package com.paymentplatform.flagserver.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "flag_environments", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"flag_id", "environment"})
})
public class FlagEnvironment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "flag_env_seq_gen")
    @SequenceGenerator(name = "flag_env_seq_gen", sequenceName = "flag_env_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flag_id", nullable = false)
    private FeatureFlag flag;

    @Column(nullable = false, length = 50)
    private String environment;

    @Column(columnDefinition = "NUMBER(1)")
    private boolean enabled = false;

    private Integer rolloutPercentage = 100;

    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FeatureFlag getFlag() {
        return flag;
    }

    public void setFlag(FeatureFlag flag) {
        this.flag = flag;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getRolloutPercentage() {
        return rolloutPercentage;
    }

    public void setRolloutPercentage(Integer rolloutPercentage) {
        this.rolloutPercentage = rolloutPercentage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
