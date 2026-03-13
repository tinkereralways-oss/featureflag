package com.paymentplatform.flagserver.repository;

import com.paymentplatform.flagserver.entity.FeatureFlag;
import com.paymentplatform.flagserver.entity.enums.LifecycleState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, String> {

    Optional<FeatureFlag> findByFlagKey(String flagKey);

    List<FeatureFlag> findByLifecycleState(LifecycleState state);

    boolean existsByFlagKey(String flagKey);
}
