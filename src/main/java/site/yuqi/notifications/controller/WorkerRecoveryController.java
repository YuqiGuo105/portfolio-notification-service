package site.yuqi.notifications.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.service.EmailDispatchService;
import site.yuqi.notifications.service.WebPushService;

import java.util.Map;

/** Request-scoped recovery entry point for the scale-to-zero Kafka consumer. */
@RestController
@RequestMapping("/api/internal/workers")
@RequiredArgsConstructor
public class WorkerRecoveryController {

    private final EmailDispatchService emails;
    private final WebPushService push;

    @PostMapping("/drain")
    public Map<String, Object> drain(@RequestParam(defaultValue = "10000") long maxWaitMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1000, Math.min(maxWaitMs, 30000));
        int attempted = 0;
        int pushAttempts = 0;
        do {
            // One bounded provider call per pass keeps scale-to-zero recovery finite.
            pushAttempts += push.dispatchOnce(1);
            attempted += emails.dispatchOnce();
            Thread.sleep(500);
        } while (System.currentTimeMillis() < deadline);
        return Map.of("status", "completed", "emailAttempts", attempted, "pushAttempts", pushAttempts);
    }
}
