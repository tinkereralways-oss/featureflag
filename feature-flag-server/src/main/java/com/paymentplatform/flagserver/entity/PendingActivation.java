package com.paymentplatform.flagserver.entity;

import com.paymentplatform.flagserver.entity.enums.ActivationStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pending_activations")
public class PendingActivation {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String flagId;

    @Column(nullable = false)
    private String environment;

    @Column(columnDefinition = "NUMBER(1)")
    private boolean newEnabled;

    @Enumerated(EnumType.STRING)
    private ActivationStatus status = ActivationStatus.PENDING;

    private Integer totalInstances;

    private Integer ackedInstances = 0;

    private Integer timeoutSeconds = 60;

    private Instant createdAt;

    private Instant completedAt;

    @OneToMany(mappedBy = "activation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PendingActivationAck> acks = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFlagId() {
        return flagId;
    }

    public void setFlagId(String flagId) {
        this.flagId = flagId;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public boolean isNewEnabled() {
        return newEnabled;
    }

    public void setNewEnabled(boolean newEnabled) {
        this.newEnabled = newEnabled;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public void setStatus(ActivationStatus status) {
        this.status = status;
    }

    public Integer getTotalInstances() {
        return totalInstances;
    }

    public void setTotalInstances(Integer totalInstances) {
        this.totalInstances = totalInstances;
    }

    public Integer getAckedInstances() {
        return ackedInstances;
    }

    public void setAckedInstances(Integer ackedInstances) {
        this.ackedInstances = ackedInstances;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public List<PendingActivationAck> getAcks() {
        return acks;
    }

    public void setAcks(List<PendingActivationAck> acks) {
        this.acks = acks;
    }
}
