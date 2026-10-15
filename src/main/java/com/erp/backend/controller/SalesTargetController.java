package com.erp.backend.controller;

import com.erp.backend.dto.kpi.MySalesProgressResponse;
import com.erp.backend.dto.kpi.SalesTargetCreateRequest;
import com.erp.backend.dto.kpi.SalesTargetResponse;
import com.erp.backend.dto.kpi.SalesTargetUpdateRequest;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.SalesTargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S8-04: Chỉ tiêu doanh số tháng (KPI) theo NV kinh doanh và khu vực.
 * - Quản lý chỉ tiêu, xem tiến độ mọi người: QL kinh doanh, Admin.
 * - NV kinh doanh xem tiến độ của chính mình (/my-progress).
 * Kỳ đã qua bị khoá; sửa / xoá bắt buộc lý do và ghi nhật ký (kiểm ở service).
 */
@RestController
@RequestMapping("/api/sales-targets")
@RequiredArgsConstructor
public class SalesTargetController {

    private final SalesTargetService salesTargetService;

    /** Vd: ?period=2026-10&type=REP (bỏ trống period = tháng hiện tại, type = tất cả). */
    @GetMapping
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public List<SalesTargetResponse> list(@RequestParam(required = false) String period,
                                          @RequestParam(required = false) String type,
                                          @AuthenticationPrincipal UserDetailsImpl actor) {
        return salesTargetService.list(period, type, actor);
    }

    /** NV kinh doanh xem chỉ tiêu và doanh số của chính mình. Vd: ?period=2026-10 */
    @GetMapping("/my-progress")
    @PreAuthorize("hasRole('SALES_REP')")
    public MySalesProgressResponse myProgress(@RequestParam(required = false) String period,
                                              @AuthenticationPrincipal UserDetailsImpl actor) {
        return salesTargetService.myProgress(period, actor);
    }

    /** Body: {"period":"2026-11","targetType":"REP","salesRepId":7,"targetAmount":500000000,"note":"..."} */
    @PostMapping
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ResponseEntity<SalesTargetResponse> create(@Valid @RequestBody SalesTargetCreateRequest request,
                                                      @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(salesTargetService.create(request, actor));
    }

    /** Body: {"targetAmount":600000000,"note":"...","reason":"Tăng chỉ tiêu do mở thêm đại lý"} */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public SalesTargetResponse update(@PathVariable Long id,
                                      @Valid @RequestBody SalesTargetUpdateRequest request,
                                      @AuthenticationPrincipal UserDetailsImpl actor) {
        return salesTargetService.update(id, request, actor);
    }

    /** Xoá chỉ tiêu tháng hiện tại / tương lai. Vd: DELETE /api/sales-targets/5?reason=Nhập nhầm */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestParam(required = false) String reason,
                                       @AuthenticationPrincipal UserDetailsImpl actor) {
        salesTargetService.delete(id, reason, actor);
        return ResponseEntity.noContent().build();
    }
}
