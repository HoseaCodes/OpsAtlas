package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.shared.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Makes the door answer in the same shape as everything else.
 *
 * <p>CLAUDE.md section 9 asks for one error shape everywhere, carrying a
 * {@code correlationId}. Spring Security's defaults answer 401 and 403 with an
 * empty body, which would have given this API two formats: a problem document
 * wherever a controller is reached, and nothing at all wherever one is not.
 * The second is exactly where a caller most needs to be told what happened -
 * they cannot get in, and an empty 401 does not say whether the token was
 * missing, expired, or simply unknown here.
 */
@Component
class ProblemSecurityResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    ProblemSecurityResponses(ObjectMapper json) {
        this.json = json;
    }

    /** No credential, or one that did not verify. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        write(
                response,
                Problems.of(
                        HttpStatus.UNAUTHORIZED,
                        "unauthenticated",
                        "Authentication required",
                        "This endpoint needs a bearer token issued by the configured identity provider. "
                                + "Send it as 'Authorization: Bearer <token>'. If you sent one, it did not verify: "
                                + "it may be expired, or signed by a key this system does not publish trust in."));
    }

    /**
     * A credential that verified, belonging to somebody this system does not
     * know. Deliberately says so rather than pretending the endpoint is absent:
     * the caller is authenticated, so there is no secret being kept by being
     * vague, and "ask an administrator" is the only useful next step.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        write(
                response,
                Problems.of(
                        HttpStatus.FORBIDDEN,
                        "not-provisioned",
                        "Not provisioned",
                        "Your token is valid, but this account is not provisioned in this OpsAtlas. "
                                + "An administrator has to add it before it can see an organization's catalog."));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
