package com.erp.backend.controller;

import com.erp.backend.dto.audit.AuditLogResponse;
import com.erp.backend.dto.audit.CreateAuditLogEntry;
import com.erp.backend.dto.customer.OptionItem;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * S2-04: API Nhật ký hệ thống.
 * - Xem nhật ký thao tác trên tồn kho, giá, hạn mức công nợ và hóa đơn.
 * - Lọc theo người dùng (actorId), loại đối tượng / phân hệ (module, targetType), khoảng thời gian (startDate, endDate).
 *
 * Phân quyền: ADMIN (Quản trị hệ thống), ACCOUNTANT (Kế toán công nợ), SALES_MANAGER (Quản lý kinh doanh), WH_MANAGER (Quản lý kho).
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'SALES_MANAGER', 'WH_MANAGER')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * Tra cứu nhật ký hệ thống kèm bộ lọc.
     * Vd: /api/audit-logs?module=DEBT_LIMIT&keyword=DL-001&startDate=2026-10-01T00:00:00&page=0&size=20
     */
    @GetMapping
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) AuditModule module,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditLogService.search(keyword, module, targetType, actorId, startDate, endDate, action, page, size);
    }

    /**
     * Xem chi tiết một bản ghi nhật ký.
     */
    @GetMapping("/{id}")
    public AuditLogResponse getById(@PathVariable Long id) {
        return auditLogService.getById(id);
    }

    /**
     * S204-01: Chặn client tự tạo hoặc mạo danh bản ghi nhật ký kiểm toán.
     * Nhật ký kiểm toán chỉ được sinh tự động từ hệ thống qua Business Services & Interceptors.
     */
    @PostMapping
    public AuditLogResponse record(@RequestBody CreateAuditLogEntry entry) {
        throw com.erp.backend.exception.BusinessException.forbidden("ACCESS_DENIED", "Không được phép tự tạo hoặc can thiệp nhật ký kiểm toán hệ thống.");
    }

    /**
     * Danh sách phân hệ nghiệp vụ hỗ trợ ghi nhật ký để hiển thị lên bộ lọc giao diện.
     */
    @GetMapping("/modules")
    public List<OptionItem> getSupportedModules() {
        return Arrays.stream(AuditModule.values())
                .map(m -> new OptionItem(m.name(), m.getLabel()))
                .toList();
    }
}
