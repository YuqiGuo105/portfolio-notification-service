package site.yuqi.notifications.security;

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
import java.util.Set;

/**
 * Enforces a shared-secret header on all `/api/subscriptions/*` and `/api/notifications/*`
 * endpoints. The Next.js Portfolio proxy is the only legitimate caller; user browsers never
 * call this service directly (they go through the proxy, which adds the header server-side
 * from an env var that is never exposed to the browser).
 *
 * <p>Public endpoints (allowlisted): {@code /api/health/**}, {@code /actuator/**}.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>If {@code portfolio.internal-token} is blank, the filter <b>fails closed</b>
 *       (rejects every protected request with 503). This prevents accidentally deploying
 *       an unauthenticated service.</li>
 *   <li>Header name is {@code X-Internal-Token}.</li>
 *   <li>Comparison uses {@link MessageDigest#isEqual(byte[], byte[])} (constant time).</li>
 *   <li>On mismatch, returns 401 with a minimal JSON body and no diagnostic that would help
 *       an attacker (no echo of the supplied token, no timing leak).</li>
 * </ul>
 */
@Component
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {

    /** Paths that bypass the filter entirely. */
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/health",
            "/actuator"
    );

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expectedTokenBytes;
    private final boolean tokenConfigured;

    public InternalAuthFilter(
            @Value("${portfolio.internal-token:}") String internalToken) {
        String trimmed = internalToken == null ? "" : internalToken.trim();
        this.tokenConfigured = !trimmed.isEmpty();
        this.expectedTokenBytes = tokenConfigured
                ? trimmed.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!tokenConfigured) {
            log.warn("{\"event\":\"internal_auth_unconfigured\",\"msg\":\"portfolio.internal-token is empty; all protected endpoints will return 503\"}");
        } else {
            log.info("{\"event\":\"internal_auth_enabled\",\"token_length\":{}}", trimmed.length());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return false;
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
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
        if (!tokenConfigured) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "internal_auth_not_configured");
            return;
        }
        String supplied = request.getHeader(HEADER);
        if (supplied == null || supplied.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "missing_internal_token");
            return;
        }
        byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual is constant-time and length-safe (returns false on length mismatch).
        if (!MessageDigest.isEqual(suppliedBytes, expectedTokenBytes)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_internal_token");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
