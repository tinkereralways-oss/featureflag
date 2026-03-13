package com.paymentplatform.flagserver.entity;

import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "flag_transitions")
public class FlagTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "flag_transition_seq_gen")
    @SequenceGenerator(name = "flag_transition_seq_gen", sequenceName = "flag_transition_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String flagId;

    @Enumerated(EnumType.STRING)
    private LifecycleState fromState;

    @Enumerated(EnumType.STRING)
    private LifecycleState toState;

    @Column(length = 4000)
    private String reason;

    private String transitionedBy;

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

    public LifecycleState getFromState() {
        return fromState;
    }

    public void setFromState(LifecycleState fromState) {
        this.fromState = fromState;
    }

    public LifecycleState getToState() {
        return toState;
    }

    public void setToState(LifecycleState toState) {
        this.toState = toState;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTransitionedBy() {
        return transitionedBy;
    }

    public void setTransitionedBy(String transitionedBy) {
        this.transitionedBy = transitionedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
