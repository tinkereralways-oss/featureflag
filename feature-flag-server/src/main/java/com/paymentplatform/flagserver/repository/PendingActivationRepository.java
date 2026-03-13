package com.paymentplatform.flagserver.repository;

import com.paymentplatform.flagserver.entity.PendingActivation;
import com.paymentplatform.flagserver.entity.enums.ActivationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PendingActivationRepository extends JpaRepository<PendingActivation, String> {

    List<PendingActivation> findByStatus(ActivationStatus status);

    Optional<PendingActivation> findByFlagIdAndEnvironmentAndStatus(String flagId, String environment, ActivationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pa FROM PendingActivation pa WHERE pa.id = :id")
    Optional<PendingActivation> findByIdForUpdate(@Param("id") String id);

    @Query("SELECT pa FROM PendingActivation pa WHERE pa.status IN :statuses")
    List<PendingActivation> findByStatusIn(@Param("statuses") List<ActivationStatus> statuses);
}
