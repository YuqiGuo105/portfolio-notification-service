package site.yuqi.notifications.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import site.yuqi.notifications.domain.NotificationRecipientRow;
import site.yuqi.notifications.repository.NotificationRecipientRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EmailDispatchService {

    private final NotificationRecipientRepository recipientRepo;
    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final int batchSize;
    private final int maxRetry;

    public EmailDispatchService(NotificationRecipientRepository recipientRepo,
                                JdbcTemplate jdbc,
                                JavaMailSender mailSender,
                                @Value("${portfolio.email.from:noreply@yuqi.site}") String fromAddress,
                                @Value("${portfolio.email.dispatch.batch-size:20}") int batchSize,
                                @Value("${portfolio.email.dispatch.max-retry:5}") int maxRetry) {
        this.recipientRepo = recipientRepo;
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
    }

    /**
     * Run a single dispatch pass. Returns the number of rows attempted.
     */
    public int dispatchOnce() {
        List<NotificationRecipientRow> claimed;
        try {
            claimed = recipientRepo.claimEmailBatch(batchSize, maxRetry);
        } catch (DataAccessException dbErr) {
            log.error("{\"event\":\"claim_failed\",\"err\":\"{}\"}", dbErr.getMessage());
            return 0;
        }
        if (claimed.isEmpty()) return 0;

        log.info("{\"event\":\"dispatch_batch\",\"size\":{}}", claimed.size());

        int attempted = 0;
        for (NotificationRecipientRow row : claimed) {
            attempted++;
            try {
                dispatchOne(row);
            } catch (Exception e) {
                log.error("{\"event\":\"dispatch_unexpected_error\",\"recipientId\":\"{}\",\"err\":\"{}\"}",
                        row.id(), e.getMessage());
                recipientRepo.markFailed(row.id(), e.getMessage(), nextBackoff(row.retryCount()));
            }
        }
        return attempted;
    }

    private void dispatchOne(NotificationRecipientRow row) {
        // 1. Check subscriber status & fetch email
        Map<String, Object> subRow;
        try {
            subRow = jdbc.queryForMap(
                    "select email, status from public.subscribers where id = ?",
                    row.subscriberId());
        } catch (DataAccessException e) {
            recipientRepo.markFailed(row.id(), "subscriber lookup failed: " + e.getMessage(),
                    nextBackoff(row.retryCount()));
            return;
        }

        String status = (String) subRow.get("status");
        String email = (String) subRow.get("email");

        if (!"ACTIVE".equals(status)) {
            recipientRepo.markSkipped(row.id(), "subscriber status=" + status);
            log.info("{\"event\":\"skip_inactive\",\"recipientId\":\"{}\",\"subscriberStatus\":\"{}\"}",
                    row.id(), status);
            return;
        }

        // 2. Send (multipart: HTML + plain-text fallback)
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject(buildSubject(row));
            helper.setText(buildPlainBody(row), buildHtmlBody(row));
            mailSender.send(mime);

            recipientRepo.markSent(row.id());
            log.info("{\"event\":\"email_sent\",\"recipientId\":\"{}\",\"to\":\"{}\"}", row.id(), email);
        } catch (MailException | jakarta.mail.MessagingException e) {
            int backoff = nextBackoff(row.retryCount());
            recipientRepo.markFailed(row.id(), e.getMessage(), backoff);
            log.warn("{\"event\":\"email_failed\",\"recipientId\":\"{}\",\"backoffSec\":{},\"err\":\"{}\"}",
                    row.id(), backoff, e.getMessage());
        }
    }

    static int nextBackoff(int currentRetryCount) {
        // 60s, 120s, 240s, 480s, 960s (cap)
        int seconds = 60 * (int) Math.pow(2, Math.min(currentRetryCount, 4));
        return Math.min(seconds, 60 * 60);
    }

    // ── Subject ──────────────────────────────────────────────────────────────

    private String buildSubject(NotificationRecipientRow row) {
        String prefix = switch (safe(row.notificationTopic())) {
            case "ARTICLE_UPDATES"  -> "New Article";
            case "FEATURE_UPDATES"  -> "New Feature";
            case "JOB_UPDATES"      -> "Career Update";
            default                 -> "Update";
        };
        return "[yuqi.site] " + prefix + ": " + safe(row.notificationTitle());
    }

    // ── Plain-text fallback ───────────────────────────────────────────────────

    private String buildPlainBody(NotificationRecipientRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(row.notificationTitle())).append("\n\n");
        if (notBlank(row.notificationBody())) {
            sb.append(row.notificationBody()).append("\n\n");
        }
        if (notBlank(row.notificationUrl())) {
            sb.append("Read more: ").append(row.notificationUrl()).append("\n\n");
        }
        sb.append("---\n");
        sb.append("You are receiving this email because you subscribed to ")
                .append(topicLabel(row.notificationTopic()))
                .append(" on yuqi.site.\n");
        sb.append("Visit: https://www.yuqi.site\n");
        return sb.toString();
    }

    // ── HTML template ─────────────────────────────────────────────────────────

    private String buildHtmlBody(NotificationRecipientRow row) {
        String title       = escHtml(safe(row.notificationTitle()));
        String body        = escHtml(safe(row.notificationBody()));
        String url         = safe(row.notificationUrl());
        String topicBadge  = topicLabel(row.notificationTopic());
        String topicColor  = topicAccentColor(row.notificationTopic());
        String topicIcon   = topicIcon(row.notificationTopic());

        String ctaBlock = notBlank(url) ? """
                <tr>
                  <td align="center" style="padding:28px 0 8px;">
                    <a href="%s"
                       style="display:inline-block;background:%s;color:#ffffff;
                              text-decoration:none;font-size:15px;font-weight:600;
                              letter-spacing:0.4px;padding:14px 36px;border-radius:8px;">
                      Read the Full Post &rarr;
                    </a>
                  </td>
                </tr>
                """.formatted(escHtml(url), topicColor) : "";

        String bodyBlock = notBlank(body) ? """
                <tr>
                  <td style="padding:0 0 20px;color:#64748b;font-size:16px;
                             line-height:1.7;font-style:italic;">
                    %s
                  </td>
                </tr>
                """.formatted(body) : "";

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8"/>
                  <meta name="viewport" content="width=device-width,initial-scale=1"/>
                  <meta name="color-scheme" content="light dark"/>
                  <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background:#0f172a;font-family:
                             -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">

                  <!-- wrapper -->
                  <table width="100%%" cellpadding="0" cellspacing="0" border="0"
                         style="background:#0f172a;padding:40px 16px;">
                    <tr>
                      <td align="center">

                        <!-- card -->
                        <table width="600" cellpadding="0" cellspacing="0" border="0"
                               style="max-width:600px;width:100%%;background:#1e293b;
                                      border-radius:16px;overflow:hidden;
                                      box-shadow:0 20px 60px rgba(0,0,0,0.5);">

                          <!-- top accent bar -->
                          <tr>
                            <td style="height:4px;background:linear-gradient(90deg,%s,#818cf8);"></td>
                          </tr>

                          <!-- header -->
                          <tr>
                            <td style="padding:36px 40px 24px;">
                              <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                                <tr>
                                  <td>
                                    <a href="https://www.yuqi.site"
                                       style="text-decoration:none;font-size:22px;font-weight:700;
                                              color:#f1f5f9;letter-spacing:-0.5px;">
                                      yuqi<span style="color:%s;">.site</span>
                                    </a>
                                  </td>
                                  <td align="right">
                                    <span style="display:inline-block;background:%s22;color:%s;
                                                 font-size:12px;font-weight:600;letter-spacing:0.6px;
                                                 padding:4px 12px;border-radius:20px;
                                                 border:1px solid %s44;">
                                      %s &nbsp;%s
                                    </span>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>

                          <!-- divider -->
                          <tr>
                            <td style="padding:0 40px;">
                              <hr style="border:none;border-top:1px solid #334155;margin:0;"/>
                            </td>
                          </tr>

                          <!-- body -->
                          <tr>
                            <td style="padding:36px 40px 12px;">
                              <table width="100%%" cellpadding="0" cellspacing="0" border="0">

                                <!-- title -->
                                <tr>
                                  <td style="padding:0 0 16px;color:#f1f5f9;font-size:24px;
                                             font-weight:700;line-height:1.3;letter-spacing:-0.3px;">
                                    %s
                                  </td>
                                </tr>

                                <!-- summary -->
                                %s

                                <!-- CTA -->
                                %s

                              </table>
                            </td>
                          </tr>

                          <!-- footer -->
                          <tr>
                            <td style="padding:0 40px;">
                              <hr style="border:none;border-top:1px solid #334155;margin:0;"/>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:24px 40px 36px;">
                              <p style="margin:0 0 8px;font-size:13px;color:#475569;line-height:1.6;">
                                You are receiving this email because you subscribed to
                                <strong style="color:#94a3b8;">%s</strong> on
                                <a href="https://www.yuqi.site"
                                   style="color:%s;text-decoration:none;">yuqi.site</a>.
                              </p>
                              <p style="margin:0;font-size:12px;color:#334155;">
                                &copy; 2025&ndash;%d Yuqi Guo &middot; Vancouver, BC
                              </p>
                            </td>
                          </tr>

                        </table>
                        <!-- /card -->

                      </td>
                    </tr>
                  </table>
                  <!-- /wrapper -->

                </body>
                </html>
                """.formatted(
                        title,           // <title>
                        topicColor,      // accent bar gradient start
                        topicColor,      // .site color
                        topicColor,      // badge bg
                        topicColor,      // badge text
                        topicColor,      // badge border
                        topicIcon, topicBadge,  // badge content
                        title,           // heading
                        bodyBlock,       // summary paragraph
                        ctaBlock,        // CTA button
                        topicBadge,      // footer topic name
                        topicColor,      // footer link color
                        java.time.Year.now().getValue()  // year
                );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String topicLabel(String topic) {
        return switch (safe(topic)) {
            case "ARTICLE_UPDATES"  -> "Articles &amp; Blog";
            case "FEATURE_UPDATES"  -> "Projects &amp; Features";
            case "JOB_UPDATES"      -> "Career Updates";
            default                 -> safe(topic);
        };
    }

    private static String topicAccentColor(String topic) {
        return switch (safe(topic)) {
            case "ARTICLE_UPDATES"  -> "#6366f1";   // indigo
            case "FEATURE_UPDATES"  -> "#10b981";   // emerald
            case "JOB_UPDATES"      -> "#f59e0b";   // amber
            default                 -> "#6366f1";
        };
    }

    private static String topicIcon(String topic) {
        return switch (safe(topic)) {
            case "ARTICLE_UPDATES"  -> "✍️";
            case "FEATURE_UPDATES"  -> "🚀";
            case "JOB_UPDATES"      -> "💼";
            default                 -> "📬";
        };
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String safe(String s) { return s == null ? "" : s; }
}
