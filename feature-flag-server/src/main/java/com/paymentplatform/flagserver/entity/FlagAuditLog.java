package com.paymentplatform.flagserver.entity;

import com.paymentplatform.flagserver.entity.enums.FlagAction;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "flag_audit_log")
public class FlagAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_log_seq_gen")
    @SequenceGenerator(name = "audit_log_seq_gen", sequenceName = "audit_log_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String flagId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlagAction action;

    @Column(length = 4000)
    private String oldValue;

    @Column(length = 4000)
    private String newValue;

    private String changedBy;

    @Lob
    private String metadata;

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFlagId() {
        return flagId;
    }

    public void setFlagId(String flagId) {
        this.flagId = flagId;
    }

    public FlagAction getAction() {
        return action;
    }

    public void setAction(FlagAction action) {
        this.action = action;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
