package com.paymentplatform.flagserver.entity;

import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true)
    private String flagKey;

    @Column(nullable = false)
    private String name;

    @Column(length = 4000)
    private String description;

    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LifecycleState lifecycleState = LifecycleState.CREATED;

    private Integer staleAfterDays = 90;

    private Instant createdAt;

    private Instant updatedAt;

    @OneToMany(mappedBy = "flag", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FlagEnvironment> environments = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFlagKey() {
        return flagKey;
    }

    public void setFlagKey(String flagKey) {
        this.flagKey = flagKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public LifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(LifecycleState lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public Integer getStaleAfterDays() {
        return staleAfterDays;
    }

    public void setStaleAfterDays(Integer staleAfterDays) {
        this.staleAfterDays = staleAfterDays;
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

    public List<FlagEnvironment> getEnvironments() {
        return environments;
    }

    public void setEnvironments(List<FlagEnvironment> environments) {
        this.environments = environments;
    }
}
