package com.ambitiousconcepts.opsatlas.integrations.internal;

import com.ambitiousconcepts.opsatlas.integrations.api.FetchResult;
import com.ambitiousconcepts.opsatlas.integrations.api.ManifestSourceReader;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceRef;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads {@code service.yaml} from a GitHub repository.
 *
 * <p>Three properties are worth stating because they are the reason ADR 0008 is
 * defensible:
 *
 * <ul>
 *   <li><strong>One host, always.</strong> The base URL is configuration, not
 *       input. A {@link SourceRef} contributes only an {@code owner/name}, a ref
 *       and a path, each validated against a pattern at construction. No caller
 *       can steer this at another address, which is what keeps a repository name
 *       from becoming a request-forgery surface.
 *   <li><strong>No write method exists.</strong> Not disabled - absent.
 *   <li><strong>Bounded.</strong> Connect and read timeouts, and a response size
 *       cap applied before the body is turned into a String, so a hostile or
 *       broken response cannot exhaust memory on a scheduled job.
 * </ul>
 *
 * <p>Authentication is optional. Unauthenticated works for public repositories
 * at 60 requests per hour per IP; a read-scoped token raises that to 5,000. The
 * token is never logged, and no scope beyond reading contents is needed or
 * requested.
 */
@Component
class GitHubManifestReader implements ManifestSourceReader {

    private static final Logger log = LoggerFactory.getLogger(GitHubManifestReader.class);

    /**
     * The same cap the ingestion pipeline applies, enforced again here so an
     * oversized response is dropped at the socket rather than carried into
     * memory and rejected afterwards.
     */
    static final int MAX_BYTES = 64 * 1024;

    /** Asks the Contents API for the file itself rather than a JSON envelope. */
    private static final MediaType RAW = MediaType.parseMediaType("application/vnd.github.raw+json");

    private final RestClient http;
    private final String baseUrl;
    private final String token;

    GitHubManifestReader(
            RestClient.Builder builder,
            @Value("${opsatlas.github.base-url:https://api.github.com}") String baseUrl,
            @Value("${opsatlas.github.token:}") String token) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String provider() {
        return "github";
    }

    @Override
    public FetchResult fetch(SourceRef source, Optional<String> etag) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("repos", source.owner(), source.name(), "contents")
                // The path may contain slashes, and each segment is encoded
                // separately so a legitimate nested path works and nothing else
                // does.
                .pathSegment(source.path().split("/"))
                .queryParam("ref", source.ref())
                .build()
                .toUri();

        try {
            return http.get()
                    .uri(uri)
                    .accept(RAW)
                    .headers(headers -> {
                        headers.set(HttpHeaders.USER_AGENT, "OpsAtlas");
                        headers.set("X-GitHub-Api-Version", "2022-11-28");
                        if (!token.isBlank()) {
                            headers.setBearerAuth(token);
                        }
                        // ADR 0008: a 304 costs no transfer and no ingestion,
                        // which is what makes polling affordable at all.
                        etag.filter(value -> !value.isBlank())
                                .ifPresent(value -> headers.set(HttpHeaders.IF_NONE_MATCH, value));
                    })
                    .exchange((request, response) -> interpret(source, response));
        } catch (ResourceAccessException e) {
            // DNS failure, connection refused, timeout. The source is recorded as
            // unreachable and whatever was already registered stays untouched.
            log.warn("Could not reach GitHub for {}: {}", source.repository(), e.getMessage());
            return new FetchResult.Unreachable("Could not reach " + baseUrl + ": " + rootCause(e));
        } catch (RuntimeException e) {
            log.warn("Unexpected failure fetching {}: {}", source.repository(), e.toString());
            return new FetchResult.Unreachable("The request to GitHub failed: " + rootCause(e));
        }
    }

    private FetchResult interpret(SourceRef source, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws java.io.IOException {
        HttpStatusCode status = response.getStatusCode();

        if (status.value() == 304) {
            return new FetchResult.NotModified();
        }

        if (status.is2xxSuccessful()) {
            byte[] body = readBounded(response.getBody());
            if (body == null) {
                return new FetchResult.Unreachable("The manifest at " + source.repository() + "/" + source.path()
                        + " is larger than " + MAX_BYTES + " bytes. A service.yaml describes a service;"
                        + " it is not a place to store data.");
            }
            return new FetchResult.Content(
                    new String(body, StandardCharsets.UTF_8),
                    Optional.ofNullable(response.getHeaders().getETag()));
        }

        if (status.value() == 404) {
            return new FetchResult.NotFound("No file at " + source.path() + " on ref " + source.ref() + " in "
                    + source.repository() + ". Either the repository is private and this token cannot see it,"
                    + " the ref does not exist, or the manifest has not been committed yet.");
        }

        if (status.value() == 401) {
            return new FetchResult.Unauthorized("GitHub rejected the credentials."
                    + (token.isBlank()
                            ? " No token is configured; set OPSATLAS_GITHUB_TOKEN to read private repositories."
                            : " Check that OPSATLAS_GITHUB_TOKEN is valid and has not expired."));
        }

        if (status.value() == 403 || status.value() == 429) {
            // GitHub uses 403 for both "forbidden" and "rate limited"; the
            // remaining-quota header is what separates them, and the two need
            // different responses from a reader of this catalog.
            String remaining = response.getHeaders().getFirst("x-ratelimit-remaining");
            if ("0".equals(remaining) || status.value() == 429) {
                return new FetchResult.RateLimited(rateLimitDetail(response.getHeaders().getFirst("x-ratelimit-reset")));
            }
            return new FetchResult.Unauthorized("GitHub refused access to " + source.repository()
                    + ". The token exists but does not have permission to read this repository's contents.");
        }

        return new FetchResult.Unreachable("GitHub answered " + status.value() + " for " + source.repository()
                + "/" + source.path() + ", which this reader does not know how to interpret.");
    }

    private String rateLimitDetail(String resetEpochSeconds) {
        String when = "shortly";
        if (resetEpochSeconds != null) {
            try {
                when = "at " + java.time.Instant.ofEpochSecond(Long.parseLong(resetEpochSeconds));
            } catch (NumberFormatException ignored) {
                // The header was not a number. The message is still useful.
            }
        }
        return "GitHub's rate limit is exhausted; it resets " + when + "."
                + (token.isBlank()
                        ? " Unauthenticated requests are limited to 60 per hour per IP."
                                + " Setting OPSATLAS_GITHUB_TOKEN raises that to 5,000."
                        : " The configured token's hourly quota is spent.");
    }

    /**
     * Reads at most {@link #MAX_BYTES}, returning null if the body is larger.
     *
     * <p>Reading the stream into a String first and checking the length
     * afterwards would mean a broken or hostile response had already been
     * allocated. This is a scheduled job that runs unattended, so the bound goes
     * before the allocation rather than after it.
     */
    private static byte[] readBounded(java.io.InputStream body) throws java.io.IOException {
        byte[] buffer = body.readNBytes(MAX_BYTES + 1);
        return buffer.length > MAX_BYTES ? null : buffer;
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
