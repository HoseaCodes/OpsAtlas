package com.ambitiousconcepts.opsatlas.governance.api;

/**
 * Whether a rule applies to a service at all.
 *
 * <p>A tier 3 internal tool is not <em>failing</em> for having no SLO - the rule
 * does not apply to it. Without this distinction the score denominator is a
 * constant, every tier 3 service looks permanently broken, and the fleet
 * compliance number is meaningless. ADR 0004.
 */
public enum Applicability {
    REQUIRED,
    NOT_APPLICABLE
}
