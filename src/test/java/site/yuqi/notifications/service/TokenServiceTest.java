package site.yuqi.notifications.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {

    private final TokenService tokens = new TokenService("test-pepper-abc");

    @Test
    void generatesUniqueTokens() {
        String a = tokens.generateToken();
        String b = tokens.generateToken();
        assertNotNull(a);
        assertEquals(64, a.length(), "expected 32 bytes -> 64 hex chars");
        assertNotEquals(a, b);
        assertTrue(a.matches("[0-9a-f]+"));
    }

    @Test
    void hashIsDeterministicAndPeppered() {
        String h1 = tokens.hash("abc");
        String h2 = tokens.hash("abc");
        assertEquals(h1, h2);
        assertEquals(64, h1.length()); // SHA-256 hex

        TokenService other = new TokenService("different-pepper");
        assertNotEquals(h1, other.hash("abc"));
    }

    @Test
    void tokenMatchesUsesHash() {
        String raw = tokens.generateToken();
        String h = tokens.hash(raw);
        assertTrue(tokens.tokenMatches(raw, h));
        assertFalse(tokens.tokenMatches(raw + "x", h));
        assertFalse(tokens.tokenMatches(null, h));
        assertFalse(tokens.tokenMatches(raw, null));
    }
}
