package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.ActivationResponse;
import com.paymentplatform.flagserver.dto.CreateFlagRequest;
import com.paymentplatform.flagserver.dto.FlagEnvironmentResponse;
import com.paymentplatform.flagserver.dto.FlagResponse;
import com.paymentplatform.flagserver.dto.UpdateFlagRequest;
import com.paymentplatform.flagserver.entity.FeatureFlag;
import com.paymentplatform.flagserver.entity.FlagEnvironment;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import com.paymentplatform.flagserver.exception.FlagAlreadyExistsException;
import com.paymentplatform.flagserver.exception.FlagNotFoundException;
import com.paymentplatform.flagserver.exception.InvalidLifecycleTransitionException;
import com.paymentplatform.flagserver.repository.FeatureFlagRepository;
import com.paymentplatform.flagserver.repository.FlagEnvironmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class FlagService {

    private final FeatureFlagRepository flagRepository;
    private final FlagEnvironmentRepository environmentRepository;
    private final FlagAuditService auditService;
    private final TwoPhaseActivationService twoPhaseActivationService;

    public FlagService(FeatureFlagRepository flagRepository,
                       FlagEnvironmentRepository environmentRepository,
                       FlagAuditService auditService,
                       TwoPhaseActivationService twoPhaseActivationService) {
        this.flagRepository = flagRepository;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
        this.twoPhaseActivationService = twoPhaseActivationService;
    }

    public FlagResponse createFlag(CreateFlagRequest request) {
        if (flagRepository.existsByFlagKey(request.flagKey())) {
            throw new FlagAlreadyExistsException(request.flagKey());
        }

        FeatureFlag flag = new FeatureFlag();
        flag.setFlagKey(request.flagKey());
        flag.setName(request.name());
        flag.setDescription(request.description());
        flag.setOwner(request.owner());

        if (request.staleAfterDays() != null) {
            flag.setStaleAfterDays(request.staleAfterDays());
        }

        flag = flagRepository.save(flag);

        if (request.environments() != null) {
            for (String env : request.environments()) {
                FlagEnvironment flagEnv = new FlagEnvironment();
                flagEnv.setFlag(flag);
                flagEnv.setEnvironment(env);
                environmentRepository.save(flagEnv);
                flag.getEnvironments().add(flagEnv);
            }
        }

        auditService.logAction(flag.getId(), FlagAction.CREATED, null, flag.getFlagKey(), request.owner());

        return toResponse(flag);
    }

    @Transactional(readOnly = true)
    public List<FlagResponse> listFlags() {
        return flagRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public FlagResponse getFlag(String flagKey) {
        FeatureFlag flag = flagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new FlagNotFoundException(flagKey));
        return toResponse(flag);
    }

    public FlagResponse updateFlag(String flagKey, UpdateFlagRequest request) {
        FeatureFlag flag = flagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new FlagNotFoundException(flagKey));

        StringBuilder oldValues = new StringBuilder();
        StringBuilder newValues = new StringBuilder();

        if (request.name() != null && !request.name().equals(flag.getName())) {
            oldValues.append("name=").append(flag.getName()).append("; ");
            flag.setName(request.name());
            newValues.append("name=").append(flag.getName()).append("; ");
        }
        if (request.description() != null && !request.description().equals(flag.getDescription())) {
            oldValues.append("description=").append(flag.getDescription()).append("; ");
            flag.setDescription(request.description());
            newValues.append("description=").append(flag.getDescription()).append("; ");
        }
        if (request.owner() != null && !request.owner().equals(flag.getOwner())) {
            oldValues.append("owner=").append(flag.getOwner()).append("; ");
            flag.setOwner(request.owner());
            newValues.append("owner=").append(flag.getOwner()).append("; ");
        }
        if (request.staleAfterDays() != null && !request.staleAfterDays().equals(flag.getStaleAfterDays())) {
            oldValues.append("staleAfterDays=").append(flag.getStaleAfterDays()).append("; ");
            flag.setStaleAfterDays(request.staleAfterDays());
            newValues.append("staleAfterDays=").append(flag.getStaleAfterDays()).append("; ");
        }

        flag = flagRepository.save(flag);

        String oldVal = !oldValues.isEmpty() ? oldValues.toString().trim() : null;
        String newVal = !newValues.isEmpty() ? newValues.toString().trim() : null;

        if (oldVal != null || newVal != null) {
            auditService.logAction(flag.getId(), FlagAction.UPDATED, oldVal, newVal, flag.getOwner());
        }

        return toResponse(flag);
    }

    public ActivationResponse toggleFlag(String flagKey, String environment, boolean enabled) {
        FeatureFlag flag = flagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new FlagNotFoundException(flagKey));

        // Guard: cannot toggle flags in RETIRED or ARCHIVED state
        if (flag.getLifecycleState() == LifecycleState.RETIRED
                || flag.getLifecycleState() == LifecycleState.ARCHIVED) {
            throw new InvalidLifecycleTransitionException(
                    "Cannot toggle flag '" + flagKey + "' in " + flag.getLifecycleState() + " state");
        }

        // Ensure the environment exists for this flag
        environmentRepository.findByFlagIdAndEnvironment(flag.getId(), environment)
                .orElseThrow(() -> new FlagNotFoundException(
                        "Environment '" + environment + "' not found for flag: " + flagKey));

        return twoPhaseActivationService.initiateActivation(flag.getId(), flagKey, environment, enabled);
    }

    public FlagResponse addEnvironment(String flagKey, String environment) {
        FeatureFlag flag = flagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new FlagNotFoundException(flagKey));

        // Check if environment already exists
        boolean exists = flag.getEnvironments().stream()
                .anyMatch(env -> env.getEnvironment().equalsIgnoreCase(environment));
        if (exists) {
            throw new FlagAlreadyExistsException(
                    "Environment '" + environment + "' already exists for flag: " + flagKey);
        }

        FlagEnvironment flagEnv = new FlagEnvironment();
        flagEnv.setFlag(flag);
        flagEnv.setEnvironment(environment);
        environmentRepository.save(flagEnv);
        flag.getEnvironments().add(flagEnv);

        auditService.logAction(flag.getId(), FlagAction.UPDATED,
                null, "environment_added=" + environment, flag.getOwner());

        return toResponse(flag);
    }

    private FlagResponse toResponse(FeatureFlag flag) {
        List<FlagEnvironmentResponse> envResponses = flag.getEnvironments()
                .stream()
                .map(env -> new FlagEnvironmentResponse(
                        env.getId(),
                        env.getEnvironment(),
                        env.isEnabled(),
                        env.getRolloutPercentage()
                ))
                .toList();

        return new FlagResponse(
                flag.getId(),
                flag.getFlagKey(),
                flag.getName(),
                flag.getDescription(),
                flag.getOwner(),
                flag.getLifecycleState().name(),
                flag.getStaleAfterDays(),
                envResponses,
                flag.getCreatedAt(),
                flag.getUpdatedAt()
        );
    }
}
