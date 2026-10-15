package com.erp.backend.controller;

import com.erp.backend.dto.trip.*;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.DeliveryTripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S6-07: Theo dõi hàng đi đường (chuyến giao).
 * - Xem: NV kho, QL kho, Admin, QL kinh doanh. NV kho / QL kho chỉ thấy chuyến của kho mình được gắn.
 * - Lập / kết thúc chuyến: NV kho, QL kho, Admin.
 */
@RestController
@RequestMapping("/api/delivery-trips")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE', 'WH_MANAGER', 'SALES_MANAGER')")
public class DeliveryTripController {

    private final DeliveryTripService deliveryTripService;

    /** Chuyến đang chạy (mặc định) hoặc đã kết thúc 30 ngày gần nhất. Vd: ?status=IN_PROGRESS&warehouseId=1 */
    @GetMapping
    public List<DeliveryTripSummaryResponse> list(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Long warehouseId,
                                                  @AuthenticationPrincipal UserDetailsImpl actor) {
        return deliveryTripService.list(status, warehouseId, actor);
    }

    /** Tổng quan hàng đi đường: số chuyến đang chạy / trễ, tiền hàng trên đường, đơn đã xuất chưa xếp chuyến. */
    @GetMapping("/summary")
    public InTransitSummaryResponse summary(@AuthenticationPrincipal UserDetailsImpl actor) {
        return deliveryTripService.summary(actor);
    }

    @GetMapping("/{id}")
    public DeliveryTripDetailResponse get(@PathVariable Long id, @AuthenticationPrincipal UserDetailsImpl actor) {
        return deliveryTripService.get(id, actor);
    }

    /** Body: {"orderIds":[101,102],"vehiclePlate":"29H-12345","driverName":"Anh Tuấn","expectedReturnAt":"2026-10-15T18:00:00"} */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE', 'WH_MANAGER')")
    public ResponseEntity<DeliveryTripDetailResponse> create(@Valid @RequestBody DeliveryTripCreateRequest request,
                                                             @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deliveryTripService.create(request, actor));
    }

    /** Xe về kho: mọi điểm đã xử lý; hàng chưa giao được nhập lại kho. */
    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE', 'WH_MANAGER')")
    public DeliveryTripDetailResponse complete(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) DeliveryTripCompleteRequest request,
                                               @AuthenticationPrincipal UserDetailsImpl actor) {
        return deliveryTripService.complete(id, request, actor);
    }
}
