package com.erp.backend.controller;

import com.erp.backend.dto.order.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.OrderApprovalService;
import com.erp.backend.service.OrderDraftService;
import jakarta.validation.Valid;
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
 * S4-05: Chốt đơn (Admin, QL / NV kinh doanh); danh sách chờ duyệt, Duyệt / Từ chối / Trả lại sửa (chỉ QL kinh doanh).
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP', 'ACCOUNTANT')")
public class OrderController {

    private final OrderDraftService orderDraftService;
    private final OrderApprovalService orderApprovalService;

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

    /** S4-05: Đơn chờ duyệt kèm lý do và mức vi phạm, đơn chờ lâu nhất lên đầu. Vd: ?keyword=DL001&page=0&size=20 */
    @GetMapping("/pending-approval")
    @PreAuthorize("hasRole('SALES_MANAGER')")
    public PageResponse<PendingOrderResponse> pendingApproval(@RequestParam(required = false) String keyword,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return orderApprovalService.pending(keyword, page, size);
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

    /** S4-05: Chốt đơn nháp: không vi phạm -> Đã duyệt, vượt hạn mức / dưới giá sàn -> Chờ duyệt. */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public OrderResponse submit(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.submit(id, actor);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('SALES_MANAGER')")
    public OrderResponse approve(@PathVariable Long id,
                                 @Valid @RequestBody(required = false) OrderApprovalActionRequest request,
                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.approve(id, request != null ? request.getComment() : null, actor);
    }

    /** Bắt buộc nhập ý kiến. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('SALES_MANAGER')")
    public OrderResponse reject(@PathVariable Long id,
                                @Valid @RequestBody(required = false) OrderApprovalActionRequest request,
                                @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.reject(id, request != null ? request.getComment() : null, actor);
    }

    /** Trả lại sửa: đơn về Nháp. Bắt buộc nhập ý kiến. */
    @PostMapping("/{id}/return")
    @PreAuthorize("hasRole('SALES_MANAGER')")
    public OrderResponse returnForEdit(@PathVariable Long id,
                                       @Valid @RequestBody(required = false) OrderApprovalActionRequest request,
                                       @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.returnForEdit(id, request != null ? request.getComment() : null, actor);
    }

    /** S4-05: Lịch sử chốt / duyệt đơn (chỉ xem, không sửa / xoá). */
    @GetMapping("/{id}/approval-history")
    public List<OrderApprovalHistoryResponse> approvalHistory(@PathVariable Long id,
                                                              @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.history(id, actor);
    }
}
