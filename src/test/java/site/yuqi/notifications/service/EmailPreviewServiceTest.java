package site.yuqi.notifications.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailPreviewServiceTest {

    @Test
    void stripsArticleMarkupAndBoundsThePreview() {
        EmailPreviewService service = new EmailPreviewService(120);
        String article = "# Heading\n<script>alert('x')</script>" +
                "A [useful link](https://example.com) and **formatted text**. ".repeat(20) +
                "```java\nSystem.out.println(\"not for email\");\n```";

        String preview = service.preview(article);

        assertTrue(preview.length() <= 120);
        assertTrue(preview.contains("useful link"));
        assertFalse(preview.contains("<script"));
        assertFalse(preview.contains("System.out"));
        assertFalse(preview.contains("https://example.com"));
    }

    @Test
    void operationalBodyRetainsComparatorsAndLinesButRemainsBounded() {
        var service = new EmailPreviewService(120);
        String body = "Condition: count >= 1\r\nEvent ID: event-123\n" + "Evidence\n".repeat(80);
        assertEquals(body.replace("\r\n", "\n").strip(), service.body("ADMIN_ALERTS", body));
        assertTrue(service.body("ARTICLE_UPDATES", body).length() <= 120);
        assertTrue(service.body("ADMIN_ALERTS", "x".repeat(9000)).endsWith("[Details truncated]"));
        assertEquals("", service.body("ADMIN_ALERTS", null));
        assertEquals("ab", service.body("ADMIN_ALERTS", "a\u0000\u202eb"));
    }
}
