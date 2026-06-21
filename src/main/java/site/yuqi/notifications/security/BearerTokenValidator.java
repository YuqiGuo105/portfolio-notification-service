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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * Validates Bearer tokens issued by Supabase (HS256) or Google Identity (RS256).
 *
 * <p>Used by {@link InternalAuthFilter} as a JWT fallback when the legacy
 * {@code X-Internal-Token} shared-secret header is not present.</p>
 *
 * <p>Configuration (all optional — at least one issuer must be configured):</p>
 * <pre>
 *   portfolio.swagger.jwt-secret       = Supabase JWT secret (HS256, base64 or plain)
 *   portfolio.swagger.google-client-id = Google OAuth client ID (RS256 audience check)
 *   portfolio.swagger.allowed-emails   = CSV email allow-list applied after validation
 * </pre>
 *
 * <p>Property names are kept on the {@code portfolio.swagger.*} namespace for
 * backwards compatibility with existing Cloud Run / secret bindings.</p>
 */
@Component
@Slf4j
public class BearerTokenValidator {

    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final long GOOGLE_KEYS_TTL_MS = 3_600_000L;

    private final SecretKey supabaseSigningKey;
    private final boolean supabaseConfigured;
    private final String googleClientId;
    private final List<String> allowedEmails;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final Map<String, PublicKey> googleKeyCache = new ConcurrentHashMap<>();
    private volatile long googleKeysFetchedAt = 0L;

    public BearerTokenValidator(
            @Value("${portfolio.swagger.jwt-secret:}") String jwtSecret,
            @Value("${portfolio.swagger.google-client-id:}") String googleClientId,
            @Value("${portfolio.swagger.allowed-emails:}") String allowedEmailsCsv,
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String trimmedSecret = jwtSecret == null ? "" : jwtSecret.trim();
        if (trimmedSecret.isEmpty()) {
            this.supabaseSigningKey = null;
            this.supabaseConfigured = false;
            log.warn("{\"event\":\"bearer_supabase_unconfigured\"}");
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

        this.googleClientId = (googleClientId == null) ? "" : googleClientId.trim();
        this.allowedEmails = Arrays.stream(allowedEmailsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        log.info("{\"event\":\"bearer_validator_init\",\"supabase\":{},\"google_aud_check\":{},\"allowed_count\":{}}",
                supabaseConfigured, !this.googleClientId.isEmpty(), this.allowedEmails.size());
    }

    /** True when at least one issuer (Supabase or Google) is configured. */
    public boolean isConfigured() {
        return supabaseConfigured || !googleClientId.isEmpty();
    }

    /** Snapshot of the configured allow-list (empty means "allow any verified email"). */
    public List<String> allowedEmails() {
        return allowedEmails;
    }

    /**
     * Validate a Bearer token. Returns the verified email claim.
     *
     * @throws AuthException on any validation failure (signature, issuer, audience, expiry).
     */
    public String validate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthException("missing_bearer_token", "Bearer token is empty.");
        }
        String issuer = peekClaim(token, "iss");
        try {
            String email;
            if (GOOGLE_ISSUER.equals(issuer)) {
                email = validateGoogleToken(token);
            } else {
                email = validateSupabaseToken(token);
            }
            if (email == null || email.isBlank()) {
                throw new AuthException("no_email_claim", "Token has no email claim.");
            }
            if (!allowedEmails.isEmpty() && !allowedEmails.contains(email)) {
                throw new AuthException("forbidden_email",
                        "Your account (" + email + ") is not on the allow-list.");
            }
            return email;
        } catch (ExpiredJwtException e) {
            throw new AuthException("token_expired", "Token has expired. Please sign in again.");
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.warn("{\"event\":\"bearer_validation_error\",\"err\":\"{}\"}", e.getMessage());
            throw new AuthException("token_error", "Token validation failed.");
        }
    }

    // ── Supabase HS256 ─────────────────────────────────────────────────────

    private String validateSupabaseToken(String token) {
        if (!supabaseConfigured) {
            throw new AuthException("supabase_not_configured",
                    "Supabase JWT secret is not set; Supabase tokens cannot be validated.");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(supabaseSigningKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return (String) claims.get("email");
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
            throw new AuthException("invalid_supabase_token",
                    "Supabase JWT signature validation failed.");
        }
    }

    // ── Google RS256 ───────────────────────────────────────────────────────

    private String validateGoogleToken(String token) throws IOException, InterruptedException {
        String kid = peekHeader(token, "kid");
        PublicKey key = getGooglePublicKey(kid);
        if (key == null) {
            throw new AuthException("google_key_not_found",
                    "No Google public key found for kid=" + kid);
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
            throw new AuthException("invalid_google_token",
                    "Google ID token signature validation failed.");
        }

        if (!GOOGLE_ISSUER.equals(claims.getIssuer())) {
            throw new AuthException("wrong_issuer",
                    "Expected issuer " + GOOGLE_ISSUER + ", got " + claims.getIssuer());
        }

        if (!googleClientId.isEmpty()) {
            Object aud = claims.get("aud");
            boolean audMatches = (aud instanceof String && googleClientId.equals(aud))
                    || (aud instanceof List<?> && ((List<?>) aud).contains(googleClientId));
            if (!audMatches) {
                throw new AuthException("wrong_audience",
                        "Google ID token audience does not match GOOGLE_CLIENT_ID.");
            }
        }

        if (Boolean.FALSE.equals(claims.get("email_verified"))) {
            throw new AuthException("email_not_verified",
                    "Google account email is not verified.");
        }

        return (String) claims.get("email");
    }

    private PublicKey getGooglePublicKey(String kid) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (now - googleKeysFetchedAt > GOOGLE_KEYS_TTL_MS || googleKeyCache.isEmpty()) {
            refreshGoogleKeys();
        }
        if (kid == null) {
            return googleKeyCache.values().stream().findFirst().orElse(null);
        }
        if (!googleKeyCache.containsKey(kid)) {
            refreshGoogleKeys();
        }
        return googleKeyCache.get(kid);
    }

    private void refreshGoogleKeys() throws IOException, InterruptedException {
        log.info("{\"event\":\"google_jwks_refresh\"}");
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
    }

    // ── JWT introspection helpers ─────────────────────────────────────────

    private String peekClaim(String token, String name) {
        return peekPart(token, 1, name);
    }

    private String peekHeader(String token, String name) {
        return peekPart(token, 0, name);
    }

    @SuppressWarnings("unchecked")
    private String peekPart(String token, int partIndex, String key) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
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

    /** Carries a short error code alongside a human-readable message. */
    public static class AuthException extends RuntimeException {
        public final String code;
        public AuthException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
