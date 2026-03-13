package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.ActivationResponse;
import com.paymentplatform.flagserver.entity.FeatureFlag;
import com.paymentplatform.flagserver.entity.FlagEnvironment;
import com.paymentplatform.flagserver.entity.InstanceRegistry;
import com.paymentplatform.flagserver.entity.PendingActivation;
import com.paymentplatform.flagserver.entity.PendingActivationAck;
import com.paymentplatform.flagserver.entity.enums.ActivationStatus;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import com.paymentplatform.flagserver.event.KafkaEventPublisher;
import com.paymentplatform.flagserver.exception.ActivationConflictException;
import com.paymentplatform.flagserver.exception.ActivationNotFoundException;
import com.paymentplatform.flagserver.exception.InstanceNotFoundException;
import com.paymentplatform.flagserver.repository.FlagEnvironmentRepository;
import com.paymentplatform.flagserver.repository.InstanceRegistryRepository;
import com.paymentplatform.flagserver.repository.PendingActivationAckRepository;
import com.paymentplatform.flagserver.repository.PendingActivationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwoPhaseActivationServiceTest {

    @Mock
    private PendingActivationRepository activationRepository;

    @Mock
    private PendingActivationAckRepository ackRepository;

    @Mock
    private InstanceRegistryRepository instanceRegistryRepository;

    @Mock
    private FlagEnvironmentRepository environmentRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private FlagAuditService auditService;

    private TwoPhaseActivationService activationService;

    @BeforeEach
    void setUp() {
        activationService = new TwoPhaseActivationService(
                activationRepository, ackRepository, instanceRegistryRepository,
                environmentRepository, kafkaEventPublisher, auditService, 60);
    }

    @Captor
    private ArgumentCaptor<PendingActivation> activationCaptor;

    private InstanceRegistry buildInstance(String id) {
        InstanceRegistry instance = new InstanceRegistry();
        instance.setInstanceId(id);
        instance.setServiceName("test-service");
        instance.setHealthStatus(HealthStatus.HEALTHY);
        instance.setLastHeartbeat(Instant.now());
        return instance;
    }

    private PendingActivation buildActivation(String id, String flagId, String env,
                                               ActivationStatus status, int total, int acked) {
        PendingActivation activation = new PendingActivation();
        activation.setId(id);
        activation.setFlagId(flagId);
        activation.setEnvironment(env);
        activation.setNewEnabled(true);
        activation.setStatus(status);
        activation.setTotalInstances(total);
        activation.setAckedInstances(acked);
        activation.setTimeoutSeconds(60);
        activation.setCreatedAt(Instant.now());
        activation.setAcks(new ArrayList<>());
        return activation;
    }

    @Test
    void initiateActivation_withHealthyInstances_publishesPrepare() {
        List<InstanceRegistry> instances = List.of(buildInstance("inst-1"), buildInstance("inst-2"));
        when(instanceRegistryRepository.findByHealthStatus(HealthStatus.HEALTHY)).thenReturn(instances);
        when(activationRepository.findByFlagIdAndEnvironmentAndStatus(anyString(), anyString(), eq(ActivationStatus.PENDING)))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFlagIdAndEnvironmentAndStatus(anyString(), anyString(), eq(ActivationStatus.PREPARING)))
                .thenReturn(Optional.empty());
        when(activationRepository.save(any(PendingActivation.class))).thenAnswer(inv -> {
            PendingActivation saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId("act-123");
            saved.setCreatedAt(Instant.now());
            return saved;
        });

        ActivationResponse response = activationService.initiateActivation("flag-id", "my-flag", "prod", true);

        assertNotNull(response);
        assertEquals("PREPARING", response.status());
        assertEquals(2, response.totalInstances());
        assertEquals(0, response.ackedInstances());

        verify(kafkaEventPublisher).publishPrepare("act-123", "my-flag", "flag-id", "prod", true);
        verify(activationRepository).save(activationCaptor.capture());
        PendingActivation captured = activationCaptor.getValue();
        assertEquals(ActivationStatus.PREPARING, captured.getStatus());
        assertEquals(2, captured.getTotalInstances());
    }

    @Test
    void initiateActivation_noInstances_appliesDirectly() {
        when(instanceRegistryRepository.findByHealthStatus(HealthStatus.HEALTHY)).thenReturn(List.of());
        when(activationRepository.findByFlagIdAndEnvironmentAndStatus(anyString(), anyString(), eq(ActivationStatus.PENDING)))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFlagIdAndEnvironmentAndStatus(anyString(), anyString(), eq(ActivationStatus.PREPARING)))
                .thenReturn(Optional.empty());

        FlagEnvironment flagEnv = new FlagEnvironment();
        flagEnv.setEnabled(false);
        when(environmentRepository.findByFlagIdAndEnvironment("flag-id", "prod")).thenReturn(Optional.of(flagEnv));
        when(environmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivationResponse response = activationService.initiateActivation("flag-id", "my-flag", "prod", true);

        assertNull(response);
        verify(environmentRepository).save(any(FlagEnvironment.class));
        verify(kafkaEventPublisher, never()).publishPrepare(any(), any(), any(), any(), anyBoolean());
        verify(auditService).logAction(eq("flag-id"), any(), eq("enabled=false"), eq("enabled=true"), eq("system"));
    }

    @Test
    void initiateActivation_conflictPending_throwsException() {
        PendingActivation existing = buildActivation("existing", "flag-id", "prod", ActivationStatus.PENDING, 2, 0);
        when(activationRepository.findByFlagIdAndEnvironmentAndStatus("flag-id", "prod", ActivationStatus.PENDING))
                .thenReturn(Optional.of(existing));

        assertThrows(ActivationConflictException.class,
                () -> activationService.initiateActivation("flag-id", "my-flag", "prod", true));
    }

    @Test
    void receiveAck_incrementsCount() {
        PendingActivation activation = buildActivation("act-1", "flag-id", "prod", ActivationStatus.PREPARING, 3, 0);

        when(activationRepository.findByIdForUpdate("act-1")).thenReturn(Optional.of(activation));
        when(instanceRegistryRepository.existsById("inst-1")).thenReturn(true);
        when(ackRepository.existsByActivationIdAndInstanceId("act-1", "inst-1")).thenReturn(false);
        when(ackRepository.save(any(PendingActivationAck.class))).thenAnswer(inv -> inv.getArgument(0));
        when(activationRepository.save(any(PendingActivation.class))).thenAnswer(inv -> inv.getArgument(0));

        ActivationResponse response = activationService.receiveAck("act-1", "inst-1");

        assertNotNull(response);
        assertEquals(1, response.ackedInstances());
        assertEquals("PREPARING", response.status());
        verify(ackRepository).save(any(PendingActivationAck.class));
    }

    @Test
    void receiveAck_lastAck_triggersCommit() {
        PendingActivation activation = buildActivation("act-1", "flag-id", "prod", ActivationStatus.PREPARING, 2, 1);

        when(activationRepository.findByIdForUpdate("act-1")).thenReturn(Optional.of(activation));
        when(instanceRegistryRepository.existsById("inst-2")).thenReturn(true);
        when(ackRepository.existsByActivationIdAndInstanceId("act-1", "inst-2")).thenReturn(false);
        when(ackRepository.save(any(PendingActivationAck.class))).thenAnswer(inv -> inv.getArgument(0));
        when(activationRepository.save(any(PendingActivation.class))).thenAnswer(inv -> inv.getArgument(0));

        FeatureFlag flag = new FeatureFlag();
        flag.setFlagKey("my-flag");
        FlagEnvironment flagEnv = new FlagEnvironment();
        flagEnv.setEnabled(false);
        flagEnv.setFlag(flag);
        when(environmentRepository.findByFlagIdAndEnvironment("flag-id", "prod")).thenReturn(Optional.of(flagEnv));
        when(environmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivationResponse response = activationService.receiveAck("act-1", "inst-2");

        assertNotNull(response);
        assertEquals("COMMITTED", response.status());
        verify(kafkaEventPublisher).publishCommit(eq("act-1"), any(), eq("flag-id"), eq("prod"), eq(true));
        verify(environmentRepository).save(any(FlagEnvironment.class));
    }

    @Test
    void receiveAck_notFound_throwsException() {
        when(activationRepository.findByIdForUpdate("nonexistent")).thenReturn(Optional.empty());

        assertThrows(ActivationNotFoundException.class,
                () -> activationService.receiveAck("nonexistent", "inst-1"));
    }

    @Test
    void receiveAck_unknownInstance_throwsException() {
        PendingActivation activation = buildActivation("act-1", "flag-id", "prod", ActivationStatus.PREPARING, 2, 0);

        when(activationRepository.findByIdForUpdate("act-1")).thenReturn(Optional.of(activation));
        when(instanceRegistryRepository.existsById("unknown-inst")).thenReturn(false);

        assertThrows(InstanceNotFoundException.class,
                () -> activationService.receiveAck("act-1", "unknown-inst"));
    }

    @Test
    void receiveAck_duplicateAck_ignored() {
        PendingActivation activation = buildActivation("act-1", "flag-id", "prod", ActivationStatus.PREPARING, 2, 1);

        when(activationRepository.findByIdForUpdate("act-1")).thenReturn(Optional.of(activation));
        when(instanceRegistryRepository.existsById("inst-1")).thenReturn(true);
        when(ackRepository.existsByActivationIdAndInstanceId("act-1", "inst-1")).thenReturn(true);

        ActivationResponse response = activationService.receiveAck("act-1", "inst-1");

        assertNotNull(response);
        assertEquals(1, response.ackedInstances()); // unchanged
        verify(ackRepository, never()).save(any());
    }

    @Test
    void receiveAck_afterTimeout_ignored() {
        PendingActivation activation = buildActivation("act-1", "flag-id", "prod", ActivationStatus.TIMED_OUT, 2, 1);

        when(activationRepository.findByIdForUpdate("act-1")).thenReturn(Optional.of(activation));

        ActivationResponse response = activationService.receiveAck("act-1", "inst-1");

        assertNotNull(response);
        assertEquals("TIMED_OUT", response.status());
        verify(ackRepository, never()).save(any());
    }

    @Test
    void rollbackActivation_setsStatusAndPublishes() {
        PendingActivation activation = buildActivation("act-1", "flag-id", "prod", ActivationStatus.PREPARING, 2, 1);

        when(activationRepository.save(any(PendingActivation.class))).thenAnswer(inv -> inv.getArgument(0));

        FlagEnvironment flagEnv = new FlagEnvironment();
        flagEnv.setEnvironment("prod");
        FeatureFlag flag = new FeatureFlag();
        flag.setFlagKey("my-flag");
        flagEnv.setFlag(flag);
        when(environmentRepository.findByFlagIdAndEnvironment("flag-id", "prod")).thenReturn(Optional.of(flagEnv));

        ActivationResponse response = activationService.rollbackActivation(activation, ActivationStatus.TIMED_OUT);

        assertNotNull(response);
        assertEquals("TIMED_OUT", response.status());
        assertNotNull(response.completedAt());
        verify(kafkaEventPublisher).publishRollback(eq("act-1"), eq("my-flag"), eq("flag-id"), eq("prod"), eq(true));
    }
}
