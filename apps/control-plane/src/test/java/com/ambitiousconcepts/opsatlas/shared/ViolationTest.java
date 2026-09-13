package com.ambitiousconcepts.opsatlas.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ViolationTest {

    @Test
    void a_violation_without_a_pointer_cannot_be_constructed() {
        // CLAUDE.md section 8: "Invalid YAML" is not an acceptable error
        // message. This makes the unacceptable version unrepresentable rather
        // than merely discouraged.
        assertThatThrownBy(() -> new Violation("", "pattern", "a slug", "Bad_Name", "message"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not actionable");
    }

    @Test
    void a_violation_without_a_message_cannot_be_constructed() {
        assertThatThrownBy(() -> new Violation("/metadata/name", "pattern", "a slug", "Bad_Name", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void found_may_be_absent_because_the_value_may_be_absent() {
        Violation violation =
                new Violation("/spec/operations/runbook", "required", "a runbook URL or path", null, "No runbook.");
        assertThat(violation.found()).isNull();
    }

    @Test
    void a_validation_failure_with_no_violations_cannot_be_constructed() {
        assertThatThrownBy(() -> new ValidationFailedException("invalid", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tells the caller nothing");
    }
}
