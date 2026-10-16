package com.erp.backend.controller;

import com.erp.backend.dto.debtadjustment.DebtAdjustmentDecisionRequest;
import com.erp.backend.dto.debtadjustment.DebtAdjustmentResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.DebtAdjustmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S8-02: API cho màn hình "Chứng từ giảm trừ công nợ" (FE /payments/adjustments).
 * - GET  /api/debt-adjustments?status=PENDING_APPROVAL|APPROVED|REJECTED|ALL&customerId=&keyword=
 * - GET  /api/debt-adjustments/{id}            (id = id phiếu trả hàng)
 * - POST /api/debt-adjustments/{id}/approve    {approvalNote}: lập chứng từ giảm trừ, giảm công nợ ngay
 * - POST /api/debt-adjustments/{id}/reject     {reason}: từ chối (bắt buộc lý do)
 */
@RestController
@RequestMapping("/api/debt-adjustments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'SALES_MANAGER', 'ADMIN')")
public class DebtAdjustmentController {

    private final DebtAdjustmentService debtAdjustmentService;

    @GetMapping
    public List<DebtAdjustmentResponse> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long customerId,
                                             @RequestParam(required = false) String keyword,
                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        return debtAdjustmentService.list(status, customerId, keyword, actor);
    }

    @GetMapping("/{id}")
    public DebtAdjustmentResponse get(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return debtAdjustmentService.get(id, actor);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public DebtAdjustmentResponse approve(@PathVariable Long id,
                                          @Valid @RequestBody(required = false) DebtAdjustmentDecisionRequest body,
                                          @AuthenticationPrincipal UserDetailsImpl actor) {
        return debtAdjustmentService.approve(id, body != null ? body.getApprovalNote() : null, actor);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'ADMIN')")
    public DebtAdjustmentResponse reject(@PathVariable Long id,
                                         @Valid @RequestBody(required = false) DebtAdjustmentDecisionRequest body,
                                         @AuthenticationPrincipal UserDetailsImpl actor) {
        return debtAdjustmentService.reject(id, body != null ? body.getReason() : null, actor);
    }
}
