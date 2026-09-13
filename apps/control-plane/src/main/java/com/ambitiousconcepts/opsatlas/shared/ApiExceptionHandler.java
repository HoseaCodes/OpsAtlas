package com.ambitiousconcepts.opsatlas.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One error shape for every endpoint, everywhere.
 *
 * <p>CLAUDE.md section 9 requires {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code correlationId} and a {@code violations[]} array for
 * validation failures. The first four are RFC 9457 fields that Spring's
 * {@link ProblemDetail} already models; the last two are added as extensions, so
 * the response is a standard problem document rather than a bespoke envelope.
 *
 * <p>Two rules hold across every handler here:
 *
 * <ol>
 *   <li>The response never contains an exception message that was not written
 *       for a caller to read. An unhandled exception produces a fixed sentence
 *       and a correlation id; the detail goes to the log, not over the wire.
 *   <li>Every response carries the correlation id, so "it failed" and "here is
 *       the line in the log" are the same conversation.
 * </ol>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Problem type URIs. Not dereferenced today; stable so that clients may switch on them. */
    private static final String TYPE_BASE = "https://opsatlas.ambitiousconcepts.io/problems/";

    @ExceptionHandler(ValidationFailedException.class)
    public ProblemDetail onValidationFailed(ValidationFailedException e) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "validation-failed",
                "The document did not satisfy the schema",
                e.getMessage());
        problem.setProperty("violations", e.violations());
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage());
    }

    @ExceptionHandler(Cursor.InvalidCursorException.class)
    public ProblemDetail onInvalidCursor(Cursor.InvalidCursorException e) {
        ProblemDetail problem =
                problem(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid pagination cursor", e.getMessage());
        problem.setProperty(
                "violations",
                List.of(new Violation(
                        "/cursor",
                        "format",
                        "a nextCursor value returned by a previous response",
                        null,
                        e.getMessage())));
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expected = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "a different type";
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-parameter",
                "Invalid request parameter",
                "The parameter '" + e.getName() + "' could not be read as " + expected + ".");
    }

    /**
     * A request parameter was outside its permitted range.
     *
     * <p>The parameter name and the constraint message are pulled out of the
     * exception rather than replaced with a generic sentence: "limit must be
     * less than or equal to 100" is fixable without opening the documentation,
     * and "one or more parameters were invalid" is not.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail onParameterConstraintViolation(HandlerMethodValidationException e) {
        List<Violation> violations = e.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new Violation(
                                "/" + result.getMethodParameter().getParameterName(),
                                "constraint",
                                defaultMessage(error.getDefaultMessage()),
                                null,
                                "The parameter '" + result.getMethodParameter().getParameterName() + "' "
                                        + defaultMessage(error.getDefaultMessage()) + ".")))
                .toList();

        String detail = violations.isEmpty()
                ? "One or more request parameters were outside their permitted range."
                : violations.stream().map(Violation::message).collect(java.util.stream.Collectors.joining(" "));

        ProblemDetail problem =
                problem(HttpStatus.BAD_REQUEST, "invalid-parameter", "Invalid request parameter", detail);
        if (!violations.isEmpty()) {
            problem.setProperty("violations", violations);
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onBodyValidation(MethodArgumentNotValidException e) {
        List<Violation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new Violation(
                        "/" + error.getField().replace('.', '/'),
                        "constraint",
                        defaultMessage(error.getDefaultMessage()),
                        error.getRejectedValue() == null ? null : String.valueOf(error.getRejectedValue()),
                        "The field '" + error.getField() + "' " + defaultMessage(error.getDefaultMessage()) + "."))
                .toList();

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "invalid-parameter",
                "Invalid request parameter",
                "The request body did not satisfy the constraints on one or more fields.");
        if (!violations.isEmpty()) {
            problem.setProperty("violations", violations);
        }
        return problem;
    }

    private static String defaultMessage(String message) {
        return message != null && !message.isBlank() ? message : "was not accepted";
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "unreadable-body",
                "Request body could not be read",
                "The request body was empty or was not in the format this endpoint accepts.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail onNoResource(NoResourceFoundException e) {
        return problem(
                HttpStatus.NOT_FOUND,
                "not-found",
                "Not found",
                "No endpoint is mapped to " + e.getHttpMethod() + " " + e.getResourcePath() + ".");
    }

    /**
     * Anything not handled above. The caller is told the correlation id and
     * nothing else - an exception message can carry a table name, a SQL
     * fragment or a file path, none of which belong in a response.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnhandled(Exception e, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current();
        log.error(
                "Unhandled exception serving {} {} [correlationId={}]",
                request.getMethod(),
                request.getRequestURI(),
                correlationId,
                e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "The control plane failed to handle this request. Quote correlation id " + correlationId
                        + " when reporting it.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setProperty("correlationId", CorrelationIdFilter.current());
        return problem;
    }
}
