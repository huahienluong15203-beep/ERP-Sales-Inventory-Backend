package com.erp.backend.controller;

import com.erp.backend.dto.order.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.OrderApprovalService;
import com.erp.backend.service.OrderDraftService;
import com.erp.backend.service.OrderQueryService;
import com.erp.backend.service.OrderPrintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * S3-09: Khởi tạo đơn hàng (đơn nháp).
 * - Xem: Admin, QL kinh doanh, NV kinh doanh, Kế toán.
 * - Tạo / sửa nháp, xem trước, gợi ý sản phẩm: Admin, QL kinh doanh, NV kinh doanh.
 * NV kinh doanh chỉ làm việc với đại lý mình phụ trách.
 * S4-05: Chốt đơn (Admin, QL / NV kinh doanh); danh sách chờ duyệt, Duyệt / Từ chối / Trả lại sửa (QL kinh doanh;
 *        S4-10: NV kinh doanh xác nhận đơn đại lý tự đặt của đại lý mình nếu đơn không vi phạm).
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP', 'ACCOUNTANT', 'WAREHOUSE', 'WH_MANAGER', 'CUSTOMER')")
public class OrderController {

    private final OrderDraftService orderDraftService;
    private final OrderApprovalService orderApprovalService;
    private final OrderQueryService orderQueryService;
    private final OrderPrintService orderPrintService;
    private final com.erp.backend.service.OrderStatusService orderStatusService;

    /**
     * S3-09 / S4-07: Danh sách đơn có bộ lọc. NV kinh doanh chỉ thấy đơn của đại lý mình phụ trách.
     * Vd: ?status=PENDING_APPROVAL,APPROVED&customerId=6&salesRepId=7&regionId=2&fromDate=2026-10-01&toDate=2026-10-31&keyword=DH2610&page=0&size=20
     */
    @GetMapping
    public PageResponse<OrderSummaryResponse> search(@RequestParam(required = false) List<String> status,
                                                     @RequestParam(required = false) Long customerId,
                                                     @RequestParam(required = false) Long salesRepId,
                                                     @RequestParam(required = false) Long regionId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderQueryService.search(new OrderSearchCriteria(status, customerId, salesRepId, regionId, fromDate, toDate,
                keyword), page, size, actor);
    }

    /** S4-07: Tổng số đơn và tổng tiền của toàn bộ kết quả đang lọc (cùng bộ lọc với danh sách). */
    @GetMapping("/totals")
    public OrderTotalsResponse totals(@RequestParam(required = false) List<String> status,
                                      @RequestParam(required = false) Long customerId,
                                      @RequestParam(required = false) Long salesRepId,
                                      @RequestParam(required = false) Long regionId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                                      @RequestParam(required = false) String keyword,
                                      @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderQueryService.totals(new OrderSearchCriteria(status, customerId, salesRepId, regionId, fromDate, toDate,
                keyword), actor);
    }

    /** S4-05: Đơn chờ duyệt kèm lý do và mức vi phạm, đơn chờ lâu nhất lên đầu. Vd: ?keyword=DL001&page=0&size=20 */
    @GetMapping("/pending-approval")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'SALES_REP')")
    public PageResponse<PendingOrderResponse> pendingApproval(@RequestParam(required = false) String keyword,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size,
                                                              @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.pending(keyword, page, size, actor);
    }

    /**
     * S4-08: Mẫu in đơn hàng (HTML khổ A4) có mã đơn và mã vạch Code 128; in hoặc "Lưu dưới dạng PDF" từ trình duyệt.
     * ?autoprint=true thì tự mở hộp thoại in khi tải xong. Quyền như xem đơn.
     */
    // Không khai báo produces để lỗi (404 / 409...) vẫn trả JSON chuẩn {code, message, ...}
    @GetMapping("/{id}/print")
    public ResponseEntity<String> print(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean autoprint,
                                        @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(orderPrintService.printHtml(id, autoprint, actor));
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

    /**
     * S4-09 / SCRUM-159: Sao chép một đơn cũ thành đơn mới (để tạo đơn định kỳ cho khách quen trong vài giây).
     * - AC1: Sao chép toàn bộ dòng hàng của đơn đã chọn.
     * - AC2: Giá và chiết khấu được áp lại theo bảng giá hiện hành, không kế thừa giá cũ.
     * - AC3: Bản sao luôn bắt đầu ở trạng thái Nháp (DRAFT).
     */
    @PostMapping(value = {"/{id}/copy", "/{id}/clone"})
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public ResponseEntity<OrderResponse> copyOrder(@PathVariable Long id,
                                                   @RequestBody(required = false) OrderCopyRequest request,
                                                   @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderDraftService.copyOrder(id, request, actor));
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
    // S4-10: NV kinh doanh xác nhận được đơn đại lý tự đặt của đại lý mình (service kiểm chi tiết)
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'SALES_REP')")
    public OrderResponse approve(@PathVariable Long id,
                                 @Valid @RequestBody(required = false) OrderApprovalActionRequest request,
                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.approve(id, request != null ? request.getComment() : null, actor);
    }

    /** Bắt buộc nhập ý kiến. */
    @PostMapping("/{id}/reject")
    // S4-10: NV kinh doanh xác nhận được đơn đại lý tự đặt của đại lý mình (service kiểm chi tiết)
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'SALES_REP')")
    public OrderResponse reject(@PathVariable Long id,
                                @Valid @RequestBody(required = false) OrderApprovalActionRequest request,
                                @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderApprovalService.reject(id, request != null ? request.getComment() : null, actor);
    }

    /** Trả lại sửa: đơn về Nháp. Bắt buộc nhập ý kiến. */
    @PostMapping("/{id}/return")
    // S4-10: NV kinh doanh xác nhận được đơn đại lý tự đặt của đại lý mình (service kiểm chi tiết)
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'SALES_REP')")
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

    /**
     * S4-06 / SCRUM-158 AC2: Hủy đơn hàng và tự động nhả tồn đang giữ chỗ.
     * Bắt buộc có lý do (AC2). Đơn đã xuất kho (DISPATCHED trở đi) thì nghiêm cấm hủy (AC3).
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public OrderResponse cancel(@PathVariable Long id,
                                @Valid @RequestBody OrderCancelRequest request,
                                @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderStatusService.cancel(id, request.getReason(), actor);
    }

    /**
     * S4-06 / SCRUM-158: API chuyển trạng thái đơn hàng theo vòng đời:
     * APPROVED -> PICKING -> DISPATCHED -> DELIVERED -> CLOSED (hoặc CANCELLED).
     * S5-01: Ghi nhận số lượng thực giao (DELIVERED) nếu có.
     */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WAREHOUSE', 'WH_MANAGER', 'SALES_REP')")
    public OrderResponse updateStatus(@PathVariable Long id,
                                      @Valid @RequestBody OrderStatusTransitionRequest request,
                                      @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderStatusService.transitionStatus(id, request.getStatus(), request.getNote(),
                request.getLineDeliveries(), actor);
    }

    /**
     * S5-01 / SCRUM-160: API cập nhật số lượng thực giao của từng dòng hàng (hỗ trợ ghi nhận giao thiếu).
     */
    @PatchMapping("/{id}/delivered-quantities")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WAREHOUSE', 'WH_MANAGER', 'SALES_REP')")
    public OrderResponse updateDeliveredQuantities(@PathVariable Long id,
                                                  @Valid @RequestBody OrderLineDeliveryBatchRequest request,
                                                  @AuthenticationPrincipal UserDetailsImpl actor) {
        return orderStatusService.updateDeliveredQuantities(id, request.getLineDeliveries(), actor);
    }
}
