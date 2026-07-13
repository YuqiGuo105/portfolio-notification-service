package site.yuqi.notifications.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.notifications.dto.AdminNotificationListResponse;
import site.yuqi.notifications.dto.AdminSubscriberItem;
import site.yuqi.notifications.dto.AdminSubscriberListResponse;
import site.yuqi.notifications.dto.UpdateSubscriberStatusRequest;
import site.yuqi.notifications.service.AdminNotificationService;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService service;

    @GetMapping("/subscribers")
    public ResponseEntity<AdminSubscriberListResponse> listSubscribers(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.listSubscribers(status, query, limit, offset));
    }

    @PatchMapping("/subscribers/{id}/status")
    public ResponseEntity<AdminSubscriberItem> updateSubscriberStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSubscriberStatusRequest request) {
        return ResponseEntity.ok(service.updateSubscriberStatus(id, request.status()));
    }

    @GetMapping("/notifications")
    public ResponseEntity<AdminNotificationListResponse> listNotifications(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.listNotifications(limit, offset));
    }
}
