package site.yuqi.notifications.service;

import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Presentation adapter for the immutable, version-compatible alert evidence text. */
final class VisitorAlertEmailTemplate {
    private static final Pattern VISIT = Pattern.compile("Visit [1-9][0-9]*");
    private static final Pattern COUNT = Pattern.compile("count (?:>=|<=|>|<|=|==) [0-9]+; measured ([0-9]+)");
    private static final Set<String> VISIT_PRIMARY = Set.of(
            "Approximate location", "Occurred at (event time)", "Page", "Event", "Client");
    private static final Set<String> STANDARD_NOTES = Set.of(
            "Latest matching visits (up to 3; the threshold uses the full window):",
            "Location is approximate city/region-level network geolocation, not a street address.",
            "Visit times remain unchanged during delayed delivery or replay.",
            "Administrator sign-in is required to view visitor records.");

    private VisitorAlertEmailTemplate() {}

    static String render(String title, String message, String url) {
        Evidence evidence = parse(message);
        String heading = blank(title) ? "Visitor activity alert" : title;
        String condition = value(evidence.fields(), "Condition");
        var count = COUNT.matcher(condition);
        String summary = count.matches()
                ? count.group(1) + ("1".equals(count.group(1)) ? " matching event" : " matching events")
                    + " in the evaluation window"
                : "A visitor activity rule was triggered.";
        String incident = value(evidence.fields(), "Incident ID");
        String preheader = evidence.visits().isEmpty() ? summary
                : join(value(evidence.visits().getFirst().fields(), "Approximate location"),
                    value(evidence.visits().getFirst().fields(), "Page")) + " | " + summary;
        StringBuilder visits = new StringBuilder();
        if (!evidence.visits().isEmpty()) {
            visits.append("<h2 style=\"margin:0 0 4px;font-size:16px;color:#172b29;\">Matching visits</h2>")
                    .append("<p style=\"margin:0 0 20px;font-size:13px;color:#596762;line-height:1.6;\">Latest ")
                    .append(evidence.visits().size())
                    .append(" shown. The rule counts all matching events in the window.</p>");
            for (Visit visit : evidence.visits()) visits.append(visitHtml(visit));
        }
        String notes = evidence.notes().stream().map(note ->
                "<p style=\"margin:8px 0;color:#596762;font-size:14px;line-height:1.6;\">" + esc(note) + "</p>")
                .collect(java.util.stream.Collectors.joining());
        List<Field> ruleFields = evidence.fields().stream()
                .filter(field -> !Set.of("Rule", "Incident ID").contains(field.label())).toList();
        // Preserve a mismatching/legacy rule name instead of silently hiding evidence.
        String rule = value(evidence.fields(), "Rule");
        if (!blank(rule) && !rule.equals(heading)) {
            ruleFields = new ArrayList<>(ruleFields);
            ruleFields.addFirst(new Field("Rule", rule));
        }
        String details = ruleFields.isEmpty() ? "" : """
                <h2 style="margin:0 0 12px;font-size:16px;color:#172b29;">Why this alert fired</h2>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                       style="width:100%%;table-layout:fixed;border-collapse:collapse;">%s</table>
                """.formatted(ruleFields.stream().map(VisitorAlertEmailTemplate::fieldHtml)
                        .collect(java.util.stream.Collectors.joining()));
        String cta = safeAdminUrl(url) ? """
                <p style="margin:24px 0 8px;">
                  <a href="%s" style="display:inline-block;background:#087f72;color:#ffffff;text-decoration:none;
                     border:1px solid #087f72;border-radius:6px;padding:13px 22px;font-size:15px;font-weight:700;
                     line-height:22px;">View Visitor Records &rarr;</a>
                </p>
                <p style="margin:0;font-size:12px;line-height:1.6;color:#596762;">Administrator sign-in required.</p>
                """.formatted(esc(url)) : "";

        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8"/>
                <meta name="viewport" content="width=device-width,initial-scale=1"/>
                <meta name="color-scheme" content="light"/><meta name="supported-color-schemes" content="light"/>
                <title>%s</title>
                <style>
                  body,table,td,p,a,h1,h2 { letter-spacing:0; }
                  @media only screen and (max-width:480px) {
                    .outer { padding:12px 8px !important; }
                    .inset { padding-left:20px !important;padding-right:20px !important; }
                    .detail-label,.detail-value { display:block !important;width:auto !important; }
                    .detail-label { padding-bottom:0 !important;border-bottom:0 !important; }
                    .detail-value { padding-top:3px !important; }
                  }
                </style></head>
                <body style="margin:0;padding:0;background:#f1f4f3;color:#172b29;font-family:Arial,Helvetica,sans-serif;">
                  <div style="display:none!important;max-height:0;max-width:0;overflow:hidden;opacity:0;mso-hide:all;">%s</div>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f4f3;">
                  <tr><td class="outer" align="center" style="padding:32px 16px;">
                    <table role="presentation" width="640" cellpadding="0" cellspacing="0"
                           style="width:100%%;max-width:640px;table-layout:fixed;background:#ffffff;border:1px solid #dbe3df;border-radius:8px;">
                      <tr><td class="inset" style="padding:24px 32px;border-bottom:1px solid #e3e9e6;">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="table-layout:fixed;">
                          <tr><td style="font-size:20px;font-weight:700;color:#172b29;">yuqi<span style="color:#087f72;">.site</span></td>
                          <td align="right" style="font-size:12px;line-height:1.5;color:#596762;">ADMIN ALERT</td></tr>
                        </table>
                      </td></tr>
                      <tr><td class="inset" style="padding:28px 32px;overflow-wrap:anywhere;word-break:break-word;">
                        <p style="margin:0 0 10px;font-size:12px;font-weight:700;color:#087f72;">VISITOR ACTIVITY%s</p>
                        <h1 style="margin:0 0 10px;font-size:28px;line-height:1.25;color:#172b29;">%s</h1>
                        <p style="margin:0;font-size:15px;line-height:1.6;color:#596762;">%s</p>
                      </td></tr>
                      <tr><td class="inset" style="padding:0 32px 28px;overflow-wrap:anywhere;word-break:break-word;">%s%s%s</td></tr>
                      <tr><td class="inset" style="padding:24px 32px;border-top:1px solid #e3e9e6;background:#f8faf9;
                                                  overflow-wrap:anywhere;word-break:break-word;">%s</td></tr>
                      <tr><td class="inset" style="padding:24px 32px;border-top:1px solid #e3e9e6;">
                        <p style="margin:0 0 8px;font-size:12px;line-height:1.7;color:#596762;">
                          Locations are approximate city/region-level network estimates, not street addresses.
                          Event times stay unchanged if delivery is delayed or replayed.
                        </p>
                        <p style="margin:0;font-size:12px;line-height:1.7;color:#596762;">
                          Private operational alert. Administrator sign-in is required to view visitor records.<br/>
                          &copy; %d Yuqi Guo &middot; yuqi.site
                        </p>
                      </td></tr>
                    </table>
                  </td></tr></table>
                </body></html>
                """.formatted(esc(heading), esc(preheader), blank(incident) ? "" : " &middot; INCIDENT #" + esc(incident),
                esc(heading), esc(summary), visits, notes, cta, details, Year.now().getValue());
    }

    private static String visitHtml(Visit visit) {
        String location = value(visit.fields(), "Approximate location");
        String time = value(visit.fields(), "Occurred at (event time)");
        String page = value(visit.fields(), "Page");
        String client = join(value(visit.fields(), "Client"), value(visit.fields(), "Event"));
        StringBuilder html = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"width:100%;table-layout:fixed;border-collapse:collapse;margin:0 0 20px;border-left:3px solid #16a18d;\"><tr>"
                + "<td style=\"padding:2px 0 2px 18px;overflow-wrap:anywhere;word-break:break-word;\">");
        html.append("<p style=\"margin:0 0 6px;font-size:12px;color:#596762;\">").append(esc(visit.label())).append("</p>");
        html.append("<p style=\"margin:0 0 12px;font-size:22px;line-height:1.3;font-weight:700;color:#172b29;\">")
                .append(esc(blank(location) ? "Location unavailable" : location)).append("</p>");
        if (!blank(time)) html.append("<p style=\"margin:0 0 10px;font-size:15px;line-height:1.6;color:#172b29;\">")
                .append("<span style=\"color:#596762;\">Occurred</span> &nbsp;").append(esc(time)).append("</p>");
        if (!blank(page)) html.append("<p style=\"margin:0 0 10px;font-size:15px;line-height:1.6;color:#172b29;\">")
                .append("<span style=\"color:#596762;\">Page</span> &nbsp;<strong>").append(esc(page)).append("</strong></p>");
        if (!blank(client)) html.append("<p style=\"margin:0 0 14px;font-size:14px;line-height:1.6;color:#596762;\">")
                .append(esc(client)).append("</p>");
        for (Field field : visit.fields()) {
            if (VISIT_PRIMARY.contains(field.label())) continue;
            html.append("<p style=\"margin:4px 0;font-size:12px;line-height:1.6;color:#596762;overflow-wrap:anywhere;word-break:break-all;\">")
                    .append(esc("Received at (server time)".equals(field.label()) ? "Received" : field.label()))
                    .append(": ").append(esc(field.value())).append("</p>");
        }
        return html.append("</td></tr></table>").toString();
    }

    private static String fieldHtml(Field field) {
        return """
                <tr><td class="detail-label" width="160" valign="top" style="width:160px;padding:9px 16px 9px 0;
                     font-size:13px;line-height:1.6;color:#596762;border-bottom:1px solid #e3e9e6;">%s</td>
                <td class="detail-value" valign="top" style="padding:9px 0;font-size:13px;line-height:1.6;color:#263e37;
                    border-bottom:1px solid #e3e9e6;overflow-wrap:anywhere;word-break:break-word;">%s</td></tr>
                """.formatted(esc(field.label()), esc(field.value()));
    }

    private static Evidence parse(String message) {
        List<Field> fields = new ArrayList<>();
        List<Visit> visits = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Field> current = fields;
        for (String raw : (message == null ? "" : message).split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || STANDARD_NOTES.contains(line)) continue;
            if (VISIT.matcher(line).matches()) {
                current = new ArrayList<>();
                visits.add(new Visit(line, current));
                continue;
            }
            int separator = line.indexOf(": ");
            if (separator > 0 && separator <= 45) {
                current.add(new Field(line.substring(0, separator), line.substring(separator + 2)));
            } else {
                notes.add(line);
            }
        }
        return new Evidence(fields, visits, notes);
    }

    private static String value(List<Field> fields, String label) {
        return fields.stream().filter(field -> field.label().equals(label)).map(Field::value).findFirst().orElse("");
    }

    private static boolean safeAdminUrl(String url) {
        if (blank(url)) return false;
        try {
            URI uri = URI.create(url);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null && "/admin/visitors".equals(uri.getPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String join(String first, String second) {
        return blank(first) ? second : blank(second) ? first : first + " / " + second;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String esc(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value); }
    private record Field(String label, String value) {}
    private record Visit(String label, List<Field> fields) {}
    private record Evidence(List<Field> fields, List<Visit> visits, List<String> notes) {}
}
