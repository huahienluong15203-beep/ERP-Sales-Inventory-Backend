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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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

    /** Lịch sử đơn của đại lý. Vd: ?status=PENDING_APPROVAL&page=0&size=20 */
    @GetMapping("/orders")
    public PageResponse<OrderSummaryResponse> orders(@RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.orders(status, page, size, actor);
    }

    @GetMapping("/orders/{id}")
    public PortalOrderResponse order(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return portalService.order(id, actor);
    }
}
