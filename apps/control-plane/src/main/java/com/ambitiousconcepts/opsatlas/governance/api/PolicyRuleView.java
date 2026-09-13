package com.ambitiousconcepts.opsatlas.governance.api;

/**
 * A rule as the console needs to describe it.
 *
 * @param declarationOnly whether this rule reads only what a manifest declares.
 *     Every rule in slice one is declaration-only, and the console says so rather
 *     than letting a reader assume the platform verified anything at runtime.
 */
public record PolicyRuleView(String id, String title, String rationale, boolean declarationOnly) {}
