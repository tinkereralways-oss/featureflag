package com.paymentplatform.flagserver.service;

import com.paymentplatform.flagserver.dto.AuditLogResponse;
import com.paymentplatform.flagserver.entity.FlagAuditLog;
import com.paymentplatform.flagserver.entity.enums.FlagAction;
import com.paymentplatform.flagserver.repository.FlagAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlagAuditServiceTest {

    @Mock
    private FlagAuditLogRepository auditLogRepository;

    @InjectMocks
    private FlagAuditService auditService;

    @Captor
    private ArgumentCaptor<FlagAuditLog> auditLogCaptor;

    private FlagAuditLog buildAuditLog(Long id, String flagId, FlagAction action,
                                        String oldValue, String newValue, String changedBy, String metadata) {
        FlagAuditLog log = new FlagAuditLog();
        log.setId(id);
        log.setFlagId(flagId);
        log.setAction(action);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setChangedBy(changedBy);
        log.setMetadata(metadata);
        log.setCreatedAt(Instant.now());
        return log;
    }

    @Test
    void logAction_savesAuditLog() {
        when(auditLogRepository.save(any(FlagAuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        auditService.logAction("flag-123", FlagAction.CREATED, null, "my-flag-key", "admin-user");

        verify(auditLogRepository).save(auditLogCaptor.capture());
        FlagAuditLog captured = auditLogCaptor.getValue();

        assertEquals("flag-123", captured.getFlagId());
        assertEquals(FlagAction.CREATED, captured.getAction());
        assertNull(captured.getOldValue());
        assertEquals("my-flag-key", captured.getNewValue());
        assertEquals("admin-user", captured.getChangedBy());
        assertNull(captured.getMetadata());
    }

    @Test
    void logAction_withMetadata() {
        when(auditLogRepository.save(any(FlagAuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String metadata = "{\"reason\": \"rollback\", \"ticket\": \"JIRA-456\"}";
        auditService.logAction("flag-789", FlagAction.UPDATED, "old-name", "new-name", "deploy-bot", metadata);

        verify(auditLogRepository).save(auditLogCaptor.capture());
        FlagAuditLog captured = auditLogCaptor.getValue();

        assertEquals("flag-789", captured.getFlagId());
        assertEquals(FlagAction.UPDATED, captured.getAction());
        assertEquals("old-name", captured.getOldValue());
        assertEquals("new-name", captured.getNewValue());
        assertEquals("deploy-bot", captured.getChangedBy());
        assertEquals(metadata, captured.getMetadata());
    }

    @Test
    void getAuditLog_returnsPaginated() {
        FlagAuditLog log1 = buildAuditLog(1L, "flag-1", FlagAction.CREATED, null, "flag-key-1", "user1", null);
        FlagAuditLog log2 = buildAuditLog(2L, "flag-2", FlagAction.TOGGLED, "false", "true", "user2", null);

        Pageable pageable = PageRequest.of(0, 10);
        Page<FlagAuditLog> page = new PageImpl<>(List.of(log1, log2), pageable, 2);

        when(auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(page);

        Page<AuditLogResponse> result = auditService.getAuditLog(pageable);

        assertNotNull(result);
        assertEquals(2, result.getTotalElements());
        assertEquals(2, result.getContent().size());

        AuditLogResponse first = result.getContent().get(0);
        assertEquals(1L, first.id());
        assertEquals("flag-1", first.flagId());
        assertEquals("CREATED", first.action());
        assertNull(first.oldValue());
        assertEquals("flag-key-1", first.newValue());
        assertEquals("user1", first.changedBy());
        assertNull(first.metadata());

        AuditLogResponse second = result.getContent().get(1);
        assertEquals(2L, second.id());
        assertEquals("flag-2", second.flagId());
        assertEquals("TOGGLED", second.action());
        assertEquals("false", second.oldValue());
        assertEquals("true", second.newValue());
        assertEquals("user2", second.changedBy());

        verify(auditLogRepository).findAllByOrderByCreatedAtDesc(pageable);
    }

    @Test
    void getAuditLogForFlag_returnsLogs() {
        FlagAuditLog log1 = buildAuditLog(10L, "flag-abc", FlagAction.CREATED, null, "flag-abc-key", "creator", null);
        FlagAuditLog log2 = buildAuditLog(11L, "flag-abc", FlagAction.UPDATED, "old", "new", "updater", "{\"field\":\"name\"}");

        when(auditLogRepository.findByFlagIdOrderByCreatedAtDesc("flag-abc")).thenReturn(List.of(log1, log2));

        List<AuditLogResponse> result = auditService.getAuditLogForFlag("flag-abc");

        assertNotNull(result);
        assertEquals(2, result.size());

        assertEquals("flag-abc", result.get(0).flagId());
        assertEquals("CREATED", result.get(0).action());
        assertEquals("creator", result.get(0).changedBy());

        assertEquals("flag-abc", result.get(1).flagId());
        assertEquals("UPDATED", result.get(1).action());
        assertEquals("updater", result.get(1).changedBy());
        assertEquals("{\"field\":\"name\"}", result.get(1).metadata());

        verify(auditLogRepository).findByFlagIdOrderByCreatedAtDesc("flag-abc");
    }
}
