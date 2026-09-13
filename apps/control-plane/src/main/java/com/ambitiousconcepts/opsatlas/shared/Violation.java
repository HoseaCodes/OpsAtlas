package com.ambitiousconcepts.opsatlas.shared;

/**
 * One thing wrong with a submitted document, located precisely enough to fix.
 *
 * <p>CLAUDE.md section 8: validation errors are actionable and structured - a
 * JSON Pointer path, what was expected, what was found. "Invalid YAML" is not an
 * acceptable error message, and no code path in this application can produce one:
 * every field below except {@code found} is mandatory.
 *
 * @param pointer  RFC 6901 JSON Pointer into the submitted document, e.g. {@code /spec/environments/0/url}
 * @param keyword  the constraint that failed, e.g. {@code pattern}, {@code required}, {@code duplicate}
 * @param expected what would have been accepted, in plain language
 * @param found    what was actually there, or null when the value was absent
 * @param message  a complete sentence the author of the document can act on
 */
public record Violation(String pointer, String keyword, String expected, String found, String message) {

    public Violation {
        if (pointer == null || pointer.isBlank()) {
            throw new IllegalArgumentException("a violation without a pointer is not actionable");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("a violation without a message is not actionable");
        }
    }
}
