package com.paymentplatform.flagserver.scheduler;

import com.paymentplatform.flagserver.entity.PendingActivation;
import com.paymentplatform.flagserver.entity.enums.ActivationStatus;
import com.paymentplatform.flagserver.repository.PendingActivationRepository;
import com.paymentplatform.flagserver.service.TwoPhaseActivationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ActivationTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActivationTimeoutScheduler.class);

    private final PendingActivationRepository activationRepository;
    private final TwoPhaseActivationService activationService;

    public ActivationTimeoutScheduler(PendingActivationRepository activationRepository,
                                      TwoPhaseActivationService activationService) {
        this.activationRepository = activationRepository;
        this.activationService = activationService;
    }

    @Scheduled(fixedDelayString = "${feature-flag.activation-timeout-check-interval-ms:5000}")
    public void checkTimeouts() {
        List<ActivationStatus> activeStatuses = List.of(ActivationStatus.PENDING, ActivationStatus.PREPARING);
        List<PendingActivation> activeActivations = activationRepository.findByStatusIn(activeStatuses);

        Instant now = Instant.now();
        for (PendingActivation activation : activeActivations) {
            if (isTimedOut(activation, now)) {
                log.warn("Activation timed out: id={} flagId={} env={} acked={}/{}",
                        activation.getId(), activation.getFlagId(), activation.getEnvironment(),
                        activation.getAckedInstances(), activation.getTotalInstances());
                activationService.rollbackActivation(activation, ActivationStatus.TIMED_OUT);
            }
        }
    }

    private boolean isTimedOut(PendingActivation activation, Instant now) {
        if (activation.getCreatedAt() == null || activation.getTimeoutSeconds() == null) {
            return false;
        }
        Instant deadline = activation.getCreatedAt().plusSeconds(activation.getTimeoutSeconds());
        return now.isAfter(deadline);
    }
}
