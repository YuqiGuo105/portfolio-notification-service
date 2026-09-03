package site.yuqi.notifications.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentEventTest {

    private ContentEvent valid() {
        return new ContentEvent(
                "evt_1", "ARTICLE_PUBLISHED", "ARTICLE_UPDATES",
                "BLOG", "blog_1", "Title", "summary", "/blog-single/blog_1",
                null, "ARTICLE_PUBLISHED:blog_1:v1", Map.of());
    }

    @Test
    void validEventPassesValidation() {
        assertTrue(valid().isValid());
    }

    @Test
    void invalidWhenEventTypeUnknown() {
        ContentEvent e = new ContentEvent("e", "WRONG", "ARTICLE_UPDATES",
                null, null, "t", null, null, null, "k", null);
        assertFalse(e.isValid());
    }

    @Test
    void invalidWhenTopicUnknown() {
        ContentEvent e = new ContentEvent("e", "ARTICLE_PUBLISHED", "OTHER_TOPIC",
                null, null, "t", null, null, null, "k", null);
        assertFalse(e.isValid());
    }

    @Test
    void invalidWhenIdempotencyKeyMissing() {
        ContentEvent e = new ContentEvent("e", "ARTICLE_PUBLISHED", "ARTICLE_UPDATES",
                null, null, "t", null, null, null, "", null);
        assertFalse(e.isValid());
    }

    @Test
    void invalidWhenTitleMissing() {
        ContentEvent e = new ContentEvent("e", "ARTICLE_PUBLISHED", "ARTICLE_UPDATES",
                null, null, null, null, null, null, "k", null);
        assertFalse(e.isValid());
    }

    @Test
    void allowedEventTypesAccepted() {
        for (String t : new String[]{"ARTICLE_PUBLISHED","ARTICLE_UPDATED","FEATURE_RELEASED","JOB_POSITION_UPDATED"}) {
            assertTrue(ContentEvent.isAllowedEventType(t), t);
        }
        assertFalse(ContentEvent.isAllowedEventType(null));
        assertFalse(ContentEvent.isAllowedEventType("X"));
    }

    @Test
    void allowedTopicsAccepted() {
        for (String t : new String[]{"ARTICLE_UPDATES","FEATURE_UPDATES","JOB_UPDATES"}) {
            assertTrue(ContentEvent.isAllowedTopic(t), t);
        }
        assertFalse(ContentEvent.isAllowedTopic(null));
        assertFalse(ContentEvent.isAllowedTopic("OTHER"));
    }

    @Test
    void audienceDefaultsToSubscribersAndFalseSuppressesDelivery() {
        assertEquals("ALL_SUBSCRIBERS", valid().effectiveAudience());
        ContentEvent suppressed = new ContentEvent("e", null, null, null, 1,
                "ARTICLE_PUBLISHED", "ARTICLE_UPDATES", "BLOG", "1", 2,
                "title", "summary", "/blog/1", null, "key", false,
                "ALL_SUBSCRIBERS", Map.of());
        assertEquals("NONE", suppressed.effectiveAudience());
    }

    @Test
    void commentEventsAreSupportedForWebhookSubscriptions() {
        assertTrue(ContentEvent.isAllowedEventType("COMMENT_CREATED"));
    }
}
