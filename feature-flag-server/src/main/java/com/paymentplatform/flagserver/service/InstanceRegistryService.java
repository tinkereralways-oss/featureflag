package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.HeartbeatRequest;
import com.paymentplatform.flagserver.dto.InstanceStatusResponse;
import com.paymentplatform.flagserver.dto.RegisterInstanceRequest;
import com.paymentplatform.flagserver.entity.InstanceRegistry;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import com.paymentplatform.flagserver.exception.InstanceNotFoundException;
import com.paymentplatform.flagserver.repository.InstanceRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class InstanceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistryService.class);

    private final InstanceRegistryRepository instanceRegistryRepository;

    public InstanceRegistryService(InstanceRegistryRepository instanceRegistryRepository) {
        this.instanceRegistryRepository = instanceRegistryRepository;
    }

    public InstanceStatusResponse register(RegisterInstanceRequest request) {
        InstanceRegistry instance = instanceRegistryRepository.findById(request.instanceId())
                .orElseGet(() -> {
                    log.info("Registering new instance: {} for service: {}", request.instanceId(), request.serviceName());
                    InstanceRegistry newInstance = new InstanceRegistry();
                    newInstance.setInstanceId(request.instanceId());
                    return newInstance;
                });

        if (instance.getRegisteredAt() != null) {
            log.info("Re-registering existing instance: {}", request.instanceId());
        }

        instance.setServiceName(request.serviceName());
        instance.setHostAddress(request.hostAddress());
        instance.setPort(request.port());
        instance.setSdkVersion(request.sdkVersion());
        instance.setHealthStatus(HealthStatus.HEALTHY);
        instance.setLastHeartbeat(Instant.now());

        instance = instanceRegistryRepository.save(instance);
        return toResponse(instance);
    }

    public InstanceStatusResponse heartbeat(HeartbeatRequest request) {
        InstanceRegistry instance = instanceRegistryRepository.findById(request.instanceId())
                .orElseThrow(() -> new InstanceNotFoundException(request.instanceId()));

        instance.setLastHeartbeat(Instant.now());
        instance.setHealthStatus(HealthStatus.HEALTHY);
        instance = instanceRegistryRepository.save(instance);

        log.debug("Heartbeat received from instance: {}", request.instanceId());
        return toResponse(instance);
    }

    public void deregister(String instanceId) {
        if (!instanceRegistryRepository.existsById(instanceId)) {
            throw new InstanceNotFoundException(instanceId);
        }
        log.info("Deregistering instance: {}", instanceId);
        instanceRegistryRepository.deleteById(instanceId);
    }

    @Transactional(readOnly = true)
    public List<InstanceStatusResponse> getHealthyInstances() {
        return instanceRegistryRepository.findByHealthStatus(HealthStatus.HEALTHY)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstanceStatusResponse> getAllInstances() {
        return instanceRegistryRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public InstanceStatusResponse getInstance(String instanceId) {
        InstanceRegistry instance = instanceRegistryRepository.findById(instanceId)
                .orElseThrow(() -> new InstanceNotFoundException(instanceId));
        return toResponse(instance);
    }

    private InstanceStatusResponse toResponse(InstanceRegistry instance) {
        return new InstanceStatusResponse(
                instance.getInstanceId(),
                instance.getServiceName(),
                instance.getHostAddress(),
                instance.getPort(),
                instance.getHealthStatus().name(),
                instance.getSdkVersion(),
                instance.getLastHeartbeat(),
                instance.getRegisteredAt()
        );
    }
}
