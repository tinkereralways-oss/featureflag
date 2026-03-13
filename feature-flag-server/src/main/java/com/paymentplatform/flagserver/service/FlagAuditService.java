package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.AuditLogResponse;
import com.paymentplatform.flagserver.entity.FlagAuditLog;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import com.paymentplatform.flagserver.repository.FlagAuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FlagAuditService {

    private final FlagAuditLogRepository auditLogRepository;

    public FlagAuditService(FlagAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(String flagId, FlagAction action, String oldValue, String newValue, String changedBy) {
        logAction(flagId, action, oldValue, newValue, changedBy, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(String flagId, FlagAction action, String oldValue, String newValue, String changedBy, String metadata) {
        FlagAuditLog log = new FlagAuditLog();
        log.setFlagId(flagId);
        log.setAction(action);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setChangedBy(changedBy);
        log.setMetadata(metadata);
        auditLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLog(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAuditLogForFlag(String flagId) {
        return auditLogRepository.findByFlagIdOrderByCreatedAtDesc(flagId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditLogResponse toResponse(FlagAuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getFlagId(),
                log.getAction().name(),
                log.getOldValue(),
                log.getNewValue(),
                log.getChangedBy(),
                log.getMetadata(),
                log.getCreatedAt()
        );
    }
}
