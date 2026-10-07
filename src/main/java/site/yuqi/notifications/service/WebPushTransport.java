package site.yuqi.notifications.service;

import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;
import java.util.Locale;

@Component
public class WebPushTransport {
    private final String publicKey;
    private final Codec codec;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public WebPushTransport(@Value("${portfolio.push.public-key:}") String publicKey,
                            @Value("${portfolio.push.private-key:}") String privateKey,
                            @Value("${portfolio.push.subject:mailto:yuqi.guo17@gmail.com}") String subject) throws GeneralSecurityException {
        Security.addProvider(new BouncyCastleProvider());
        this.publicKey = publicKey;
        codec = publicKey.isBlank() || privateKey.isBlank() ? null : new Codec(publicKey, privateKey, subject);
    }
    public boolean enabled() { return codec != null; }
    public String publicKey() { return enabled() ? publicKey : ""; }

    public int send(String endpoint, String p256dh, String auth, byte[] payload) throws Exception {
        if (codec == null) throw new IllegalStateException("Browser push is not configured");
        var encrypted = prepare(endpoint, p256dh, auth, payload);
        HttpRequest.Builder request = HttpRequest.newBuilder(requireEndpoint(encrypted.getUrl())).timeout(Duration.ofSeconds(10));
        encrypted.getHeaders().forEach(request::header);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(encrypted.getBody())).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    nl.martijndwars.webpush.HttpRequest prepare(String endpoint, String key, String auth, byte[] payload) throws Exception {
        requireEndpoint(endpoint);
        return codec.encode(new Notification(endpoint, key, auth, payload, 86400));
    }
    public static URI requireEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 2048) throw new IllegalArgumentException("Invalid push endpoint");
        URI uri;
        try { uri = URI.create(endpoint); } catch (Exception e) { throw new IllegalArgumentException("Invalid push endpoint"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean provider = host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com")
                || host.equals("web.push.apple.com") || host.endsWith(".push.apple.com");
        if (!provider || !"https".equals(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPath().length() < 2) {
            throw new IllegalArgumentException("Unsupported push provider endpoint");
        }
        return uri;
    }
    // Library encryption/signing, with bounded, no-redirect JDK transport.
    private static class Codec extends AbstractPushService<Codec> {
        Codec(String publicKey, String privateKey, String subject) throws GeneralSecurityException { super(publicKey, privateKey, subject); }
        nl.martijndwars.webpush.HttpRequest encode(Notification notification) throws Exception {
            return prepareRequest(notification, Encoding.AES128GCM);
        }
    }
}
