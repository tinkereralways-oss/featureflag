package com.paymentplatform.flagserver.scheduler;

import com.paymentplatform.flagserver.dto.LifecycleTransitionRequest;
import com.paymentplatform.flagserver.entity.FeatureFlag;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import com.paymentplatform.flagserver.repository.FeatureFlagRepository;
import com.paymentplatform.flagserver.repository.FlagAuditLogRepository;
import com.paymentplatform.flagserver.service.FlagAuditService;
import com.paymentplatform.flagserver.service.FlagLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StalenessCheckSchedulerTest {

    @Mock
    private FeatureFlagRepository flagRepository;

    @Mock
    private FlagAuditLogRepository auditLogRepository;

    @Mock
    private FlagAuditService auditService;

    @Mock
    private FlagLifecycleService lifecycleService;

    @Captor
    private ArgumentCaptor<LifecycleTransitionRequest> transitionRequestCaptor;

    private FeatureFlag buildActiveFlag(String id, String flagKey, int staleAfterDays, Instant updatedAt) {
        FeatureFlag flag = new FeatureFlag();
        flag.setId(id);
        flag.setFlagKey(flagKey);
        flag.setName(flagKey);
        flag.setOwner("test-owner");
        flag.setLifecycleState(LifecycleState.ACTIVE);
        flag.setStaleAfterDays(staleAfterDays);
        flag.setCreatedAt(updatedAt);
        flag.setUpdatedAt(updatedAt);
        return flag;
    }

    @Test
    void checkStaleness_noActiveFlags_noAction() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, false);

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(Collections.emptyList());

        scheduler.checkStaleness();

        verify(lifecycleService, never()).transitionFlag(any(), any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkStaleness_freshFlag_noAction() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, false);

        FeatureFlag freshFlag = buildActiveFlag("flag-1", "fresh-flag", 90, Instant.now().minus(10, ChronoUnit.DAYS));

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(List.of(freshFlag));

        scheduler.checkStaleness();

        verify(lifecycleService, never()).transitionFlag(any(), any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkStaleness_staleFlag_logsWarning() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, false);

        FeatureFlag staleFlag = buildActiveFlag("flag-1", "stale-flag", 90, Instant.now().minus(100, ChronoUnit.DAYS));

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(List.of(staleFlag));
        when(auditLogRepository.existsByFlagIdAndAction("flag-1", FlagAction.STALE_WARNING))
                .thenReturn(false);

        scheduler.checkStaleness();

        verify(lifecycleService, never()).transitionFlag(any(), any());
        verify(auditService).logAction(
                eq("flag-1"),
                eq(FlagAction.STALE_WARNING),
                isNull(),
                isNull(),
                eq("system"),
                argThat(meta -> meta.contains("90 days"))
        );
    }

    @Test
    void checkStaleness_staleFlag_alreadyWarned_skipsAudit() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, false);

        FeatureFlag staleFlag = buildActiveFlag("flag-1", "stale-flag", 90, Instant.now().minus(100, ChronoUnit.DAYS));

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(List.of(staleFlag));
        when(auditLogRepository.existsByFlagIdAndAction("flag-1", FlagAction.STALE_WARNING))
                .thenReturn(true);

        scheduler.checkStaleness();

        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkStaleness_staleFlagWithAutoRetire_delegatesToLifecycleService() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, true);

        FeatureFlag staleFlag = buildActiveFlag("flag-1", "stale-flag", 30, Instant.now().minus(60, ChronoUnit.DAYS));

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(List.of(staleFlag));

        scheduler.checkStaleness();

        verify(lifecycleService).transitionFlag(eq("stale-flag"), transitionRequestCaptor.capture());
        LifecycleTransitionRequest captured = transitionRequestCaptor.getValue();
        assertEquals("RETIRED", captured.targetState());
        assertEquals("system", captured.transitionedBy());
        assertTrue(captured.reason().contains("30 days"));
    }

    @Test
    void checkStaleness_mixedFreshAndStale() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, false);

        FeatureFlag freshFlag = buildActiveFlag("flag-1", "fresh", 90, Instant.now().minus(10, ChronoUnit.DAYS));
        FeatureFlag staleFlag = buildActiveFlag("flag-2", "stale", 30, Instant.now().minus(60, ChronoUnit.DAYS));

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(List.of(freshFlag, staleFlag));
        when(auditLogRepository.existsByFlagIdAndAction("flag-2", FlagAction.STALE_WARNING))
                .thenReturn(false);

        scheduler.checkStaleness();

        verify(auditService).logAction(
                eq("flag-2"),
                eq(FlagAction.STALE_WARNING),
                isNull(),
                isNull(),
                eq("system"),
                any()
        );
        verify(auditService, times(1)).logAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkStaleness_nullStaleAfterDays_skipped() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, false);

        FeatureFlag flag = buildActiveFlag("flag-1", "no-stale-config", 0, Instant.now().minus(365, ChronoUnit.DAYS));
        flag.setStaleAfterDays(null);

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(List.of(flag));

        scheduler.checkStaleness();

        verify(lifecycleService, never()).transitionFlag(any(), any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkStaleness_zeroStaleAfterDays_skipped() {
        StalenessCheckScheduler scheduler = new StalenessCheckScheduler(
                flagRepository, auditLogRepository, auditService, lifecycleService, false);

        FeatureFlag flag = buildActiveFlag("flag-1", "zero-stale", 0, Instant.now().minus(365, ChronoUnit.DAYS));

        when(flagRepository.findByLifecycleState(LifecycleState.ACTIVE))
                .thenReturn(List.of(flag));

        scheduler.checkStaleness();

        verify(lifecycleService, never()).transitionFlag(any(), any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any());
    }
}
