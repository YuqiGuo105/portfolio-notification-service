package site.yuqi.notifications.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Authenticates inbound requests to {@code /api/**} using one of two channels:
 *
 * <ol>
 *   <li><b>{@code X-Internal-Token}</b> — shared secret used by the Next.js Portfolio
 *       proxy and any other server-to-server caller. Compared in constant time.</li>
 *   <li><b>{@code Authorization: Bearer &lt;token&gt;}</b> — Supabase access token or
 *       Google ID token. Delegated to {@link BearerTokenValidator}; the resulting email
 *       claim is checked against {@code portfolio.swagger.allowed-emails}.</li>
 * </ol>
 *
 * Either channel succeeding is sufficient. If both are absent or invalid, the filter
 * returns {@code 401} with a JSON body describing which channels were attempted.
 *
 * <p><b>Public paths</b> bypass the filter: {@code /api/health}, {@code /actuator},
 * {@code /swagger-ui}, {@code /v3/api-docs}, {@code /swagger-resources}, {@code /webjars}.
 * Swagger UI is intentionally open (matching admin-service) so operators can browse
 * the API docs without authenticating. The actual API calls made from the UI still go
 * through this filter and require a valid Bearer token.</p>
 *
 * <p><b>Fail-closed</b>: if neither {@code X-Internal-Token} nor a Bearer issuer is
 * configured, every protected request returns {@code 503} to prevent accidentally
 * deploying an unauthenticated service.</p>
 */
@Component
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/health",
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs",
            "/swagger-resources",
            "/webjars"
    );

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expectedTokenBytes;
    private final boolean tokenConfigured;
    private final BearerTokenValidator bearerValidator;
    private final ObjectMapper objectMapper;

    public InternalAuthFilter(
            @Value("${portfolio.internal-token:}") String internalToken,
            BearerTokenValidator bearerValidator,
            ObjectMapper objectMapper) {
        String trimmed = internalToken == null ? "" : internalToken.trim();
        this.tokenConfigured = !trimmed.isEmpty();
        this.expectedTokenBytes = tokenConfigured
                ? trimmed.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        this.bearerValidator = bearerValidator;
        this.objectMapper = objectMapper;

        if (!tokenConfigured && !bearerValidator.isConfigured()) {
            log.warn("{\"event\":\"auth_unconfigured\",\"msg\":\"Neither X-Internal-Token nor any Bearer issuer configured; all /api/** will return 503\"}");
        } else {
            log.info("{\"event\":\"auth_enabled\",\"internal_token\":{},\"bearer\":{}}",
                    tokenConfigured, bearerValidator.isConfigured());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return false;
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + ".")) {
                return true;
            }
        }
        // Only enforce on our own API paths; static resources etc. fall through.
        return !(path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (!tokenConfigured && !bearerValidator.isConfigured()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "auth_not_configured",
                    "Neither X-Internal-Token nor any Bearer issuer is configured.");
            return;
        }

        // Channel 1: X-Internal-Token (constant-time compare).
        String supplied = request.getHeader(HEADER);
        if (supplied != null && !supplied.isEmpty() && tokenConfigured) {
            byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(suppliedBytes, expectedTokenBytes)) {
                chain.doFilter(request, response);
                return;
            }
            // Fall through and try Bearer if the internal token is invalid.
        }

        // Channel 2: Authorization: Bearer <token>.
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (!bearerValidator.isConfigured()) {
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "bearer_not_configured",
                        "Bearer token supplied but no JWT issuer is configured on the server.");
                return;
            }
            String token = authHeader.substring(7).trim();
            try {
                String email = bearerValidator.validate(token);
                log.debug("{\"event\":\"bearer_access_granted\",\"email\":\"{}\"}", email);
                chain.doFilter(request, response);
                return;
            } catch (BearerTokenValidator.AuthException e) {
                int status = "forbidden_email".equals(e.code)
                        ? HttpServletResponse.SC_FORBIDDEN
                        : HttpServletResponse.SC_UNAUTHORIZED;
                writeError(response, status, e.code, e.getMessage());
                return;
            }
        }

        // Neither channel produced credentials.
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                "missing_credentials",
                "Supply X-Internal-Token (server-to-server) or Authorization: Bearer <Supabase or Google token>.");
    }

    private void writeError(HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
