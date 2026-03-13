package com.paymentplatform.flagserver.controller;

import com.paymentplatform.flagserver.dto.AuditLogResponse;
import com.paymentplatform.flagserver.service.FlagAuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final FlagAuditService auditService;

    public AuditController(FlagAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public Page<AuditLogResponse> getAuditLog(Pageable pageable) {
        return auditService.getAuditLog(pageable);
    }

    @GetMapping("/flag/{flagId}")
    public List<AuditLogResponse> getAuditLogForFlag(@PathVariable String flagId) {
        return auditService.getAuditLogForFlag(flagId);
    }
}
