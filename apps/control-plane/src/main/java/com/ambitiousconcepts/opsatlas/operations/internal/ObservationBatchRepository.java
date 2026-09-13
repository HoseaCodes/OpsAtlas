package com.ambitiousconcepts.opsatlas.operations.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Applied batches, for idempotency. */
interface ObservationBatchRepository extends JpaRepository<ObservationBatchEntity, ObservationBatchEntity.Key> {

    Optional<ObservationBatchEntity> findByOrgIdAndIdempotencyKey(UUID orgId, String idempotencyKey);

    /** An idempotency key only has to outlive the retries of its own request. */
    @Modifying
    @Query("delete from ObservationBatchEntity b where b.receivedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
