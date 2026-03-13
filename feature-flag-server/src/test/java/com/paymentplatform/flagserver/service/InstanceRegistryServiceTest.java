package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.HeartbeatRequest;
import com.paymentplatform.flagserver.dto.InstanceStatusResponse;
import com.paymentplatform.flagserver.dto.RegisterInstanceRequest;
import com.paymentplatform.flagserver.entity.InstanceRegistry;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import com.paymentplatform.flagserver.exception.InstanceNotFoundException;
import com.paymentplatform.flagserver.repository.InstanceRegistryRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstanceRegistryServiceTest {

    @Mock
    private InstanceRegistryRepository instanceRegistryRepository;

    @InjectMocks
    private InstanceRegistryService instanceRegistryService;

    @Captor
    private ArgumentCaptor<InstanceRegistry> instanceCaptor;

    private InstanceRegistry buildInstance(String instanceId, String serviceName, HealthStatus status) {
        InstanceRegistry instance = new InstanceRegistry();
        instance.setInstanceId(instanceId);
        instance.setServiceName(serviceName);
        instance.setHostAddress("192.168.1.10");
        instance.setPort(8080);
        instance.setHealthStatus(status);
        instance.setSdkVersion("1.0.0");
        instance.setLastHeartbeat(Instant.now());
        instance.setRegisteredAt(Instant.now());
        return instance;
    }

    @Test
    void register_newInstance_success() {
        RegisterInstanceRequest request = new RegisterInstanceRequest(
                "inst-001", "payment-service", "192.168.1.10", 8080, "1.0.0"
        );

        when(instanceRegistryRepository.findById("inst-001")).thenReturn(Optional.empty());
        when(instanceRegistryRepository.save(any(InstanceRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InstanceStatusResponse response = instanceRegistryService.register(request);

        assertNotNull(response);
        assertEquals("inst-001", response.instanceId());
        assertEquals("payment-service", response.serviceName());
        assertEquals("192.168.1.10", response.hostAddress());
        assertEquals(8080, response.port());
        assertEquals("HEALTHY", response.healthStatus());
        assertEquals("1.0.0", response.sdkVersion());
        assertNotNull(response.lastHeartbeat());

        verify(instanceRegistryRepository).save(instanceCaptor.capture());
        InstanceRegistry captured = instanceCaptor.getValue();
        assertEquals("inst-001", captured.getInstanceId());
        assertEquals("payment-service", captured.getServiceName());
        assertEquals(HealthStatus.HEALTHY, captured.getHealthStatus());
    }

    @Test
    void register_existingInstance_reRegisters() {
        InstanceRegistry existing = buildInstance("inst-001", "old-service", HealthStatus.UNHEALTHY);

        RegisterInstanceRequest request = new RegisterInstanceRequest(
                "inst-001", "payment-service", "10.0.0.1", 9090, "2.0.0"
        );

        when(instanceRegistryRepository.findById("inst-001")).thenReturn(Optional.of(existing));
        when(instanceRegistryRepository.save(any(InstanceRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InstanceStatusResponse response = instanceRegistryService.register(request);

        assertNotNull(response);
        assertEquals("inst-001", response.instanceId());
        assertEquals("payment-service", response.serviceName());
        assertEquals("10.0.0.1", response.hostAddress());
        assertEquals(9090, response.port());
        assertEquals("HEALTHY", response.healthStatus());
        assertEquals("2.0.0", response.sdkVersion());

        verify(instanceRegistryRepository).save(instanceCaptor.capture());
        InstanceRegistry captured = instanceCaptor.getValue();
        assertEquals(HealthStatus.HEALTHY, captured.getHealthStatus());
        assertEquals("payment-service", captured.getServiceName());
    }

    @Test
    void heartbeat_success() {
        InstanceRegistry instance = buildInstance("inst-001", "payment-service", HealthStatus.HEALTHY);
        Instant oldHeartbeat = Instant.now().minusSeconds(30);
        instance.setLastHeartbeat(oldHeartbeat);

        HeartbeatRequest request = new HeartbeatRequest("inst-001");

        when(instanceRegistryRepository.findById("inst-001")).thenReturn(Optional.of(instance));
        when(instanceRegistryRepository.save(any(InstanceRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InstanceStatusResponse response = instanceRegistryService.heartbeat(request);

        assertNotNull(response);
        assertEquals("inst-001", response.instanceId());
        assertEquals("HEALTHY", response.healthStatus());

        verify(instanceRegistryRepository).save(instanceCaptor.capture());
        InstanceRegistry captured = instanceCaptor.getValue();
        assertTrue(captured.getLastHeartbeat().isAfter(oldHeartbeat));
        assertEquals(HealthStatus.HEALTHY, captured.getHealthStatus());
    }

    @Test
    void heartbeat_unhealthyInstance_becomesHealthy() {
        InstanceRegistry instance = buildInstance("inst-001", "payment-service", HealthStatus.UNHEALTHY);

        HeartbeatRequest request = new HeartbeatRequest("inst-001");

        when(instanceRegistryRepository.findById("inst-001")).thenReturn(Optional.of(instance));
        when(instanceRegistryRepository.save(any(InstanceRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InstanceStatusResponse response = instanceRegistryService.heartbeat(request);

        assertEquals("HEALTHY", response.healthStatus());

        verify(instanceRegistryRepository).save(instanceCaptor.capture());
        assertEquals(HealthStatus.HEALTHY, instanceCaptor.getValue().getHealthStatus());
    }

    @Test
    void heartbeat_unknownInstance_throwsException() {
        HeartbeatRequest request = new HeartbeatRequest("unknown-inst");

        when(instanceRegistryRepository.findById("unknown-inst")).thenReturn(Optional.empty());

        assertThrows(InstanceNotFoundException.class, () -> instanceRegistryService.heartbeat(request));

        verify(instanceRegistryRepository, never()).save(any());
    }

    @Test
    void deregister_success() {
        when(instanceRegistryRepository.existsById("inst-001")).thenReturn(true);

        instanceRegistryService.deregister("inst-001");

        verify(instanceRegistryRepository).deleteById("inst-001");
    }

    @Test
    void deregister_unknownInstance_throwsException() {
        when(instanceRegistryRepository.existsById("unknown-inst")).thenReturn(false);

        assertThrows(InstanceNotFoundException.class, () -> instanceRegistryService.deregister("unknown-inst"));

        verify(instanceRegistryRepository, never()).deleteById(any());
    }

    @Test
    void getHealthyInstances_returnsOnlyHealthy() {
        InstanceRegistry healthy1 = buildInstance("inst-001", "svc-a", HealthStatus.HEALTHY);
        InstanceRegistry healthy2 = buildInstance("inst-002", "svc-b", HealthStatus.HEALTHY);

        when(instanceRegistryRepository.findByHealthStatus(HealthStatus.HEALTHY))
                .thenReturn(List.of(healthy1, healthy2));

        List<InstanceStatusResponse> responses = instanceRegistryService.getHealthyInstances();

        assertEquals(2, responses.size());
        assertEquals("inst-001", responses.get(0).instanceId());
        assertEquals("inst-002", responses.get(1).instanceId());
        responses.forEach(r -> assertEquals("HEALTHY", r.healthStatus()));
    }

    @Test
    void getAllInstances_returnsAll() {
        InstanceRegistry healthy = buildInstance("inst-001", "svc-a", HealthStatus.HEALTHY);
        InstanceRegistry unhealthy = buildInstance("inst-002", "svc-b", HealthStatus.UNHEALTHY);

        when(instanceRegistryRepository.findAll()).thenReturn(List.of(healthy, unhealthy));

        List<InstanceStatusResponse> responses = instanceRegistryService.getAllInstances();

        assertEquals(2, responses.size());
        assertEquals("HEALTHY", responses.get(0).healthStatus());
        assertEquals("UNHEALTHY", responses.get(1).healthStatus());
    }

    @Test
    void getInstance_found() {
        InstanceRegistry instance = buildInstance("inst-001", "payment-service", HealthStatus.HEALTHY);

        when(instanceRegistryRepository.findById("inst-001")).thenReturn(Optional.of(instance));

        InstanceStatusResponse response = instanceRegistryService.getInstance("inst-001");

        assertNotNull(response);
        assertEquals("inst-001", response.instanceId());
        assertEquals("payment-service", response.serviceName());
    }

    @Test
    void getInstance_notFound_throwsException() {
        when(instanceRegistryRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThrows(InstanceNotFoundException.class, () -> instanceRegistryService.getInstance("unknown"));
    }
}
