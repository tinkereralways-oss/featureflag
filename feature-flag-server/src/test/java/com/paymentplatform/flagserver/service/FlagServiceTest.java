package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.CreateFlagRequest;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock
    private FeatureFlagRepository flagRepository;

    @Mock
    private FlagEnvironmentRepository environmentRepository;

    @Mock
    private FlagAuditService auditService;

    @Mock
    private TwoPhaseActivationService twoPhaseActivationService;

    @InjectMocks
    private FlagService flagService;

    @Captor
    private ArgumentCaptor<FeatureFlag> flagCaptor;

    @Captor
    private ArgumentCaptor<FlagEnvironment> envCaptor;

    private FeatureFlag buildFlag(String id, String flagKey, String name, String description, String owner) {
        FeatureFlag flag = new FeatureFlag();
        flag.setId(id);
        flag.setFlagKey(flagKey);
        flag.setName(name);
        flag.setDescription(description);
        flag.setOwner(owner);
        flag.setLifecycleState(LifecycleState.CREATED);
        flag.setStaleAfterDays(90);
        return flag;
    }

    @Test
    void createFlag_success() {
        CreateFlagRequest request = new CreateFlagRequest(
                "enable-payments", "Enable Payments", "Payment feature toggle", "team-payments", 30, null
        );

        when(flagRepository.existsByFlagKey("enable-payments")).thenReturn(false);
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(invocation -> {
            FeatureFlag saved = invocation.getArgument(0);
            saved.setId("flag-id-123");
            return saved;
        });

        FlagResponse response = flagService.createFlag(request);

        assertNotNull(response);
        assertEquals("flag-id-123", response.id());
        assertEquals("enable-payments", response.flagKey());
        assertEquals("Enable Payments", response.name());
        assertEquals("Payment feature toggle", response.description());
        assertEquals("team-payments", response.owner());
        assertEquals(30, response.staleAfterDays());

        verify(flagRepository).save(flagCaptor.capture());
        FeatureFlag captured = flagCaptor.getValue();
        assertEquals("enable-payments", captured.getFlagKey());
        assertEquals("Enable Payments", captured.getName());
        assertEquals(30, captured.getStaleAfterDays());

        verify(auditService).logAction(eq("flag-id-123"), eq(FlagAction.CREATED), isNull(), eq("enable-payments"), eq("team-payments"));
    }

    @Test
    void createFlag_duplicateKey_throwsException() {
        CreateFlagRequest request = new CreateFlagRequest(
                "existing-key", "Name", null, null, null, null
        );

        when(flagRepository.existsByFlagKey("existing-key")).thenReturn(true);

        assertThrows(FlagAlreadyExistsException.class, () -> flagService.createFlag(request));

        verify(flagRepository, never()).save(any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any());
    }

    @Test
    void createFlag_withEnvironments() {
        CreateFlagRequest request = new CreateFlagRequest(
                "checkout-flag", "Checkout Flag", "desc", "owner1", null, List.of("dev", "staging", "prod")
        );

        when(flagRepository.existsByFlagKey("checkout-flag")).thenReturn(false);
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(invocation -> {
            FeatureFlag saved = invocation.getArgument(0);
            saved.setId("flag-id-456");
            return saved;
        });
        when(environmentRepository.save(any(FlagEnvironment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FlagResponse response = flagService.createFlag(request);

        assertNotNull(response);
        verify(environmentRepository, times(3)).save(envCaptor.capture());

        List<FlagEnvironment> capturedEnvs = envCaptor.getAllValues();
        assertEquals(3, capturedEnvs.size());
        assertEquals("dev", capturedEnvs.get(0).getEnvironment());
        assertEquals("staging", capturedEnvs.get(1).getEnvironment());
        assertEquals("prod", capturedEnvs.get(2).getEnvironment());

        for (FlagEnvironment env : capturedEnvs) {
            assertNotNull(env.getFlag());
            assertEquals("flag-id-456", env.getFlag().getId());
        }

        verify(auditService).logAction(eq("flag-id-456"), eq(FlagAction.CREATED), isNull(), eq("checkout-flag"), eq("owner1"));
    }

    @Test
    void listFlags_returnsAll() {
        FeatureFlag flag1 = buildFlag("id-1", "flag-one", "Flag One", "desc1", "owner1");
        FeatureFlag flag2 = buildFlag("id-2", "flag-two", "Flag Two", "desc2", "owner2");

        when(flagRepository.findAll()).thenReturn(List.of(flag1, flag2));

        List<FlagResponse> responses = flagService.listFlags();

        assertEquals(2, responses.size());
        assertEquals("flag-one", responses.get(0).flagKey());
        assertEquals("Flag One", responses.get(0).name());
        assertEquals("flag-two", responses.get(1).flagKey());
        assertEquals("Flag Two", responses.get(1).name());

        verify(flagRepository).findAll();
    }

    @Test
    void getFlag_found() {
        FeatureFlag flag = buildFlag("id-1", "my-flag", "My Flag", "A description", "owner1");

        when(flagRepository.findByFlagKey("my-flag")).thenReturn(Optional.of(flag));

        FlagResponse response = flagService.getFlag("my-flag");

        assertNotNull(response);
        assertEquals("id-1", response.id());
        assertEquals("my-flag", response.flagKey());
        assertEquals("My Flag", response.name());
        assertEquals("A description", response.description());
        assertEquals("owner1", response.owner());
        assertEquals("CREATED", response.lifecycleState());

        verify(flagRepository).findByFlagKey("my-flag");
    }

    @Test
    void getFlag_notFound_throwsException() {
        when(flagRepository.findByFlagKey("missing-flag")).thenReturn(Optional.empty());

        assertThrows(FlagNotFoundException.class, () -> flagService.getFlag("missing-flag"));

        verify(flagRepository).findByFlagKey("missing-flag");
    }

    @Test
    void updateFlag_success() {
        FeatureFlag existingFlag = buildFlag("id-1", "update-flag", "Old Name", "Old desc", "old-owner");

        UpdateFlagRequest request = new UpdateFlagRequest("New Name", "New desc", "new-owner", 60);

        when(flagRepository.findByFlagKey("update-flag")).thenReturn(Optional.of(existingFlag));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FlagResponse response = flagService.updateFlag("update-flag", request);

        assertNotNull(response);
        assertEquals("New Name", response.name());
        assertEquals("New desc", response.description());
        assertEquals("new-owner", response.owner());
        assertEquals(60, response.staleAfterDays());

        verify(flagRepository).save(flagCaptor.capture());
        FeatureFlag captured = flagCaptor.getValue();
        assertEquals("New Name", captured.getName());
        assertEquals("New desc", captured.getDescription());
        assertEquals("new-owner", captured.getOwner());
        assertEquals(60, captured.getStaleAfterDays());

        // Audit captures all changed fields
        verify(auditService).logAction(
                eq("id-1"),
                eq(FlagAction.UPDATED),
                argThat(old -> old.contains("name=Old Name") && old.contains("description=Old desc")
                        && old.contains("owner=old-owner") && old.contains("staleAfterDays=90")),
                argThat(nw -> nw.contains("name=New Name") && nw.contains("description=New desc")
                        && nw.contains("owner=new-owner") && nw.contains("staleAfterDays=60")),
                eq("new-owner")
        );
    }

    @Test
    void updateFlag_notFound_throwsException() {
        UpdateFlagRequest request = new UpdateFlagRequest("Name", null, null, null);

        when(flagRepository.findByFlagKey("nonexistent")).thenReturn(Optional.empty());

        assertThrows(FlagNotFoundException.class, () -> flagService.updateFlag("nonexistent", request));

        verify(flagRepository, never()).save(any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any());
    }

    @Test
    void toggleFlag_retiredFlag_throwsException() {
        FeatureFlag flag = buildFlag("id-1", "retired-flag", "Retired Flag", "desc", "owner");
        flag.setLifecycleState(LifecycleState.RETIRED);

        when(flagRepository.findByFlagKey("retired-flag")).thenReturn(Optional.of(flag));

        InvalidLifecycleTransitionException ex = assertThrows(
                InvalidLifecycleTransitionException.class,
                () -> flagService.toggleFlag("retired-flag", "prod", true));

        assertTrue(ex.getMessage().contains("RETIRED"));
        verify(twoPhaseActivationService, never()).initiateActivation(any(), any(), any(), anyBoolean());
    }

    @Test
    void toggleFlag_archivedFlag_throwsException() {
        FeatureFlag flag = buildFlag("id-1", "archived-flag", "Archived Flag", "desc", "owner");
        flag.setLifecycleState(LifecycleState.ARCHIVED);

        when(flagRepository.findByFlagKey("archived-flag")).thenReturn(Optional.of(flag));

        InvalidLifecycleTransitionException ex = assertThrows(
                InvalidLifecycleTransitionException.class,
                () -> flagService.toggleFlag("archived-flag", "prod", true));

        assertTrue(ex.getMessage().contains("ARCHIVED"));
        verify(twoPhaseActivationService, never()).initiateActivation(any(), any(), any(), anyBoolean());
    }

    @Test
    void toggleFlag_createdFlag_allowed() {
        FeatureFlag flag = buildFlag("id-1", "new-flag", "New Flag", "desc", "owner");
        flag.setLifecycleState(LifecycleState.CREATED);

        FlagEnvironment env = new FlagEnvironment();
        env.setFlag(flag);
        env.setEnvironment("prod");
        flag.getEnvironments().add(env);

        when(flagRepository.findByFlagKey("new-flag")).thenReturn(Optional.of(flag));
        when(environmentRepository.findByFlagIdAndEnvironment("id-1", "prod"))
                .thenReturn(Optional.of(env));

        flagService.toggleFlag("new-flag", "prod", true);

        verify(twoPhaseActivationService).initiateActivation("id-1", "new-flag", "prod", true);
    }

    @Test
    void toggleFlag_activeFlag_allowed() {
        FeatureFlag flag = buildFlag("id-1", "active-flag", "Active Flag", "desc", "owner");
        flag.setLifecycleState(LifecycleState.ACTIVE);

        FlagEnvironment env = new FlagEnvironment();
        env.setFlag(flag);
        env.setEnvironment("prod");
        flag.getEnvironments().add(env);

        when(flagRepository.findByFlagKey("active-flag")).thenReturn(Optional.of(flag));
        when(environmentRepository.findByFlagIdAndEnvironment("id-1", "prod"))
                .thenReturn(Optional.of(env));

        flagService.toggleFlag("active-flag", "prod", true);

        verify(twoPhaseActivationService).initiateActivation("id-1", "active-flag", "prod", true);
    }
}
