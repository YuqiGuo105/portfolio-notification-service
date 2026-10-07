package site.yuqi.notifications.service;

import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class WebPushTransportTest {
    @Test void signsAndEncryptsWithRealLibraryWithoutSending() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var server = generator.generateKeyPair();
        var device = generator.generateKeyPair();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String pub = encoder.encodeToString(Utils.encode((ECPublicKey) server.getPublic()));
        String priv = encoder.encodeToString(Utils.encode((ECPrivateKey) server.getPrivate()));
        var transport = new WebPushTransport(pub, priv, "mailto:developer@example.test");
        var request = transport.prepare("https://fcm.googleapis.com/fcm/send/test-only",
                encoder.encodeToString(Utils.encode((ECPublicKey) device.getPublic())),
                encoder.encodeToString(new byte[16]), "Private test payload".getBytes(StandardCharsets.UTF_8));
        assertEquals("aes128gcm", request.getHeaders().get("Content-Encoding"));
        assertTrue(request.getHeaders().get("Authorization").startsWith("vapid t="));
        assertEquals("86400", request.getHeaders().get("TTL"));
        assertFalse(new String(request.getBody(), StandardCharsets.UTF_8).contains("Private test payload"));
        assertTrue(request.getBody().length > 40);
        assertFalse(new WebPushTransport("", "", "mailto:developer@example.test").enabled());
    }
}
