package com.paymentplatform.flagserver.repository;

import com.paymentplatform.flagserver.entity.FlagAuditLog;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlagAuditLogRepository extends JpaRepository<FlagAuditLog, Long> {

    List<FlagAuditLog> findByFlagIdOrderByCreatedAtDesc(String flagId);

    Page<FlagAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByFlagIdAndAction(String flagId, FlagAction action);
}
