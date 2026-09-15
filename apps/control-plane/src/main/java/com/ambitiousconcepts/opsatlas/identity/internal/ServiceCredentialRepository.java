package com.ambitiousconcepts.opsatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface ServiceCredentialRepository extends Repository<ServiceCredentialEntity, UUID> {

    Optional<ServiceCredentialEntity> findBySecretHash(String secretHash);

    Optional<ServiceCredentialEntity> findByOrgIdAndName(UUID orgId, String name);

    ServiceCredentialEntity save(ServiceCredentialEntity credential);
}
