package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.ActivationResponse;
import com.paymentplatform.flagserver.entity.FlagEnvironment;
import com.paymentplatform.flagserver.entity.InstanceRegistry;
import com.paymentplatform.flagserver.entity.PendingActivation;
import com.paymentplatform.flagserver.entity.PendingActivationAck;
import com.paymentplatform.flagserver.entity.enums.ActivationStatus;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import com.paymentplatform.flagserver.event.KafkaEventPublisher;
import com.paymentplatform.flagserver.exception.ActivationConflictException;
import com.paymentplatform.flagserver.exception.ActivationNotFoundException;
import com.paymentplatform.flagserver.exception.InstanceNotFoundException;
import com.paymentplatform.flagserver.repository.FlagEnvironmentRepository;
import com.paymentplatform.flagserver.repository.InstanceRegistryRepository;
import com.paymentplatform.flagserver.repository.PendingActivationAckRepository;
import com.paymentplatform.flagserver.repository.PendingActivationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class TwoPhaseActivationService {

    private static final Logger log = LoggerFactory.getLogger(TwoPhaseActivationService.class);

    private final PendingActivationRepository activationRepository;
    private final PendingActivationAckRepository ackRepository;
    private final InstanceRegistryRepository instanceRegistryRepository;
    private final FlagEnvironmentRepository environmentRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final FlagAuditService auditService;
    private final int defaultTimeoutSeconds;

    public TwoPhaseActivationService(PendingActivationRepository activationRepository,
                                     PendingActivationAckRepository ackRepository,
                                     InstanceRegistryRepository instanceRegistryRepository,
                                     FlagEnvironmentRepository environmentRepository,
                                     KafkaEventPublisher kafkaEventPublisher,
                                     FlagAuditService auditService,
                                     @Value("${feature-flag.activation-timeout-seconds:60}") int defaultTimeoutSeconds) {
        this.activationRepository = activationRepository;
        this.ackRepository = ackRepository;
        this.instanceRegistryRepository = instanceRegistryRepository;
        this.environmentRepository = environmentRepository;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.auditService = auditService;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public ActivationResponse initiateActivation(String flagId, String flagKey,
                                                  String environment, boolean newEnabled) {
        // Check for existing pending/preparing activation on same flag+env
        activationRepository.findByFlagIdAndEnvironmentAndStatus(flagId, environment, ActivationStatus.PENDING)
                .ifPresent(existing -> {
                    throw new ActivationConflictException(
                            "Activation already pending for flag=" + flagKey + " env=" + environment);
                });
        activationRepository.findByFlagIdAndEnvironmentAndStatus(flagId, environment, ActivationStatus.PREPARING)
                .ifPresent(existing -> {
                    throw new ActivationConflictException(
                            "Activation already in progress for flag=" + flagKey + " env=" + environment);
                });

        List<InstanceRegistry> healthyInstances = instanceRegistryRepository.findByHealthStatus(HealthStatus.HEALTHY);

        if (healthyInstances.isEmpty()) {
            // No instances registered — apply change directly
            applyToggle(flagId, environment, newEnabled);
            log.info("No healthy instances registered. Applied toggle directly for flag={} env={}", flagKey, environment);
            auditService.logAction(flagId, FlagAction.TOGGLED,
                    "enabled=" + !newEnabled, "enabled=" + newEnabled, "system");
            return null;
        }

        PendingActivation activation = new PendingActivation();
        activation.setFlagId(flagId);
        activation.setEnvironment(environment);
        activation.setNewEnabled(newEnabled);
        activation.setStatus(ActivationStatus.PREPARING);
        activation.setTotalInstances(healthyInstances.size());
        activation.setTimeoutSeconds(defaultTimeoutSeconds);
        activation = activationRepository.save(activation);

        kafkaEventPublisher.publishPrepare(activation.getId(), flagKey, flagId, environment, newEnabled);

        log.info("Initiated two-phase activation id={} for flag={} env={} totalInstances={}",
                activation.getId(), flagKey, environment, healthyInstances.size());

        return toResponse(activation);
    }

    public ActivationResponse receiveAck(String activationId, String instanceId) {
        // Pessimistic lock to prevent race between concurrent ACKs
        PendingActivation activation = activationRepository.findByIdForUpdate(activationId)
                .orElseThrow(() -> new ActivationNotFoundException(activationId));

        if (activation.getStatus() != ActivationStatus.PREPARING) {
            log.warn("ACK received for activation={} in status={}, ignoring", activationId, activation.getStatus());
            return toResponse(activation);
        }

        // Validate instance exists in the registry before recording ACK
        if (!instanceRegistryRepository.existsById(instanceId)) {
            throw new InstanceNotFoundException(instanceId);
        }

        // Check for duplicate ACK
        if (ackRepository.existsByActivationIdAndInstanceId(activationId, instanceId)) {
            log.warn("Duplicate ACK from instance={} for activation={}, ignoring", instanceId, activationId);
            return toResponse(activation);
        }

        PendingActivationAck ack = new PendingActivationAck();
        ack.setActivation(activation);
        ack.setInstanceId(instanceId);
        ackRepository.save(ack);

        activation.setAckedInstances(activation.getAckedInstances() + 1);
        activation = activationRepository.save(activation);

        log.info("ACK received: activation={} instance={} acked={}/{}",
                activationId, instanceId, activation.getAckedInstances(), activation.getTotalInstances());

        if (activation.getAckedInstances().equals(activation.getTotalInstances())) {
            return commitActivation(activation);
        }

        return toResponse(activation);
    }

    public ActivationResponse commitActivation(PendingActivation activation) {
        activation.setStatus(ActivationStatus.COMMITTED);
        activation.setCompletedAt(Instant.now());
        activation = activationRepository.save(activation);

        // Apply the actual toggle to the flag environment
        applyToggle(activation.getFlagId(), activation.getEnvironment(), activation.isNewEnabled());

        // Look up flagKey for the event
        String flagKey = lookupFlagKey(activation.getFlagId(), activation.getEnvironment());
        kafkaEventPublisher.publishCommit(activation.getId(), flagKey, activation.getFlagId(),
                activation.getEnvironment(), activation.isNewEnabled());

        auditService.logAction(activation.getFlagId(), FlagAction.TOGGLED,
                "enabled=" + !activation.isNewEnabled(), "enabled=" + activation.isNewEnabled(), "system");

        log.info("Activation COMMITTED: id={} flag={} env={}", activation.getId(), flagKey, activation.getEnvironment());
        return toResponse(activation);
    }

    public ActivationResponse rollbackActivation(PendingActivation activation, ActivationStatus rollbackStatus) {
        activation.setStatus(rollbackStatus);
        activation.setCompletedAt(Instant.now());
        activation = activationRepository.save(activation);

        String flagKey = lookupFlagKey(activation.getFlagId(), activation.getEnvironment());
        kafkaEventPublisher.publishRollback(activation.getId(), flagKey, activation.getFlagId(),
                activation.getEnvironment(), activation.isNewEnabled());

        log.info("Activation {}: id={} flag={} env={}",
                rollbackStatus, activation.getId(), flagKey, activation.getEnvironment());
        return toResponse(activation);
    }

    @Transactional(readOnly = true)
    public ActivationResponse getActivation(String activationId) {
        PendingActivation activation = activationRepository.findById(activationId)
                .orElseThrow(() -> new ActivationNotFoundException(activationId));
        return toResponse(activation);
    }

    @Transactional(readOnly = true)
    public List<ActivationResponse> getPendingActivations() {
        List<ActivationStatus> activeStatuses = List.of(ActivationStatus.PENDING, ActivationStatus.PREPARING);
        return activationRepository.findByStatusIn(activeStatuses)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private void applyToggle(String flagId, String environment, boolean newEnabled) {
        FlagEnvironment flagEnv = environmentRepository.findByFlagIdAndEnvironment(flagId, environment)
                .orElseGet(() -> {
                    log.warn("FlagEnvironment not found for flagId={} env={}, cannot apply toggle", flagId, environment);
                    return null;
                });
        if (flagEnv != null) {
            flagEnv.setEnabled(newEnabled);
            environmentRepository.save(flagEnv);
        }
    }

    private String lookupFlagKey(String flagId, String environment) {
        return environmentRepository.findByFlagIdAndEnvironment(flagId, environment)
                .map(env -> env.getFlag().getFlagKey())
                .orElse("unknown");
    }

    private ActivationResponse toResponse(PendingActivation activation) {
        List<String> ackedInstanceIds = activation.getAcks()
                .stream()
                .map(PendingActivationAck::getInstanceId)
                .toList();

        return new ActivationResponse(
                activation.getId(),
                activation.getFlagId(),
                activation.getEnvironment(),
                activation.isNewEnabled(),
                activation.getStatus().name(),
                activation.getTotalInstances() != null ? activation.getTotalInstances() : 0,
                activation.getAckedInstances() != null ? activation.getAckedInstances() : 0,
                activation.getTimeoutSeconds() != null ? activation.getTimeoutSeconds() : defaultTimeoutSeconds,
                ackedInstanceIds,
                activation.getCreatedAt(),
                activation.getCompletedAt()
        );
    }
}
