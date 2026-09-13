package com.ambitiousconcepts.opsatlas.catalog.api;

/**
 * The result of a registration attempt.
 *
 * <p>{@code created} distinguishes a new service from a replay of an unchanged
 * manifest, so the controller can answer 201 or 200 without re-deriving it. A
 * retried POST is not an error and must not look like one, but it is also not a
 * creation and must not claim to be.
 */
public record RegistrationOutcome(ServiceDetail service, boolean created) {}
