package com.erp.backend.controller;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.customer.DeliveryAddressResponse;
import com.erp.backend.dto.order.OrderSummaryResponse;
import com.erp.backend.dto.portal.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.PortalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * S4-10: Cổng đại lý đặt hàng — chỉ tài khoản vai trò Đại lý (CUSTOMER).
 * Đại lý luôn lấy theo tài khoản đăng nhập; không có tham số customerId.
 */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class PortalController {

    private final PortalService portalService;

    @GetMapping("/me")
    public PortalMeResponse me(@AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.me(actor);
    }

    /** Sản phẩm và giá theo bảng giá nhóm khách của đại lý. Vd: ?keyword=coca */
    @GetMapping("/products")
    public List<PortalProductResponse> products(@RequestParam(required = false) String keyword,
                                                @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.products(keyword, actor);
    }

    /** Công nợ hiện tại, hạn mức, còn lại; truyền orderAmount = tổng giỏ hàng để biết có vượt hạn mức không. */
    @GetMapping("/credit-status")
    public CreditStatusResponse creditStatus(@RequestParam(required = false) BigDecimal orderAmount,
                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.creditStatus(orderAmount, actor);
    }

    @GetMapping("/delivery-addresses")
    public List<DeliveryAddressResponse> deliveryAddresses(@AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.deliveryAddresses(actor);
    }

    /** Tính tiền giỏ hàng (không lưu). */
    @PostMapping("/orders/preview")
    public PortalOrderResponse preview(@Valid @RequestBody PortalOrderRequest request,
                                       @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.preview(request, actor);
    }

    /** Gửi đơn: đơn luôn ở trạng thái Chờ duyệt để nhân viên phụ trách xác nhận. */
    @PostMapping("/orders")
    public ResponseEntity<PortalOrderResponse> placeOrder(@Valid @RequestBody PortalOrderRequest request,
                                                          @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(portalService.placeOrder(request, actor));
    }

    /** Lịch sử đơn của đại lý có bộ lọc và phân trang. Vd: ?status=PENDING_APPROVAL&keyword=DH2610&fromDate=2026-10-01&toDate=2026-10-31&page=0&size=10 */
    @GetMapping("/orders")
    public PageResponse<OrderSummaryResponse> orders(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.orders(status, fromDate, toDate, keyword, page, size, actor);
    }

    /**
     * S5-02: Đặt lại đơn cũ: chọn toàn bộ (lineIds trống) hoặc một phần dòng; hàng ngừng kinh doanh bị loại kèm lý do,
     * giá áp lại theo bảng giá hiện hành. Đại lý xác nhận thì gửi keptLines qua POST /api/portal/orders.
     */
    @PostMapping("/orders/{id}/reorder-preview")
    public ReorderPreviewResponse reorderPreview(@PathVariable Long id,
                                                 @Valid @RequestBody(required = false) ReorderPreviewRequest request,
                                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.reorderPreview(id, request, actor);
    }

    @GetMapping("/orders/{id}")
    public PortalOrderResponse order(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.order(id, actor);
    }
}
