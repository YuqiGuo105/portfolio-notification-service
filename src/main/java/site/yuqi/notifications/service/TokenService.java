package site.yuqi.notifications.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class TokenService {

    private static final SecureRandom RNG = new SecureRandom();

    private final String pepper;

    public TokenService(@Value("${portfolio.token.pepper:dev-only-pepper-change-me}") String pepper) {
        this.pepper = pepper == null ? "" : pepper;
    }

    /**
     * Generate a URL-safe opaque token (32 bytes ≈ 256 bits of entropy → 64 hex chars).
     */
    public String generateToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * SHA-256(pepper || token) → hex. Constant-time-friendly comparison should be used at verify time.
     */
    public String hash(String token) {
        if (token == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(pepper.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Constant-time compare of two hex strings.
     */
    public boolean tokenMatches(String rawToken, String storedHash) {
        if (rawToken == null || storedHash == null) return false;
        String computed = hash(rawToken);
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
