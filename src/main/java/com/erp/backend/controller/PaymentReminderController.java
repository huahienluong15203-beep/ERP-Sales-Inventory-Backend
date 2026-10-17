package com.erp.backend.controller;

import com.erp.backend.dto.payment.PaymentReminderLogResponse;
import com.erp.backend.dto.payment.PaymentReminderRunResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.PaymentReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * S7-08 / SCRUM-262: Quản lý và kích hoạt tác vụ gửi Email/Notify chuông nhắc nợ cho Đại lý.
 */
@RestController
@RequestMapping("/api/payments/reminders")
@RequiredArgsConstructor
public class PaymentReminderController {

    private final PaymentReminderService reminderService;

    /**
     * Kích hoạt quét nhắc nợ thủ công hoặc phục vụ test giả lập ngày chạy cronjob.
     */
    @PostMapping("/trigger")
    @PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<PaymentReminderRunResponse> trigger(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate simulatedDate) {
        PaymentReminderRunResponse response = reminderService.scanAndSendReminders(simulatedDate);
        return ResponseEntity.ok(response);
    }

    /**
     * Xem nhật ký gửi nhắc nợ theo ngày.
     */
    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<List<PaymentReminderLogResponse>> getLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<PaymentReminderLogResponse> logs = reminderService.getLogsByDate(date);
        return ResponseEntity.ok(logs);
    }

    /**
     * Xem lịch sử nhắc nợ của một hoá đơn cụ thể.
     */
    @GetMapping("/invoices/{invoiceId}/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'SALES_MANAGER', 'SALES_REP')")
    public ResponseEntity<List<PaymentReminderLogResponse>> getInvoiceLogs(@PathVariable Long invoiceId,
                                                                           @AuthenticationPrincipal UserDetailsImpl actor) {
        List<PaymentReminderLogResponse> logs = reminderService.getLogsForInvoice(invoiceId, actor);
        return ResponseEntity.ok(logs);
    }
}
