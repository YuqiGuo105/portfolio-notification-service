package site.yuqi.notifications.service;

import jakarta.mail.internet.MimeMessage;
import jakarta.annotation.PreDestroy;
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
import site.yuqi.notifications.operations.OperationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class EmailDispatchService {

    private final NotificationRecipientRepository recipientRepo;
    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;
    private final EmailPreviewService previewService;
    private final OperationEventPublisher operations;
    private final String fromAddress;
    private final int batchSize;
    private final int maxRetry;
    private final ThreadPoolExecutor deliveryExecutor;
    private final long batchTimeoutSeconds;

    public EmailDispatchService(NotificationRecipientRepository recipientRepo,
                                JdbcTemplate jdbc,
                                JavaMailSender mailSender,
                                EmailPreviewService previewService,
                                OperationEventPublisher operations,
                                @Value("${portfolio.email.from:noreply@yuqi.site}") String fromAddress,
                                @Value("${portfolio.email.dispatch.batch-size:20}") int batchSize,
                                @Value("${portfolio.email.dispatch.max-retry:5}") int maxRetry,
                                @Value("${portfolio.email.dispatch.concurrency:4}") int concurrency,
                                @Value("${portfolio.email.dispatch.queue-capacity:20}") int queueCapacity,
                                @Value("${portfolio.email.dispatch.batch-timeout-seconds:90}") long batchTimeoutSeconds) {
        this.recipientRepo = recipientRepo;
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.previewService = previewService;
        this.operations = operations;
        this.fromAddress = fromAddress;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
        this.batchTimeoutSeconds = Math.max(10, batchTimeoutSeconds);
        AtomicInteger sequence = new AtomicInteger();
        int threads = Math.max(1, concurrency);
        this.deliveryExecutor = new ThreadPoolExecutor(
                threads,
                threads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                task -> {
                    Thread thread = new Thread(task, "email-delivery-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.deliveryExecutor.allowCoreThreadTimeOut(true);
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

        CompletableFuture<?>[] deliveries = claimed.stream()
                .map(row -> CompletableFuture.runAsync(() -> dispatchSafely(row), deliveryExecutor))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(deliveries).get(batchTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception error) {
            // Unfinished rows remain leased and are reclaimed after lease expiry.
            log.warn("{\"event\":\"dispatch_batch_incomplete\",\"size\":{},\"err\":\"{}\"}",
                    claimed.size(), error.getMessage());
        }
        return claimed.size();
    }

    private void dispatchSafely(NotificationRecipientRow row) {
        try {
            dispatchOne(row);
        } catch (Exception e) {
            log.error("{\"event\":\"dispatch_unexpected_error\",\"recipientId\":\"{}\",\"err\":\"{}\"}",
                    row.id(), e.getMessage());
            recordFailure(row, e.getMessage(), nextBackoff(row.retryCount()));
        }
    }

    @PreDestroy
    void closeExecutor() {
        deliveryExecutor.shutdown();
    }

    private void dispatchOne(NotificationRecipientRow row) {
        long startedNanos = System.nanoTime();
        // 1. Check subscriber status & fetch email
        Map<String, Object> subRow;
        try {
            if ("ADMIN_ALERTS".equals(row.notificationTopic())) {
                subRow = jdbc.queryForMap("""
                        select s.email,
                               case when a.enabled then 'ACTIVE' else 'DISABLED' end as status
                          from public.subscribers s
                          join public.admin_alert_subscriptions a on a.subscriber_id = s.id
                         where s.id = ?
                        """, row.subscriberId());
            } else {
                subRow = jdbc.queryForMap(
                        "select email, status from public.subscribers where id = ?",
                        row.subscriberId());
            }
        } catch (DataAccessException e) {
            recordFailure(row, "subscriber lookup failed: " + e.getMessage(),
                    nextBackoff(row.retryCount()));
            publishDelivery(row, "notification.email.delivery_failed", "FAILED", startedNanos,
                    Map.of("errorType", e.getClass().getSimpleName()));
            return;
        }

        String status = (String) subRow.get("status");
        String email = (String) subRow.get("email");

        if (!"ACTIVE".equals(status)) {
            if (!recipientRepo.markSkipped(row.id(), row.nextRetryAt(), "subscriber status=" + status)) {
                log.warn("{\"event\":\"stale_dispatch_result\",\"recipientId\":\"{}\",\"outcome\":\"SKIPPED\"}",
                        row.id());
            }
            log.info("{\"event\":\"skip_inactive\",\"recipientId\":\"{}\",\"subscriberStatus\":\"{}\"}",
                    row.id(), status);
            publishDelivery(row, "notification.email.skipped", "SUCCEEDED", startedNanos,
                    Map.of("reason", "subscriber_not_active"));
            return;
        }

        // 2. Send (multipart: HTML + plain-text fallback)
        boolean dispatchStarted=false;
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject(buildSubject(row));
            helper.setText(buildPlainBody(row), buildHtmlBody(row));
            if(!recipientRepo.markSending(row.id(),row.nextRetryAt())) return;
            dispatchStarted=true;
            mailSender.send(mime);

            if (!recipientRepo.markSent(row.id(), row.nextRetryAt())) {
                log.warn("{\"event\":\"stale_dispatch_result\",\"recipientId\":\"{}\",\"outcome\":\"SENT\"}",
                        row.id());
                return;
            }
            log.info("{\"event\":\"email_sent\",\"recipientId\":\"{}\",\"subscriberId\":\"{}\"}",
                    row.id(), row.subscriberId());
            publishDelivery(row, "notification.email.delivered", "SUCCEEDED", startedNanos,
                    Map.of("channel", "EMAIL"));
        } catch (MailException | jakarta.mail.MessagingException e) {
            if(dispatchStarted) {
                recipientRepo.markUnknown(row.id(),row.nextRetryAt());
                publishDelivery(row,"notification.email.outcome_unknown","UNKNOWN",startedNanos,
                        Map.of("errorType",e.getClass().getSimpleName(),"safeToRetry",false));
                return;
            }
            int backoff = nextBackoff(row.retryCount());
            recordFailure(row, e.getMessage(), backoff);
            log.warn("{\"event\":\"email_failed\",\"recipientId\":\"{}\",\"backoffSec\":{},\"err\":\"{}\"}",
                    row.id(), backoff, e.getMessage());
            publishDelivery(row, "notification.email.delivery_failed", "FAILED", startedNanos,
                    Map.of("errorType", e.getClass().getSimpleName(), "backoffSeconds", backoff));
        }
    }

    private void recordFailure(NotificationRecipientRow row, String error, int backoffSeconds) {
        if (!recipientRepo.markFailed(row.id(), row.nextRetryAt(), error, backoffSeconds)) {
            log.warn("{\"event\":\"stale_dispatch_result\",\"recipientId\":\"{}\",\"outcome\":\"FAILED\"}",
                    row.id());
        }
    }

    private void publishDelivery(NotificationRecipientRow row, String eventType, String status,
                                 long startedNanos, Map<String, Object> attributes) {
        operations.publish(row.traceId(), row.correlationId(), row.causationId(), row.idempotencyKey(),
                eventType, status, row.sourceType(), row.sourceId(), row.sourceVersion(),
                row.retryCount() + 1, Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000),
                attributes);
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
            case "ADMIN_ALERTS"     -> "Admin Alert";
            default                 -> "Update";
        };
        return "[yuqi.site] " + prefix + ": " + safe(row.notificationTitle());
    }

    // ── Plain-text fallback ───────────────────────────────────────────────────

    private String buildPlainBody(NotificationRecipientRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(row.notificationTitle())).append("\n\n");
        String preview = previewService.body(row.notificationTopic(), row.notificationBody());
        if (notBlank(preview)) {
            sb.append(preview).append("\n\n");
        }
        if (notBlank(row.notificationUrl())) {
            sb.append("ADMIN_ALERTS".equals(row.notificationTopic())
                            ? "View visitor records (administrator sign-in required): " : "Read more: ")
                    .append(row.notificationUrl()).append("\n\n");
        }
        sb.append("---\n");
        if ("ADMIN_ALERTS".equals(row.notificationTopic())) {
            sb.append("Private operational alert for a yuqi.site administrator.\n");
        } else {
            sb.append("You are receiving this email because you subscribed to ")
                    .append(topicLabel(row.notificationTopic()))
                    .append(" on yuqi.site.\n");
        }
        sb.append("Visit: https://www.yuqi.site\n");
        return sb.toString();
    }

    // ── HTML template ─────────────────────────────────────────────────────────

    private String buildHtmlBody(NotificationRecipientRow row) {
        if ("ADMIN_ALERTS".equals(row.notificationTopic())) {
            return VisitorAlertEmailTemplate.render(row.notificationTitle(),
                    previewService.body(row.notificationTopic(), row.notificationBody()), row.notificationUrl());
        }
        String title       = escHtml(safe(row.notificationTitle()));
        String message     = previewService.body(row.notificationTopic(), row.notificationBody());
        String body        = escHtml(message);
        String url         = safe(row.notificationUrl());
        String topicBadge  = topicLabel(row.notificationTopic());
        String topicColor  = topicAccentColor(row.notificationTopic());
        String topicIcon   = topicIcon(row.notificationTopic());
        String footerReason = "You are receiving this email because you subscribed to <strong style=\"color:#94a3b8;\">"
                    + topicBadge + "</strong> on <a href=\"https://www.yuqi.site\" style=\"color:"
                    + topicColor + ";text-decoration:none;\">yuqi.site</a>.";

        String ctaBlock = notBlank(url) ? """
                <tr>
                  <td align="center" style="padding:28px 0 8px;">
                    <a href="%s"
                       style="display:inline-block;background:%s;color:#ffffff;
                              text-decoration:none;font-size:15px;font-weight:600;
                              letter-spacing:0.4px;padding:14px 36px;border-radius:8px;">
                      %s &rarr;
                    </a>
                  </td>
                </tr>
                """.formatted(escHtml(url), topicColor, "Read the Full Post") : "";

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
                                %s
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
                        footerReason,    // private admin or public subscription reason
                        java.time.Year.now().getValue()  // year
                );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String topicLabel(String topic) {
        return switch (safe(topic)) {
            case "ARTICLE_UPDATES"  -> "Articles &amp; Blog";
            case "FEATURE_UPDATES"  -> "Projects &amp; Features";
            case "JOB_UPDATES"      -> "Career Updates";
            case "ADMIN_ALERTS"     -> "Admin Operations";
            default                 -> safe(topic);
        };
    }

    private static String topicAccentColor(String topic) {
        return switch (safe(topic)) {
            case "ARTICLE_UPDATES"  -> "#6366f1";   // indigo
            case "FEATURE_UPDATES"  -> "#10b981";   // emerald
            case "JOB_UPDATES"      -> "#f59e0b";   // amber
            case "ADMIN_ALERTS"     -> "#ef4444";   // red
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
