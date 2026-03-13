package com.paymentplatform.flagserver.scheduler;

import com.paymentplatform.flagserver.entity.PendingActivation;
import com.paymentplatform.flagserver.entity.enums.ActivationStatus;
import com.paymentplatform.flagserver.repository.PendingActivationRepository;
import com.paymentplatform.flagserver.service.TwoPhaseActivationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivationTimeoutSchedulerTest {

    @Mock
    private PendingActivationRepository activationRepository;

    @Mock
    private TwoPhaseActivationService activationService;

    @InjectMocks
    private ActivationTimeoutScheduler scheduler;

    private PendingActivation buildActivation(String id, ActivationStatus status, int timeoutSeconds, Instant createdAt) {
        PendingActivation activation = new PendingActivation();
        activation.setId(id);
        activation.setFlagId("flag-1");
        activation.setEnvironment("prod");
        activation.setNewEnabled(true);
        activation.setStatus(status);
        activation.setTotalInstances(2);
        activation.setAckedInstances(1);
        activation.setTimeoutSeconds(timeoutSeconds);
        activation.setCreatedAt(createdAt);
        activation.setAcks(new ArrayList<>());
        return activation;
    }

    @Test
    void checkTimeouts_timedOutActivation_triggersRollback() {
        PendingActivation timedOut = buildActivation("act-1", ActivationStatus.PREPARING, 60,
                Instant.now().minusSeconds(120)); // 2 minutes ago, 60s timeout

        when(activationRepository.findByStatusIn(anyList())).thenReturn(List.of(timedOut));

        scheduler.checkTimeouts();

        verify(activationService).rollbackActivation(eq(timedOut), eq(ActivationStatus.TIMED_OUT));
    }

    @Test
    void checkTimeouts_activeActivation_noRollback() {
        PendingActivation active = buildActivation("act-1", ActivationStatus.PREPARING, 60,
                Instant.now().minusSeconds(10)); // 10 seconds ago, 60s timeout

        when(activationRepository.findByStatusIn(anyList())).thenReturn(List.of(active));

        scheduler.checkTimeouts();

        verify(activationService, never()).rollbackActivation(any(), any());
    }

    @Test
    void checkTimeouts_noActiveActivations_doesNothing() {
        when(activationRepository.findByStatusIn(anyList())).thenReturn(List.of());

        scheduler.checkTimeouts();

        verify(activationService, never()).rollbackActivation(any(), any());
    }

    @Test
    void checkTimeouts_mixedActivations_onlyRollsBackTimedOut() {
        PendingActivation timedOut = buildActivation("act-1", ActivationStatus.PREPARING, 30,
                Instant.now().minusSeconds(60));
        PendingActivation active = buildActivation("act-2", ActivationStatus.PREPARING, 60,
                Instant.now().minusSeconds(10));

        when(activationRepository.findByStatusIn(anyList())).thenReturn(List.of(timedOut, active));

        scheduler.checkTimeouts();

        verify(activationService).rollbackActivation(eq(timedOut), eq(ActivationStatus.TIMED_OUT));
        verify(activationService, never()).rollbackActivation(eq(active), any());
    }
}
