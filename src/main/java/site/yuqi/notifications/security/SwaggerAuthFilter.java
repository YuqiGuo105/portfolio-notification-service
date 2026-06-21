package site.yuqi.notifications.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards the Swagger UI and OpenAPI spec paths ({@code /swagger-ui/**}, {@code /v3/api-docs/**})
 * with JWT authentication.
 *
 * <h3>Supported token types</h3>
 * <ul>
 *   <li><b>Supabase JWT</b> (HS256) — issued by {@code supabase.auth.getSession()}.
 *       Validated using {@code SUPABASE_JWT_SECRET}.</li>
 *   <li><b>Google ID token</b> (RS256) — issued by Google Sign-In / Google OAuth2.
 *       Validated using Google's public JWKS endpoint. Requires {@code GOOGLE_CLIENT_ID}
 *       to verify the {@code aud} claim. Works for users who log in with Google via
 *       Supabase <em>or</em> who present a raw Google credential.</li>
 * </ul>
 *
 * <p>The issuer claim ({@code iss}) in the token is used to route to the correct
 * validation path automatically — no manual selection required.
 *
 * <h3>Usage in Swagger UI</h3>
 * <p>Click <b>Authorize</b>, then enter {@code Bearer <token>} where token is:
 * <ul>
 *   <li><b>Supabase:</b> {@code (await supabase.auth.getSession()).data.session.access_token}</li>
 *   <li><b>Google:</b> the {@code credential} field from the Google Sign-In response
 *       ({@code google.accounts.id.initialize} callback)</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>
 * SUPABASE_JWT_SECRET=&lt;base64-or-plain jwt secret from Supabase dashboard&gt;
 * GOOGLE_CLIENT_ID=&lt;your-oauth2-client-id.apps.googleusercontent.com&gt;  (optional)
 * portfolio.swagger.allowed-emails=yuqi.guo17@gmail.com
 * </pre>
 */
@Component
@Slf4j
public class SwaggerAuthFilter extends OncePerRequestFilter {

    private static final Set<String> SWAGGER_PREFIXES = Set.of("/swagger-ui", "/v3/api-docs");
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    /** Re-fetch Google public keys at most once per hour. */
    private static final long GOOGLE_KEYS_TTL_MS = 3_600_000L;

    private final SecretKey supabaseSigningKey;
    private final boolean supabaseConfigured;
    private final String googleClientId;
    private final List<String> allowedEmails;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** kid → RSA PublicKey cache for Google tokens. */
    private final Map<String, PublicKey> googleKeyCache = new ConcurrentHashMap<>();
    private volatile long googleKeysFetchedAt = 0L;

    public SwaggerAuthFilter(
            @Value("${portfolio.swagger.jwt-secret:}") String jwtSecret,
            @Value("${portfolio.swagger.google-client-id:}") String googleClientId,
            @Value("${portfolio.swagger.allowed-emails:}") String allowedEmailsCsv,
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // ── Supabase HS256 key ────────────────────────────────────────────────
        String trimmedSecret = jwtSecret == null ? "" : jwtSecret.trim();
        if (trimmedSecret.isEmpty()) {
            this.supabaseSigningKey = null;
            this.supabaseConfigured = false;
            log.warn("{\"event\":\"swagger_supabase_unconfigured\",\"msg\":\"SUPABASE_JWT_SECRET not set; Supabase tokens will not be accepted\"}");
        } else {
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(trimmedSecret);
            } catch (IllegalArgumentException e) {
                keyBytes = trimmedSecret.getBytes(StandardCharsets.UTF_8);
            }
            this.supabaseSigningKey = Keys.hmacShaKeyFor(keyBytes);
            this.supabaseConfigured = true;
        }

        // ── Google RS256 client ID ────────────────────────────────────────────
        this.googleClientId = (googleClientId == null) ? "" : googleClientId.trim();
        if (this.googleClientId.isEmpty()) {
            log.info("{\"event\":\"swagger_google_client_id_not_set\",\"msg\":\"Google ID tokens accepted for any audience (set GOOGLE_CLIENT_ID to restrict)\"}");
        }

        this.allowedEmails = Arrays.stream(allowedEmailsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        log.info("{\"event\":\"swagger_auth_enabled\",\"supabase\":{},\"google_aud_check\":{},\"allowed_count\":{}}",
                supabaseConfigured, !this.googleClientId.isEmpty(), this.allowedEmails.size());
    }

    // ── Filter routing ───────────────────────────────────────────────────────

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        for (String prefix : SWAGGER_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + ".")) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!supabaseConfigured && googleClientId.isEmpty()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "swagger_not_configured",
                    "Neither SUPABASE_JWT_SECRET nor GOOGLE_CLIENT_ID is set; Swagger UI is disabled.");
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "missing_bearer_token",
                    "Include 'Authorization: Bearer <token>'. Accepted: Supabase access_token or Google ID token.");
            return;
        }

        String token = authHeader.substring(7).trim();

        // Peek at issuer (no verification yet) to pick the right validation path
        String issuer = peekClaim(token, "iss");

        String email;
        try {
            if (GOOGLE_ISSUER.equals(issuer)) {
                email = validateGoogleToken(token);
            } else {
                // Default: Supabase JWT (also covers Supabase + Google OAuth flow, because
                // Supabase re-issues its own HS256 JWT after Google OAuth completes)
                email = validateSupabaseToken(token);
            }
        } catch (ExpiredJwtException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "token_expired",
                    "Token has expired. Please sign in again.");
            return;
        } catch (AuthException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.code, e.getMessage());
            return;
        } catch (Exception e) {
            log.warn("{\"event\":\"jwt_validation_error\",\"err\":\"{}\"}", e.getMessage());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "token_error",
                    "Token validation failed.");
            return;
        }

        if (email == null || email.isBlank()) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "no_email_claim",
                    "Token has no email claim.");
            return;
        }

        if (!allowedEmails.isEmpty() && !allowedEmails.contains(email)) {
            log.warn("{\"event\":\"swagger_access_denied\",\"email\":\"{}\"}", email);
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "access_denied",
                    "Your account (" + email + ") is not on the Swagger allowlist.");
            return;
        }

        log.debug("{\"event\":\"swagger_access_granted\",\"email\":\"{}\"}", email);
        chain.doFilter(request, response);
    }

    // ── Supabase HS256 validation ────────────────────────────────────────────

    private String validateSupabaseToken(String token) {
        if (!supabaseConfigured) {
            throw new AuthException("supabase_not_configured",
                    "SUPABASE_JWT_SECRET is not set; Supabase tokens cannot be validated.");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(supabaseSigningKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return (String) claims.get("email");
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
            throw new AuthException("invalid_supabase_token", "Supabase JWT signature validation failed.");
        }
    }

    // ── Google RS256 validation ──────────────────────────────────────────────

    private String validateGoogleToken(String token) throws IOException, InterruptedException {
        String kid = peekHeader(token, "kid");
        PublicKey key = getGooglePublicKey(kid);
        if (key == null) {
            throw new AuthException("google_key_not_found",
                    "No Google public key found for kid=" + kid + ". Keys may have rotated; try again.");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
            throw new AuthException("invalid_google_token", "Google ID token signature validation failed.");
        }

        // Verify issuer
        String iss = claims.getIssuer();
        if (!GOOGLE_ISSUER.equals(iss)) {
            throw new AuthException("wrong_issuer", "Expected issuer " + GOOGLE_ISSUER + ", got " + iss);
        }

        // Verify audience (client ID) if configured
        if (!googleClientId.isEmpty()) {
            Object aud = claims.get("aud");
            boolean audMatches = (aud instanceof String && googleClientId.equals(aud))
                    || (aud instanceof List<?> && ((List<?>) aud).contains(googleClientId));
            if (!audMatches) {
                throw new AuthException("wrong_audience",
                        "Google ID token audience does not match GOOGLE_CLIENT_ID.");
            }
        }

        // Google ID tokens put email in top-level claim; email_verified should be true
        Object emailVerified = claims.get("email_verified");
        if (Boolean.FALSE.equals(emailVerified)) {
            throw new AuthException("email_not_verified",
                    "Google account email is not verified.");
        }

        return (String) claims.get("email");
    }

    /** Returns the RSA public key for the given kid, refreshing the cache if needed. */
    @SuppressWarnings("unchecked")
    private PublicKey getGooglePublicKey(String kid) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (now - googleKeysFetchedAt > GOOGLE_KEYS_TTL_MS || googleKeyCache.isEmpty()) {
            refreshGoogleKeys();
        }
        if (kid == null) {
            // If no kid, use first available key (rare, but some older tokens omit kid)
            return googleKeyCache.values().stream().findFirst().orElse(null);
        }
        // If still not found after refresh, force one more refresh (keys may have just rotated)
        if (!googleKeyCache.containsKey(kid)) {
            refreshGoogleKeys();
        }
        return googleKeyCache.get(kid);
    }

    private void refreshGoogleKeys() throws IOException, InterruptedException {
        log.info("{\"event\":\"google_jwks_refresh\",\"url\":\"{}\"}", GOOGLE_JWKS_URL);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GOOGLE_JWKS_URL))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.error("{\"event\":\"google_jwks_fetch_failed\",\"status\":{}}", resp.statusCode());
            return;
        }

        googleKeyCache.clear();
        @SuppressWarnings("unchecked")
        Set<Jwk<?>> keys = Jwks.setParser().build().parse(resp.body()).getKeys();
        for (Jwk<?> jwk : keys) {
            try {
                PublicKey pk = (PublicKey) jwk.toKey();
                String keyId = jwk.getId();
                if (keyId != null) {
                    googleKeyCache.put(keyId, pk);
                }
            } catch (Exception e) {
                log.warn("{\"event\":\"google_jwk_parse_error\",\"kid\":\"{}\",\"err\":\"{}\"}",
                        jwk.getId(), e.getMessage());
            }
        }
        googleKeysFetchedAt = System.currentTimeMillis();
        log.info("{\"event\":\"google_jwks_refreshed\",\"key_count\":{}}", googleKeyCache.size());
    }

    // ── JWT introspection helpers (no signature verification) ───────────────

    /** Decode the JWT payload and return the value of the given claim, or null. */
    private String peekClaim(String token, String claimName) {
        return peekPart(token, 1, claimName);
    }

    /** Decode the JWT header and return the value of the given field, or null. */
    private String peekHeader(String token, String fieldName) {
        return peekPart(token, 0, fieldName);
    }

    @SuppressWarnings("unchecked")
    private String peekPart(String token, int partIndex, String key) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            // Base64URL decode — pad to multiple of 4
            String part = parts[partIndex];
            int pad = (4 - part.length() % 4) % 4;
            part = part + "=".repeat(pad);
            byte[] decoded = Base64.getUrlDecoder().decode(part);
            Map<String, Object> map = objectMapper.readValue(decoded, Map.class);
            Object val = map.get(key);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void writeError(HttpServletResponse response, int status,
                             String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }

    /** Lightweight checked exception used internally to carry an error code. */
    private static class AuthException extends RuntimeException {
        final String code;
        AuthException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
