package com.erp.backend.controller;

import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.dto.notification.NotificationResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * S7-08: REST API chuông thông báo trong ứng dụng cho Đại lý và nhân viên.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> getMyNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PageResponse<NotificationResponse> result = notificationService.getMyNotifications(page, size, actor);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/recent")
    public ResponseEntity<List<NotificationResponse>> getRecent(
            @AuthenticationPrincipal UserDetailsImpl actor) {
        List<NotificationResponse> result = notificationService.getTopRecent(actor);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal UserDetailsImpl actor) {
        long count = notificationService.getUnreadCount(actor);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markAsRead(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        NotificationResponse response = notificationService.markAsRead(id, actor);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead(
            @AuthenticationPrincipal UserDetailsImpl actor) {
        int updated = notificationService.markAllAsRead(actor);
        return ResponseEntity.ok(Map.of("success", true, "updatedCount", updated));
    }
}
