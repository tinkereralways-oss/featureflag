package com.paymentplatform.flagserver.repository;

import com.paymentplatform.flagserver.entity.PendingActivationAck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PendingActivationAckRepository extends JpaRepository<PendingActivationAck, Long> {

    List<PendingActivationAck> findByActivationId(String activationId);

    boolean existsByActivationIdAndInstanceId(String activationId, String instanceId);
}
