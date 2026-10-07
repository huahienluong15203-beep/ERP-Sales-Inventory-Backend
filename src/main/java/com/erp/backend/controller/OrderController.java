package com.erp.backend.controller;

import com.erp.backend.dto.order.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.OrderDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S3-09: Khởi tạo đơn hàng (đơn nháp).
 * - Xem: Admin, QL kinh doanh, NV kinh doanh, Kế toán.
 * - Tạo / sửa nháp, xem trước, gợi ý sản phẩm: Admin, QL kinh doanh, NV kinh doanh.
 * NV kinh doanh chỉ làm việc với đại lý mình phụ trách.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP', 'ACCOUNTANT')")
public class OrderController {

    private final OrderDraftService orderDraftService;

    /** Vd: ?status=DRAFT&customerId=6&keyword=DH2610&page=0&size=20 */
    @GetMapping
    public PageResponse<OrderSummaryResponse> search(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Long customerId,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderDraftService.search(status, customerId, keyword, page, size, actor);
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderDraftService.getById(id, actor);
    }

    /** Gợi ý sản phẩm khi gõ mã / tên (từ 2 ký tự). Vd: ?customerId=6&keyword=coca */
    @GetMapping("/product-options")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public List<ProductOptionResponse> productOptions(@RequestParam Long customerId,
                                                      @RequestParam(required = false) String keyword,
                                                      @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderDraftService.productOptions(customerId, keyword, actor);
    }

    /** Tính tổng tiền hàng, chiết khấu, tổng phải thu khi thêm / sửa dòng hàng (không lưu). */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public OrderResponse preview(@RequestBody OrderDraftRequest request, @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderDraftService.preview(request, actor);
    }

    /** Lưu nháp đơn mới. */
    @PostMapping("/drafts")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public ResponseEntity<OrderResponse> createDraft(@RequestBody OrderDraftRequest request,
                                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderDraftService.createDraft(request, actor));
    }

    /** Mở lại đơn nháp và lưu tiếp. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public OrderResponse updateDraft(@PathVariable Long id, @RequestBody OrderDraftRequest request,
                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderDraftService.updateDraft(id, request, actor);
    }
}
