package com.paymentplatform.flagserver.repository;

import com.paymentplatform.flagserver.entity.InstanceRegistry;
import com.paymentplatform.flagserver.entity.enums.HealthStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface InstanceRegistryRepository extends JpaRepository<InstanceRegistry, String> {

    List<InstanceRegistry> findByHealthStatus(HealthStatus status);

    List<InstanceRegistry> findByServiceName(String serviceName);

    List<InstanceRegistry> findByLastHeartbeatBeforeAndHealthStatus(Instant threshold, HealthStatus status);
}
