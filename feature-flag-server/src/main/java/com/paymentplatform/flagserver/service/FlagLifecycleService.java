package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.LifecycleTransitionRequest;
import com.paymentplatform.flagserver.dto.LifecycleTransitionResponse;
import com.paymentplatform.flagserver.entity.FeatureFlag;
import com.paymentplatform.flagserver.entity.FlagTransition;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import com.paymentplatform.flagserver.exception.FlagNotFoundException;
import com.paymentplatform.flagserver.exception.InvalidLifecycleTransitionException;
import com.paymentplatform.flagserver.repository.FeatureFlagRepository;
import com.paymentplatform.flagserver.repository.FlagTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class FlagLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(FlagLifecycleService.class);

    private static final Map<LifecycleState, Set<LifecycleState>> VALID_TRANSITIONS = Map.of(
            LifecycleState.CREATED, Set.of(LifecycleState.ACTIVE, LifecycleState.ARCHIVED),
            LifecycleState.ACTIVE, Set.of(LifecycleState.RETIRED, LifecycleState.ARCHIVED),
            LifecycleState.RETIRED, Set.of(LifecycleState.ARCHIVED, LifecycleState.ACTIVE),
            LifecycleState.ARCHIVED, Set.of()
    );

    private final FeatureFlagRepository flagRepository;
    private final FlagTransitionRepository transitionRepository;
    private final FlagAuditService auditService;

    public FlagLifecycleService(FeatureFlagRepository flagRepository,
                                FlagTransitionRepository transitionRepository,
                                FlagAuditService auditService) {
        this.flagRepository = flagRepository;
        this.transitionRepository = transitionRepository;
        this.auditService = auditService;
    }

    public LifecycleTransitionResponse transitionFlag(String flagKey, LifecycleTransitionRequest request) {
        FeatureFlag flag = flagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new FlagNotFoundException(flagKey));

        LifecycleState targetState;
        try {
            targetState = LifecycleState.valueOf(request.targetState().toUpperCase());
        } catch (IllegalArgumentException e) {
            String validStates = Arrays.stream(LifecycleState.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new InvalidLifecycleTransitionException(
                    "Invalid target state: " + request.targetState() +
                            ". Valid states: " + validStates);
        }

        LifecycleState currentState = flag.getLifecycleState();
        validateTransition(currentState, targetState);

        flag.setLifecycleState(targetState);
        flagRepository.save(flag);

        FlagTransition transition = new FlagTransition();
        transition.setFlagId(flag.getId());
        transition.setFromState(currentState);
        transition.setToState(targetState);
        transition.setReason(request.reason());
        transition.setTransitionedBy(request.transitionedBy());
        transitionRepository.save(transition);

        auditService.logAction(
                flag.getId(),
                FlagAction.LIFECYCLE_TRANSITION,
                currentState.name(),
                targetState.name(),
                request.transitionedBy()
        );

        log.info("Flag '{}' transitioned from {} to {} by {}",
                flagKey, currentState, targetState, request.transitionedBy());

        return new LifecycleTransitionResponse(
                flag.getId(),
                flag.getFlagKey(),
                currentState.name(),
                targetState.name(),
                request.reason(),
                request.transitionedBy(),
                transition.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<LifecycleTransitionResponse> getTransitionHistory(String flagKey) {
        FeatureFlag flag = flagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new FlagNotFoundException(flagKey));

        return transitionRepository.findByFlagIdOrderByCreatedAtDesc(flag.getId())
                .stream()
                .map(t -> new LifecycleTransitionResponse(
                        t.getFlagId(),
                        flag.getFlagKey(),
                        t.getFromState().name(),
                        t.getToState().name(),
                        t.getReason(),
                        t.getTransitionedBy(),
                        t.getCreatedAt()
                ))
                .toList();
    }

    private void validateTransition(LifecycleState from, LifecycleState to) {
        if (from == to) {
            throw new InvalidLifecycleTransitionException(
                    "Flag is already in state " + from);
        }

        Set<LifecycleState> validTargets = VALID_TRANSITIONS.getOrDefault(from, Set.of());
        if (!validTargets.contains(to)) {
            throw new InvalidLifecycleTransitionException(
                    "Cannot transition from " + from + " to " + to +
                            ". Valid transitions from " + from + ": " + validTargets);
        }
    }
}
