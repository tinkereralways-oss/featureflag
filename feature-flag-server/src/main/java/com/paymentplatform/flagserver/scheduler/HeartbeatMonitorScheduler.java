package com.paymentplatform.flagserver.scheduler;

import com.paymentplatform.flagserver.entity.InstanceRegistry;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import com.paymentplatform.flagserver.repository.InstanceRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class HeartbeatMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitorScheduler.class);

    private final InstanceRegistryRepository instanceRegistryRepository;
    private final long unhealthyThresholdSeconds;

    public HeartbeatMonitorScheduler(
            InstanceRegistryRepository instanceRegistryRepository,
            @Value("${feature-flag.unhealthy-threshold-seconds:45}") long unhealthyThresholdSeconds) {
        this.instanceRegistryRepository = instanceRegistryRepository;
        this.unhealthyThresholdSeconds = unhealthyThresholdSeconds;
    }

    @Scheduled(fixedDelayString = "${feature-flag.heartbeat-interval-seconds:15}000")
    @Transactional
    public void checkHeartbeats() {
        Instant threshold = Instant.now().minusSeconds(unhealthyThresholdSeconds);

        List<InstanceRegistry> staleInstances = instanceRegistryRepository
                .findByLastHeartbeatBeforeAndHealthStatus(threshold, HealthStatus.HEALTHY);

        for (InstanceRegistry instance : staleInstances) {
            log.warn("Marking instance {} ({}) as UNHEALTHY — last heartbeat: {}",
                    instance.getInstanceId(), instance.getServiceName(), instance.getLastHeartbeat());
            instance.setHealthStatus(HealthStatus.UNHEALTHY);
        }

        if (!staleInstances.isEmpty()) {
            instanceRegistryRepository.saveAll(staleInstances);
            log.info("Marked {} instance(s) as UNHEALTHY", staleInstances.size());
        }
    }
}
