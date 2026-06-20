package site.yuqi.notifications.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.dto.MarkReadRequest;
import site.yuqi.notifications.dto.NotificationListResponse;
import site.yuqi.notifications.service.NotificationService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public ResponseEntity<NotificationListResponse> list(
            @RequestParam("subscriberId") UUID subscriberId,
            @RequestParam("subscriberToken") String subscriberToken,
            @RequestParam(value = "status", required = false) String status) {
        boolean unreadOnly = status != null && status.equalsIgnoreCase("unread");
        return ResponseEntity.ok(service.listForSubscriber(subscriberId, subscriberToken, unreadOnly));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(
            @PathVariable("id") UUID id,
            @Valid @RequestBody MarkReadRequest req) {
        boolean updated = service.markRead(id, req.subscriberId(), req.subscriberToken());
        return ResponseEntity.ok(Map.of("status", "ok", "updated", updated));
    }
}
