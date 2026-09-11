package site.yuqi.notifications.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VisitorAlertEmailTemplateTest {
    private static final String ADMIN_URL = "https://www.yuqi.site/admin/visitors";
    private static final String DETAILS = """
            Rule: Visitor from Texas
            Condition: count >= 1; measured 1
            Area: REGION:US:TX
            Window start: 2026-09-10 20:15:00 UTC
            Window end (exclusive): 2026-09-10 20:20:00 UTC
            Alert detected at: 2026-09-10 20:17:06 UTC
            Incident ID: 17

            Latest matching visits (up to 3; the threshold uses the full window):

            Visit 1
            Occurred at (event time): 2026-09-10 20:16:08 UTC
            Received at (server time): 2026-09-10 20:16:10 UTC
            Approximate location: Dallas, TX, US
            Page: /life-blog/2
            Event: page_view
            Client: desktop / Chrome
            Event ID: 00000000-0000-4000-8000-000000000017

            Location is approximate city/region-level network geolocation, not a street address.
            Visit times remain unchanged during delayed delivery or replay.
            Administrator sign-in is required to view visitor records.
            """;

    @Test
    void emphasizesVisitsWhilePreservingRuleEvidenceAndOriginalTimestamps() throws Exception {
        String html = VisitorAlertEmailTemplate.render("Visitor from Texas", DETAILS, ADMIN_URL);
        assertThat(html).contains("1 matching event in the evaluation window", "INCIDENT #17",
                "Dallas, TX, US", "2026-09-10 20:16:08 UTC", "2026-09-10 20:16:10 UTC",
                "2026-09-10 20:15:00 UTC", "2026-09-10 20:20:00 UTC", "2026-09-10 20:17:06 UTC",
                "Window end (exclusive)", "REGION:US:TX", "count &gt;= 1; measured 1",
                "00000000-0000-4000-8000-000000000017", "/life-blog/2", "desktop / Chrome",
                "Administrator sign-in required.", "not street addresses");
        assertThat(html.indexOf("<h2 style=\"margin:0 0 4px")).isLessThan(html.indexOf("Why this alert fired"));
        assertThat(html).doesNotContain("1 visitor", "<script", "<img", "<details", "background:linear-gradient");
        save("visitor-alert-redesigned.html", html);
    }

    @Test
    void distinguishesFullWindowCountFromTheShownSample() throws Exception {
        String details = DETAILS.replace("measured 1", "measured 42") + "\nVisit 2\n"
                + "Approximate location: Austin, TX, US\nPage: /work-single/" + "long-path-".repeat(30)
                + "\nOccurred at (event time): 2026-09-10 20:16:00 UTC\nEvent ID: " + "1234567890".repeat(20)
                + "\nVisit 3\nApproximate location: Houston, TX, US\nPage: /cv\n"
                + "Occurred at (event time): 2026-09-10 20:15:45 UTC";
        String html = VisitorAlertEmailTemplate.render("Visitor from Texas", details, ADMIN_URL);
        assertThat(html).contains("42 matching events", "Latest 3 shown", "all matching events",
                "Austin, TX, US", "Houston, TX, US", "max-width:480px", "overflow-wrap:anywhere");
        save("visitor-alert-long.html", html);
    }

    @Test
    void missingEvidenceDoesNotInventVisitTimesOrLocations() {
        String html = VisitorAlertEmailTemplate.render("Visitor from Texas", """
                Condition: count >= 1; measured 1
                Window start: 2026-09-10 20:15:00 UTC
                Visit details: No individual matching record is available. The window is not an exact visit time.
                """, ADMIN_URL);
        assertThat(html).contains("No individual matching record is available", "not an exact visit time")
                .doesNotContain("Matching visits</h2>", ">Occurred</span>", "Location unavailable");
    }

    @Test
    void retainsUnrecognizedAndLegacyEvidenceAsEscapedText() {
        String html = VisitorAlertEmailTemplate.render("<script>alert(1)</script>", """
                Region: <img src=x onerror=alert(1)>
                measured 1
                Future note: keep this evidence
                """, ADMIN_URL);
        assertThat(html).contains("&lt;script&gt;", "&lt;img src=x onerror=alert(1)&gt;",
                "Future note", "keep this evidence", "measured 1")
                .doesNotContain("<script>", "<img ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/admin/visitors", "javascript:alert(1)", "https://www.yuqi.site/cv",
            "https://user:password@www.yuqi.site/admin/visitors"})
    void omitsUnsafeOrUnexpectedCallToActionTargets(String url) {
        assertThat(VisitorAlertEmailTemplate.render("Alert", DETAILS, url)).doesNotContain("href=");
    }

    @Test
    void emptyOrZeroCountAlertsDoNotClaimAnActualVisit() {
        assertThat(VisitorAlertEmailTemplate.render(null, null, null)).contains("Visitor activity alert")
                .doesNotContain("Matching visits</h2>", "href=");
        assertThat(VisitorAlertEmailTemplate.render("No visits", "Condition: count <= 0; measured 0", null))
                .contains("0 matching events").doesNotContain("Matching visits</h2>");
    }

    private static void save(String name, String html) throws Exception {
        Path file = Path.of("target/test-artifacts", name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, html);
    }
}
