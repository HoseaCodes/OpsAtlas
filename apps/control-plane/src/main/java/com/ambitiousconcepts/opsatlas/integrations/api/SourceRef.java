package com.ambitiousconcepts.opsatlas.integrations.api;

import java.util.regex.Pattern;

/**
 * Where a manifest lives.
 *
 * <p>Validated at construction rather than at the point of use, because this
 * value is interpolated into a URL. Every field is checked against a pattern
 * that admits only what GitHub itself permits, so there is no way to build a
 * {@code SourceRef} that could reach a host or a path the caller did not intend.
 * The same shapes are refused by CHECK constraints on the {@code source} table.
 *
 * @param repository {@code owner/name}
 * @param ref        a branch, tag or commit; {@code HEAD} for the default branch
 * @param path       the manifest's path within the repository
 */
public record SourceRef(String repository, String ref, String path) {

    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._/-]{1,255}$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final Pattern TRAVERSAL = Pattern.compile("(^|/)\\.\\.(/|$)");

    public SourceRef {
        require(REPOSITORY.matcher(repository).matches(), "repository must be owner/name", repository);
        require(REF.matcher(ref).matches(), "ref contains characters a git ref cannot", ref);
        require(PATH.matcher(path).matches(), "path must be a relative path inside the repository", path);
        // Belt and braces. The patterns above already exclude a bare "..", but a
        // traversal segment is the one thing worth refusing twice.
        require(!TRAVERSAL.matcher(path).find(), "path must not contain a '..' segment", path);
        require(!TRAVERSAL.matcher(ref).find(), "ref must not contain a '..' segment", ref);
    }

    public static SourceRef of(String repository, String ref, String path) {
        return new SourceRef(repository, ref == null || ref.isBlank() ? "HEAD" : ref, path);
    }

    public String owner() {
        return repository.substring(0, repository.indexOf('/'));
    }

    public String name() {
        return repository.substring(repository.indexOf('/') + 1);
    }

    private static void require(boolean condition, String message, String value) {
        if (!condition) {
            throw new IllegalArgumentException(message + " (was: '" + value + "')");
        }
    }
}
