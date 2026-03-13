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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlagLifecycleServiceTest {

    @Mock
    private FeatureFlagRepository flagRepository;

    @Mock
    private FlagTransitionRepository transitionRepository;

    @Mock
    private FlagAuditService auditService;

    @InjectMocks
    private FlagLifecycleService lifecycleService;

    @Captor
    private ArgumentCaptor<FlagTransition> transitionCaptor;

    @Captor
    private ArgumentCaptor<FeatureFlag> flagCaptor;

    private FeatureFlag buildFlag(String id, String flagKey, LifecycleState state) {
        FeatureFlag flag = new FeatureFlag();
        flag.setId(id);
        flag.setFlagKey(flagKey);
        flag.setName(flagKey);
        flag.setOwner("test-owner");
        flag.setLifecycleState(state);
        flag.setStaleAfterDays(90);
        flag.setCreatedAt(Instant.now());
        flag.setUpdatedAt(Instant.now());
        return flag;
    }

    @Test
    void transitionFlag_createdToActive_success() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.CREATED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("ACTIVE", "Ready for use", "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any(FlagTransition.class))).thenAnswer(inv -> {
            FlagTransition t = inv.getArgument(0);
            t.setCreatedAt(Instant.now());
            return t;
        });

        LifecycleTransitionResponse response = lifecycleService.transitionFlag("my-flag", request);

        assertEquals("flag-1", response.flagId());
        assertEquals("my-flag", response.flagKey());
        assertEquals("CREATED", response.fromState());
        assertEquals("ACTIVE", response.toState());
        assertEquals("Ready for use", response.reason());
        assertEquals("admin", response.transitionedBy());
        assertNotNull(response.transitionedAt());

        verify(flagRepository).save(flagCaptor.capture());
        assertEquals(LifecycleState.ACTIVE, flagCaptor.getValue().getLifecycleState());

        verify(transitionRepository).save(transitionCaptor.capture());
        FlagTransition captured = transitionCaptor.getValue();
        assertEquals("flag-1", captured.getFlagId());
        assertEquals(LifecycleState.CREATED, captured.getFromState());
        assertEquals(LifecycleState.ACTIVE, captured.getToState());

        verify(auditService).logAction("flag-1", FlagAction.LIFECYCLE_TRANSITION, "CREATED", "ACTIVE", "admin");
    }

    @Test
    void transitionFlag_activeToRetired_success() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.ACTIVE);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("RETIRED", "No longer needed", "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any(FlagTransition.class))).thenAnswer(inv -> {
            FlagTransition t = inv.getArgument(0);
            t.setCreatedAt(Instant.now());
            return t;
        });

        LifecycleTransitionResponse response = lifecycleService.transitionFlag("my-flag", request);

        assertEquals("ACTIVE", response.fromState());
        assertEquals("RETIRED", response.toState());
    }

    @Test
    void transitionFlag_retiredToArchived_success() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.RETIRED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("ARCHIVED", "Cleanup complete", "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any(FlagTransition.class))).thenAnswer(inv -> {
            FlagTransition t = inv.getArgument(0);
            t.setCreatedAt(Instant.now());
            return t;
        });

        LifecycleTransitionResponse response = lifecycleService.transitionFlag("my-flag", request);

        assertEquals("RETIRED", response.fromState());
        assertEquals("ARCHIVED", response.toState());
    }

    @Test
    void transitionFlag_retiredToActive_success() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.RETIRED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("ACTIVE", "Re-activating", "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any(FlagTransition.class))).thenAnswer(inv -> {
            FlagTransition t = inv.getArgument(0);
            t.setCreatedAt(Instant.now());
            return t;
        });

        LifecycleTransitionResponse response = lifecycleService.transitionFlag("my-flag", request);

        assertEquals("RETIRED", response.fromState());
        assertEquals("ACTIVE", response.toState());
    }

    @Test
    void transitionFlag_createdToArchived_success() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.CREATED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("ARCHIVED", "Never used", "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any(FlagTransition.class))).thenAnswer(inv -> {
            FlagTransition t = inv.getArgument(0);
            t.setCreatedAt(Instant.now());
            return t;
        });

        LifecycleTransitionResponse response = lifecycleService.transitionFlag("my-flag", request);

        assertEquals("CREATED", response.fromState());
        assertEquals("ARCHIVED", response.toState());
    }

    @Test
    void transitionFlag_createdToRetired_throwsException() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.CREATED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("RETIRED", null, "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));

        InvalidLifecycleTransitionException ex = assertThrows(
                InvalidLifecycleTransitionException.class,
                () -> lifecycleService.transitionFlag("my-flag", request));

        assertTrue(ex.getMessage().contains("Cannot transition from CREATED to RETIRED"));
        verify(flagRepository, never()).save(any());
        verify(transitionRepository, never()).save(any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any());
    }

    @Test
    void transitionFlag_archivedToAny_throwsException() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.ARCHIVED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("ACTIVE", null, "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));

        InvalidLifecycleTransitionException ex = assertThrows(
                InvalidLifecycleTransitionException.class,
                () -> lifecycleService.transitionFlag("my-flag", request));

        assertTrue(ex.getMessage().contains("Cannot transition from ARCHIVED to ACTIVE"));
        verify(flagRepository, never()).save(any());
    }

    @Test
    void transitionFlag_sameState_throwsException() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.ACTIVE);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("ACTIVE", null, "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));

        InvalidLifecycleTransitionException ex = assertThrows(
                InvalidLifecycleTransitionException.class,
                () -> lifecycleService.transitionFlag("my-flag", request));

        assertTrue(ex.getMessage().contains("already in state ACTIVE"));
    }

    @Test
    void transitionFlag_invalidTargetState_throwsException() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.CREATED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("INVALID", null, "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));

        InvalidLifecycleTransitionException ex = assertThrows(
                InvalidLifecycleTransitionException.class,
                () -> lifecycleService.transitionFlag("my-flag", request));

        assertTrue(ex.getMessage().contains("Invalid target state"));
    }

    @Test
    void transitionFlag_flagNotFound_throwsException() {
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("ACTIVE", null, "admin");

        when(flagRepository.findByFlagKey("missing")).thenReturn(Optional.empty());

        assertThrows(FlagNotFoundException.class,
                () -> lifecycleService.transitionFlag("missing", request));
    }

    @Test
    void transitionFlag_caseInsensitiveTargetState() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.CREATED);
        LifecycleTransitionRequest request = new LifecycleTransitionRequest("active", "Lower case", "admin");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any(FlagTransition.class))).thenAnswer(inv -> {
            FlagTransition t = inv.getArgument(0);
            t.setCreatedAt(Instant.now());
            return t;
        });

        LifecycleTransitionResponse response = lifecycleService.transitionFlag("my-flag", request);

        assertEquals("ACTIVE", response.toState());
    }

    @Test
    void getTransitionHistory_returnsOrderedList() {
        FeatureFlag flag = buildFlag("flag-1", "my-flag", LifecycleState.ACTIVE);
        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));

        FlagTransition t1 = new FlagTransition();
        t1.setFlagId("flag-1");
        t1.setFromState(LifecycleState.CREATED);
        t1.setToState(LifecycleState.ACTIVE);
        t1.setReason("Activated");
        t1.setTransitionedBy("admin");
        t1.setCreatedAt(Instant.now().minusSeconds(3600));

        FlagTransition t2 = new FlagTransition();
        t2.setFlagId("flag-1");
        t2.setFromState(LifecycleState.ACTIVE);
        t2.setToState(LifecycleState.RETIRED);
        t2.setReason("Retiring");
        t2.setTransitionedBy("admin");
        t2.setCreatedAt(Instant.now());

        when(transitionRepository.findByFlagIdOrderByCreatedAtDesc("flag-1"))
                .thenReturn(List.of(t2, t1));

        List<LifecycleTransitionResponse> history = lifecycleService.getTransitionHistory("my-flag");

        assertEquals(2, history.size());
        assertEquals("ACTIVE", history.get(0).fromState());
        assertEquals("RETIRED", history.get(0).toState());
        assertEquals("CREATED", history.get(1).fromState());
        assertEquals("ACTIVE", history.get(1).toState());
    }

    @Test
    void getTransitionHistory_flagNotFound_throwsException() {
        when(flagRepository.findByFlagKey("missing")).thenReturn(Optional.empty());

        assertThrows(FlagNotFoundException.class,
                () -> lifecycleService.getTransitionHistory("missing"));
    }
}
