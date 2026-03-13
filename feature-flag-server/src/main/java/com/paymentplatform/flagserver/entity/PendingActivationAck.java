package com.paymentplatform.flagserver.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "pending_activation_acks", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"activation_id", "instance_id"})
})
public class PendingActivationAck {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "activation_ack_seq_gen")
    @SequenceGenerator(name = "activation_ack_seq_gen", sequenceName = "activation_ack_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activation_id", nullable = false)
    private PendingActivation activation;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    private Instant ackedAt;

    @PrePersist
    protected void onCreate() {
        ackedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PendingActivation getActivation() {
        return activation;
    }

    public void setActivation(PendingActivation activation) {
        this.activation = activation;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Instant getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(Instant ackedAt) {
        this.ackedAt = ackedAt;
    }
}
