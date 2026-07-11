package site.yuqi.notifications.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
