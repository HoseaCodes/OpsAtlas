package com.ambitiousconcepts.opsatlas.catalog.internal.domain;

import java.util.UUID;

/**
 * Everything a manifest determines about a service row.
 *
 * <p>A parameter object rather than a twelve-argument constructor, so that a
 * caller cannot silently transpose {@code repository} and {@code sourcePath} -
 * both strings, both plausible in either position, and a mix-up that would
 * corrupt the uniqueness constraint rather than fail.
 *
 * <p>Deliberately excludes {@code orgId}, {@code id} and {@code createdAt}:
 * those are not the manifest's to state.
 *
 * @param teamId null when the manifest declares no owner, which is permitted
 * @param manifest the normalized document as JSON
 * @param manifestDigest SHA-256 of the raw submitted bytes
 */
public record ServiceFields(
        UUID teamId,
        String slug,
        String displayName,
        String repository,
        short tier,
        String runtime,
        String lifecycle,
        String schemaVersion,
        String manifest,
        String manifestDigest,
        String sourcePath,
        String sourceRef) {}
