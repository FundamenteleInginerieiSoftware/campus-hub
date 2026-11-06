package com.campus.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401, 403 and firewall 400s as the same ProblemDetail JSON that Liviu's GlobalExceptionHandler produces
 * (docs/api-contract.md section 4). These errors happen in the security filters, before the request
 * reaches a controller, so his handler never sees them. Without this class the body would be empty.
 */
class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler, RequestRejectedHandler {

    private final JsonMapper jsonMapper;

    SecurityErrorHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // No token, a fake one, or an expired one. Stefania's frontend sees UNAUTHORIZED, drops the
    // token and sends the user back to the login page.
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Log in first: the token is missing, invalid or expired");
    }

    // A valid token whose role isn't enough, e.g. a student on /api/v1/admin/**.
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this");
    }

    // Bug fixed (#118): Spring Security's firewall blocks suspicious URLs (";", "..", encoded "/")
    // before any other filter runs. Its default handler sent a bare 400 that Spring Boot turned into
    // its own JSON without our "code". Spring uses a RequestRejectedHandler bean instead when there
    // is one (SecurityConfig defines it), so these now get the same ProblemDetail as 401/403.
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException e)
            throws IOException {
        write(
                request,
                response,
                HttpStatus.BAD_REQUEST,
                "REQUEST_REJECTED",
                "The address contains characters that are not allowed");
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        // A LinkedHashMap keeps the fields in the same order as the contract example.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
