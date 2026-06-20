package site.yuqi.notifications.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.dto.HealthResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final List<String> REQUIRED_TABLES = List.of(
            "subscribers",
            "subscription_preferences",
            "content_event_audit",
            "notifications",
            "notification_recipients"
    );

    private final JdbcTemplate jdbc;

    @Autowired
    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/notification")
    public ResponseEntity<HealthResponse> health() {
        Map<String, Object> details = new LinkedHashMap<>();

        boolean dbUp;
        try {
            jdbc.queryForObject("select 1", Integer.class);
            dbUp = true;
        } catch (Exception e) {
            dbUp = false;
            details.put("db_error", e.getClass().getSimpleName());
        }
        details.put("db", dbUp ? "up" : "down");

        Map<String, Boolean> tables = new LinkedHashMap<>();
        boolean allPresent = true;
        if (dbUp) {
            for (String t : REQUIRED_TABLES) {
                boolean exists = tableExists(t);
                tables.put(t, exists);
                if (!exists) allPresent = false;
            }
        } else {
            allPresent = false;
        }
        details.put("tables", tables);

        String overall = (dbUp && allPresent) ? "UP" : "DOWN";
        HealthResponse body = new HealthResponse(overall, details);
        return ResponseEntity.status(overall.equals("UP") ? 200 : 503).body(body);
    }

    private boolean tableExists(String name) {
        try {
            Integer cnt = jdbc.queryForObject(
                    "select count(*) from information_schema.tables " +
                            " where table_schema = 'public' and table_name = ?",
                    Integer.class, name);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
