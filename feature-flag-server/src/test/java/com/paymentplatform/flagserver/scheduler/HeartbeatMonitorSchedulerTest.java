package com.paymentplatform.flagserver.scheduler;

import com.paymentplatform.flagserver.entity.InstanceRegistry;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import com.paymentplatform.flagserver.repository.InstanceRegistryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatMonitorSchedulerTest {

    @Mock
    private InstanceRegistryRepository instanceRegistryRepository;

    @Captor
    private ArgumentCaptor<List<InstanceRegistry>> instanceListCaptor;

    private InstanceRegistry buildInstance(String instanceId, String serviceName, Instant lastHeartbeat) {
        InstanceRegistry instance = new InstanceRegistry();
        instance.setInstanceId(instanceId);
        instance.setServiceName(serviceName);
        instance.setHealthStatus(HealthStatus.HEALTHY);
        instance.setLastHeartbeat(lastHeartbeat);
        instance.setRegisteredAt(Instant.now());
        return instance;
    }

    @Test
    void checkHeartbeats_marksStaleInstancesUnhealthy() {
        HeartbeatMonitorScheduler scheduler = new HeartbeatMonitorScheduler(instanceRegistryRepository, 45);

        InstanceRegistry staleInstance = buildInstance("inst-001", "payment-service",
                Instant.now().minusSeconds(60));

        when(instanceRegistryRepository.findByLastHeartbeatBeforeAndHealthStatus(any(Instant.class), eq(HealthStatus.HEALTHY)))
                .thenReturn(List.of(staleInstance));
        when(instanceRegistryRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        scheduler.checkHeartbeats();

        verify(instanceRegistryRepository).saveAll(instanceListCaptor.capture());
        List<InstanceRegistry> captured = instanceListCaptor.getValue();
        assertEquals(1, captured.size());
        assertEquals(HealthStatus.UNHEALTHY, captured.get(0).getHealthStatus());
        assertEquals("inst-001", captured.get(0).getInstanceId());
    }

    @Test
    void checkHeartbeats_noStaleInstances_noUpdates() {
        HeartbeatMonitorScheduler scheduler = new HeartbeatMonitorScheduler(instanceRegistryRepository, 45);

        when(instanceRegistryRepository.findByLastHeartbeatBeforeAndHealthStatus(any(Instant.class), eq(HealthStatus.HEALTHY)))
                .thenReturn(Collections.emptyList());

        scheduler.checkHeartbeats();

        verify(instanceRegistryRepository, never()).saveAll(anyList());
    }

    @Test
    void checkHeartbeats_multipleStaleInstances_allMarkedUnhealthy() {
        HeartbeatMonitorScheduler scheduler = new HeartbeatMonitorScheduler(instanceRegistryRepository, 45);

        InstanceRegistry stale1 = buildInstance("inst-001", "svc-a", Instant.now().minusSeconds(60));
        InstanceRegistry stale2 = buildInstance("inst-002", "svc-b", Instant.now().minusSeconds(120));

        when(instanceRegistryRepository.findByLastHeartbeatBeforeAndHealthStatus(any(Instant.class), eq(HealthStatus.HEALTHY)))
                .thenReturn(List.of(stale1, stale2));
        when(instanceRegistryRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        scheduler.checkHeartbeats();

        verify(instanceRegistryRepository).saveAll(instanceListCaptor.capture());
        List<InstanceRegistry> captured = instanceListCaptor.getValue();
        assertEquals(2, captured.size());
        captured.forEach(inst -> assertEquals(HealthStatus.UNHEALTHY, inst.getHealthStatus()));
    }

    @Test
    void checkHeartbeats_usesConfiguredThreshold() {
        HeartbeatMonitorScheduler scheduler = new HeartbeatMonitorScheduler(instanceRegistryRepository, 30);

        when(instanceRegistryRepository.findByLastHeartbeatBeforeAndHealthStatus(any(Instant.class), eq(HealthStatus.HEALTHY)))
                .thenReturn(Collections.emptyList());

        scheduler.checkHeartbeats();

        // Verify the threshold instant is calculated correctly (within a reasonable margin)
        verify(instanceRegistryRepository).findByLastHeartbeatBeforeAndHealthStatus(
                argThat(instant -> {
                    long secondsAgo = Instant.now().getEpochSecond() - instant.getEpochSecond();
                    return secondsAgo >= 29 && secondsAgo <= 31;
                }),
                eq(HealthStatus.HEALTHY)
        );
    }
}
