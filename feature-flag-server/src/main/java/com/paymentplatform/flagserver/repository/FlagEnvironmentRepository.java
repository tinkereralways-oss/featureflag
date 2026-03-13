package com.paymentplatform.flagserver.repository;

import com.paymentplatform.flagserver.entity.FlagEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FlagEnvironmentRepository extends JpaRepository<FlagEnvironment, Long> {

    List<FlagEnvironment> findByFlagId(String flagId);

    Optional<FlagEnvironment> findByFlagIdAndEnvironment(String flagId, String environment);
}
