package com.erp.backend.controller;

import com.erp.backend.dto.customer.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.CustomerGroup;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.CustomerDeliveryAddressService;
import com.erp.backend.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Đại lý: S3-03 (hồ sơ), S3-04 (điểm giao hàng), S3-06 (phân công), S3-08 (tìm kiếm, lọc).
 *
 * Phân quyền (theo ma trận "Đại lý & hạn mức công nợ"):
 * - Xem: ADMIN, SALES_MANAGER, ACCOUNTANT (tất cả) | SALES_REP (chỉ đại lý mình phụ trách)
 * - Tạo / sửa hồ sơ, ngừng giao dịch: ADMIN, SALES_MANAGER, ACCOUNTANT
 * - Điểm giao hàng: thêm cả SALES_REP (chỉ đại lý mình phụ trách)
 * - Phân công / chuyển giao người phụ trách: ADMIN, SALES_MANAGER
 * Vai trò khác (kho, đại lý...) bị từ chối.
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'ACCOUNTANT', 'SALES_REP')")
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerDeliveryAddressService deliveryAddressService;

    // ======================= S3-08: TÌM KIẾM / XEM =======================

    /** Vd: ?keyword=minh%20phat&regionId=1&customerGroup=DEALER_LEVEL_1&salesRepId=5&status=ACTIVE&transactionLocked=true&page=0&size=20 */
    @GetMapping
    public PageResponse<CustomerResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) CustomerGroup customerGroup,
            @RequestParam(required = false) Long salesRepId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean transactionLocked,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.search(keyword, regionId, customerGroup, salesRepId, status, transactionLocked,
                page, size, actor);
    }

    /** Dữ liệu cho ô chọn: nhóm khách hàng, trạng thái, khu vực, nhân viên kinh doanh. */
    @GetMapping("/form-options")
    public CustomerFormOptionsResponse formOptions() {
        return customerService.getFormOptions();
    }

    @GetMapping("/{id}")
    public CustomerResponse getById(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.getById(id, actor);
    }

    // ======================= S3-03: HỒ SƠ ĐẠI LÝ =======================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request,
                                                   @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.create(request, actor));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public CustomerResponse update(@PathVariable Long id,
                                   @Valid @RequestBody CustomerProfileRequest request,
                                   @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.update(id, request, actor);
    }

    /** Ngừng giao dịch / giao dịch lại (thay cho xoá). */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public CustomerResponse changeStatus(@PathVariable Long id,
                                         @Valid @RequestBody ChangeCustomerStatusRequest request,
                                         @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.changeStatus(id, request, actor);
    }

    // ======================= S3-05: HẠN MỨC CÔNG NỢ & SỐ NGÀY NỢ =======================

    /**
     * S3-05: Thiết lập hạn mức tiền tối đa và số ngày nợ tối đa (bắt buộc nhập lý do khi thay đổi để ghi nhật ký).
     */
    @PutMapping("/{id}/debt-limit")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public CustomerResponse updateDebtLimit(@PathVariable Long id,
                                           @Valid @RequestBody UpdateDebtLimitRequest request,
                                           @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.updateDebtLimit(id, request, actor);
    }

    // ======================= S3-07: KHÓA / MỞ GIAO DỊCH ĐẠI LÝ =======================

    /**
     * S3-07: Khóa hoặc mở giao dịch với một đại lý (bắt buộc nhập lý do) để chặn tạo đơn mới trên mọi nền tảng.
     */
    @PatchMapping("/{id}/transaction-lock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'ACCOUNTANT')")
    public CustomerResponse setTransactionLock(@PathVariable Long id,
                                               @Valid @RequestBody CustomerTransactionLockRequest request,
                                               @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.setTransactionLock(id, request, actor);
    }

    /**
     * S3-07 & S4-02: Kiểm tra điều kiện tạo đơn mới của đại lý.
     * Cho phép các nền tảng bán hàng kiểm tra trước khi tạo đơn.
     */
    @GetMapping("/{id}/check-order-creation")
    public OrderCreationCheckResponse checkOrderCreation(@PathVariable Long id) {
        return customerService.checkOrderCreation(id);
    }

    // ======================= S3-04: ĐIỂM GIAO HÀNG =======================

    @GetMapping("/{id}/delivery-addresses")
    public List<DeliveryAddressResponse> listAddresses(@PathVariable Long id,
                                                       @AuthenticationPrincipal UserDetailsImpl actor) {
        return deliveryAddressService.list(id, actor);
    }

    @PostMapping("/{id}/delivery-addresses")
    public ResponseEntity<DeliveryAddressResponse> createAddress(@PathVariable Long id,
                                                                 @Valid @RequestBody DeliveryAddressRequest request,
                                                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deliveryAddressService.create(id, request, actor));
    }

    @PutMapping("/{id}/delivery-addresses/{addressId}")
    public DeliveryAddressResponse updateAddress(@PathVariable Long id,
                                                 @PathVariable Long addressId,
                                                 @Valid @RequestBody DeliveryAddressRequest request,
                                                 @AuthenticationPrincipal UserDetailsImpl actor) {
        return deliveryAddressService.update(id, addressId, request, actor);
    }

    @PatchMapping("/{id}/delivery-addresses/{addressId}/default")
    public DeliveryAddressResponse setDefaultAddress(@PathVariable Long id,
                                                     @PathVariable Long addressId,
                                                     @AuthenticationPrincipal UserDetailsImpl actor) {
        return deliveryAddressService.setDefault(id, addressId, actor);
    }

    /** Ngừng sử dụng điểm giao (không xoá cứng vì đơn hàng sẽ tham chiếu tới). */
    @PatchMapping("/{id}/delivery-addresses/{addressId}/deactivate")
    public ResponseEntity<Void> deactivateAddress(@PathVariable Long id,
                                                  @PathVariable Long addressId,
                                                  @AuthenticationPrincipal UserDetailsImpl actor) {
        deliveryAddressService.deactivate(id, addressId, actor);
        return ResponseEntity.noContent().build();
    }

    // ======================= S3-06: PHÂN CÔNG NGƯỜI PHỤ TRÁCH =======================

    @PutMapping("/{id}/sales-rep")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public CustomerResponse assignSalesRep(@PathVariable Long id,
                                           @Valid @RequestBody AssignSalesRepRequest request,
                                           @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.assignSalesRep(id, request, actor);
    }

    /** Chuyển giao hàng loạt đại lý khi nhân viên nghỉ việc. */
    @PostMapping("/transfer")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public TransferCustomersResponse transfer(@Valid @RequestBody TransferCustomersRequest request,
                                              @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.transfer(request, actor);
    }

    @GetMapping("/{id}/assignment-history")
    public List<AssignmentHistoryResponse> assignmentHistory(@PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        return customerService.getAssignmentHistory(id, actor);
    }
}
