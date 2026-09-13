package com.ambitiousconcepts.opsatlas.integrations.internal;

import com.ambitiousconcepts.opsatlas.integrations.api.SourceView;

final class SourceMapper {

    private SourceMapper() {}

    static SourceView toView(SourceEntity entity) {
        return new SourceView(
                entity.getId(),
                entity.getProvider(),
                entity.getRepository(),
                entity.getGitRef(),
                entity.getPath(),
                entity.isEnabled(),
                entity.getLastAttemptAt(),
                entity.getLastSuccessAt(),
                entity.getLastOutcome(),
                entity.getLastDetail(),
                entity.getConsecutiveFailures(),
                entity.getServiceId(),
                entity.getCreatedAt(),
                entity.getVersion());
    }
}
