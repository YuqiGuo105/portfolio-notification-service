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
}
