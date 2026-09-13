package com.ambitiousconcepts.opsatlas.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ambitiousconcepts.opsatlas.integrations.api.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The validation that keeps a repository name from becoming a request-forgery
 * surface.
 *
 * <p>A {@link SourceRef} is interpolated into a URL, so it validates at
 * construction rather than at the point of use: there is no way to hold an
 * unvalidated one. These tests are the reason the reader can build a URL without
 * re-checking anything.
 */
class SourceRefTest {

    @Test
    @DisplayName("a normal repository, ref and path are accepted and split correctly")
    void accepts_a_normal_source() {
        SourceRef ref = SourceRef.of("ambitious-concepts/orders-api", "main", "deploy/service.yaml");

        assertThat(ref.owner()).isEqualTo("ambitious-concepts");
        assertThat(ref.name()).isEqualTo("orders-api");
        assertThat(ref.path()).isEqualTo("deploy/service.yaml");
    }

    @Test
    @DisplayName("a blank ref means the default branch rather than an error")
    void blank_ref_defaults_to_head() {
        assertThat(SourceRef.of("acme/demo", null, "service.yaml").ref()).isEqualTo("HEAD");
        assertThat(SourceRef.of("acme/demo", "  ", "service.yaml").ref()).isEqualTo("HEAD");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-a-repository",
                "too/many/slashes",
                "owner/",
                "/name",
                "owner name/repo",
                "owner/repo?query",
                "owner/repo#fragment",
                "https://evil.example.com/a/b",
            })
    @DisplayName("a repository that is not owner/name is refused")
    void refuses_malformed_repositories(String repository) {
        assertThatThrownBy(() -> SourceRef.of(repository, "main", "service.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/name");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../../../etc/passwd",
                "a/../../b",
                "/etc/passwd",
                "service.yaml?ref=x",
                "service yaml",
                "a//b",
                "..",
            })
    @DisplayName("a path that escapes the repository, or is not a path, is refused")
    void refuses_traversal_and_junk_paths(String path) {
        // The reader builds a URL from this. A '..' segment, an absolute path or
        // an embedded query are each a different way of asking for something
        // other than the file that was meant.
        assertThatThrownBy(() -> SourceRef.of("acme/demo", "main", path))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../other", "refs/../../x", "main\nHost: evil"})
    @DisplayName("a ref that escapes, or carries a newline, is refused")
    void refuses_dangerous_refs(String ref) {
        // A newline in a value that reaches a request line is header injection.
        assertThatThrownBy(() -> SourceRef.of("acme/demo", ref, "service.yaml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nested paths are allowed, because manifests do live in subdirectories")
    void allows_legitimate_nesting() {
        assertThat(SourceRef.of("acme/demo", "main", "deploy/k8s/service.yaml").path())
                .isEqualTo("deploy/k8s/service.yaml");
    }
}
