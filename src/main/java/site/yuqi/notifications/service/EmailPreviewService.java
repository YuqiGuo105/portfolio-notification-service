package site.yuqi.notifications.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
public class EmailPreviewService {

    private final int maxCharacters;

    public EmailPreviewService(
            @Value("${portfolio.email.preview.max-characters:320}") int maxCharacters) {
        this.maxCharacters = Math.max(80, maxCharacters);
    }

    public String preview(String source) {
        if (source == null || source.isBlank()) return "";

        String compact = source
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
                .replaceAll("[*_~`>|]", " ");
        compact = HtmlUtils.htmlUnescape(compact).replaceAll("\\s+", " ").trim();

        if (compact.length() <= maxCharacters) return compact;
        int end = maxCharacters - 1;
        int boundary = compact.lastIndexOf(' ', end);
        if (boundary >= maxCharacters / 2) end = boundary;
        return compact.substring(0, end).trim() + "…";
    }

    /** Operational evidence is plain text, not a truncated Markdown article preview. */
    public String body(String topic, String source) {
        if (!"ADMIN_ALERTS".equals(topic)) return preview(source);
        if (source == null || source.isBlank()) return "";
        String text = source.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]|\\p{Cf}", "").strip();
        return text.length() <= 8000 ? text : text.substring(0, 8000) + "\n[Details truncated]";
    }
}
