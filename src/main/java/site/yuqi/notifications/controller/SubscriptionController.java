package site.yuqi.notifications.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.dto.SubscribeResponse;
import site.yuqi.notifications.dto.UnsubscribeRequest;
import site.yuqi.notifications.dto.UpdatePreferencesRequest;
import site.yuqi.notifications.service.SubscriptionService;

import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService service;

    @Autowired
    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SubscribeResponse> subscribe(@Valid @RequestBody SubscribeRequest req) {
        return ResponseEntity.ok(service.subscribe(req));
    }

    @PatchMapping("/preferences")
    public ResponseEntity<Map<String, Object>> updatePreferences(@Valid @RequestBody UpdatePreferencesRequest req) {
        service.updatePreferences(req);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<Map<String, Object>> unsubscribe(@Valid @RequestBody UnsubscribeRequest req) {
        boolean ok = service.unsubscribeByToken(req.token());
        // Always return 200 to avoid leaking whether a token is valid
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "unsubscribed", ok
        ));
    }
}
