package com.ambitiousconcepts.opsatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface PrincipalRepository extends Repository<PrincipalEntity, UUID> {

    /**
     * Identity is the pair, never the subject alone: the same opaque id from a
     * different issuer is a different person, and treating them as one would
     * hand one organization's catalog to another.
     */
    Optional<PrincipalEntity> findByIssuerAndSubject(String issuer, String subject);
}
