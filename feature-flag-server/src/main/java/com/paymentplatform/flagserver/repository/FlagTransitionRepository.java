package com.paymentplatform.flagserver.repository;

import com.paymentplatform.flagserver.entity.FlagTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlagTransitionRepository extends JpaRepository<FlagTransition, Long> {

    List<FlagTransition> findByFlagIdOrderByCreatedAtDesc(String flagId);
}
