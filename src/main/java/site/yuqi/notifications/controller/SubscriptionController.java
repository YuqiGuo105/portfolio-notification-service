package site.yuqi.notifications.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.dto.SubscribeRequest;
import site.yuqi.notifications.dto.SubscribeResponse;
import site.yuqi.notifications.dto.UnsubscribeRequest;
import site.yuqi.notifications.dto.UnsubscribeVerificationRequest;
import site.yuqi.notifications.dto.UnsubscribeVerificationResponse;
import site.yuqi.notifications.dto.ConfirmUnsubscribeRequest;
import site.yuqi.notifications.dto.ConfirmUnsubscribeResponse;
import site.yuqi.notifications.dto.UpdatePreferencesRequest;
import site.yuqi.notifications.service.SubscriptionService;
import site.yuqi.notifications.service.UnsubscribeVerificationService;

import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService service;
    private final UnsubscribeVerificationService unsubscribeVerificationService;

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

    @PostMapping("/unsubscribe-verification")
    public ResponseEntity<UnsubscribeVerificationResponse> requestUnsubscribeVerification(
            @Valid @RequestBody UnsubscribeVerificationRequest req) {
        return ResponseEntity.accepted().body(unsubscribeVerificationService.requestCode(req.email()));
    }

    @PostMapping("/unsubscribe/confirm")
    public ResponseEntity<ConfirmUnsubscribeResponse> confirmUnsubscribe(
            @Valid @RequestBody ConfirmUnsubscribeRequest req) {
        return ResponseEntity.ok(unsubscribeVerificationService.confirm(
                req.verificationId(), req.verificationCode()));
    }
}
