package com.ambitiousconcepts.opsatlas.operations.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Daily rollups. Every org-scoped method takes orgId first; see ADR 0003. */
interface EnvironmentDayRepository extends JpaRepository<EnvironmentDayEntity, EnvironmentDayEntity.Key> {

    @Query("""
            select d from EnvironmentDayEntity d
            where d.orgId = :orgId and d.environmentId = :environmentId and d.day >= :from
            order by d.day asc
            """)
    List<EnvironmentDayEntity> findSince(
            @Param("orgId") UUID orgId, @Param("environmentId") UUID environmentId, @Param("from") LocalDate from);

    Optional<EnvironmentDayEntity> findByEnvironmentIdAndDay(UUID environmentId, LocalDate day);

    /**
     * Retention. A table that only grows is a time series wearing a different
     * hat (ADR 0009), so the pruning is real rather than assumed.
     */
    @Modifying
    @Query("delete from EnvironmentDayEntity d where d.day < :before")
    int deleteOlderThan(@Param("before") LocalDate before);
}
