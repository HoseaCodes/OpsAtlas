package com.ambitiousconcepts.opsatlas.shared;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds the one error shape this API has (CLAUDE.md section 9).
 *
 * <p>Extracted from {@code ApiExceptionHandler} when authentication arrived,
 * because the security layer answers before any controller does and has to
 * answer in the same shape. Spring Security's defaults return 401 and 403 with
 * an empty body, which would have left two error formats: a problem document
 * everywhere a controller is reached, and nothing at the door.
 *
 * <p>Two builders rather than one shared one would drift, and the drift would be
 * invisible until somebody hit an endpoint while logged out.
 */
public final class Problems {

    public static final String TYPE_BASE = "https://opsatlas.ambitiousconcepts.io/problems/";

    private Problems() {}

    public static ProblemDetail of(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        problem.setDetail(detail);
        // The same string the caller got in X-Correlation-Id, which is also the
        // trace id when the request was traced (ADR 0011).
        problem.setProperty("correlationId", CorrelationIdFilter.current());
        return problem;
    }
}
