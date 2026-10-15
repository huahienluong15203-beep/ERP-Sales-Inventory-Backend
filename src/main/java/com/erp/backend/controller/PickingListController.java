package com.erp.backend.controller;

import com.erp.backend.dto.inventory.picking.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.PickingListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller quản lý Phiếu soạn hàng (S6-03 / SCRUM-88).
 * Hỗ trợ Gom đơn, Gộp mặt hàng, Sắp xếp theo đường đi trong kho & Cập nhật tiến độ trên điện thoại.
 */
@RestController
@RequestMapping("/api/inventory/picking-lists")
@RequiredArgsConstructor
@Tag(name = "Order Picking Management", description = "API Gom đơn, gộp mặt hàng, sắp xếp đường đi kho & quản lý phiếu soạn hàng (S6-03)")
public class PickingListController {

    private final PickingListService pickingListService;

    // =========================================================================
    // 1. DANH SÁCH ĐƠN KHẢ DỤNG & XEM TRƯỚC GOM ĐƠN
    // =========================================================================

    @GetMapping("/available-orders")
    @Operation(summary = "Lấy danh sách đơn hàng đã duyệt sẵn sàng gom vào đợt soạn",
            description = "Lấy các đơn hàng có trạng thái APPROVED tại kho chưa được gom vào phiếu soạn hàng nào.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<List<AvailablePickingOrderResponse>> getAvailableOrders(
            @Parameter(description = "ID kho xuất hàng", required = true) @RequestParam Long warehouseId,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(pickingListService.getAvailableOrdersForPicking(warehouseId, actor));
    }

    @PostMapping("/preview")
    @Operation(summary = "Xem trước kết quả gom đơn, gộp mặt hàng và đường đi",
            description = "Xem trước danh sách mặt hàng gộp kèm vị trí lưu và thứ tự đường đi trước khi chính thức tạo phiếu.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<PickingListPreviewResponse> previewPickingList(
            @Valid @RequestBody PickingListPreviewRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(pickingListService.previewPickingList(request, actor));
    }

    // =========================================================================
    // 2. TẠO MỚI & TRA CỨU PHIẾU SOẠN HÀNG
    // =========================================================================

    @PostMapping
    @Operation(summary = "Tạo phiếu soạn hàng từ các đơn đã chọn",
            description = "Gom nhiều đơn hàng đã duyệt, gộp mặt hàng, gắn vị trí lưu và sắp xếp theo đường đi trong kho.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<PickingListResponse> createPickingList(
            @Valid @RequestBody PickingListCreateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        PickingListResponse response = pickingListService.createPickingList(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Tra cứu danh sách phiếu soạn hàng",
            description = "Tra cứu theo kho, trạng thái (ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED), người soạn và từ khóa.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<PageResponse<PickingListResponse>> searchPickingLists(
            @Parameter(description = "ID kho") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Trạng thái phiếu: ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED") @RequestParam(required = false) String status,
            @Parameter(description = "ID người soạn hàng") @RequestParam(required = false) Long pickerId,
            @Parameter(description = "Từ khóa tìm kiếm (mã phiếu, ghi chú, người soạn)") @RequestParam(required = false) String keyword,
            @Parameter(description = "Số trang (bắt đầu từ 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Số bản ghi mỗi trang") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sắp xếp theo trường (vd: createdAt,desc)") @RequestParam(defaultValue = "createdAt,desc") String sort,
            @AuthenticationPrincipal UserDetailsImpl actor) {

        String[] sortParts = sort.split(",");
        String sortProp = sortParts[0];
        Sort.Direction direction = sortParts.length > 1 && "asc".equalsIgnoreCase(sortParts[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortProp));
        PageResponse<PickingListResponse> response = pickingListService.searchPickingLists(
                warehouseId, status, pickerId, keyword, pageRequest, actor);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Xem chi tiết một phiếu soạn hàng",
            description = "Xem thông tin chi tiết phiếu soạn hàng gồm danh sách mặt hàng gộp, vị trí lưu, tiến độ soạn và phân bổ về từng đơn.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN', 'ACCOUNTANT', 'SALES_MANAGER')")
    public ResponseEntity<PickingListResponse> getPickingListById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(pickingListService.getPickingListById(id, actor));
    }

    // =========================================================================
    // 3. TIẾN ĐỘ SOẠN HÀNG TRÊN DI ĐỘNG & RÀNG BUỘC SOẠN THIẾU
    // =========================================================================

    @PatchMapping("/{id}/items/{itemId}")
    @Operation(summary = "Cập nhật tiến độ soạn cho một dòng hàng (Mobile-friendly)",
            description = "Cập nhật số lượng thực soạn, tick hoàn thành từng dòng. BẮT BUỘC nhập lý do nếu số lượng thực soạn < số lượng yêu cầu.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<PickingListResponse> updateItemProgress(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody PickingItemUpdateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(pickingListService.updateItemProgress(id, itemId, request, actor));
    }

    @PutMapping("/{id}/progress")
    @Operation(summary = "Cập nhật tiến độ hàng loạt cho nhiều dòng hàng",
            description = "Lưu tiến độ đồng loạt các dòng hàng và tùy chọn tự động hoàn tất phiếu nếu tất cả dòng đã soạn xong.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<PickingListResponse> updateProgress(
            @PathVariable Long id,
            @Valid @RequestBody PickingProgressUpdateRequest request,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(pickingListService.updateProgress(id, request, actor));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Chốt hoàn thành phiếu soạn hàng",
            description = "Chuyển trạng thái phiếu sang COMPLETED. Kiểm tra tất cả các dòng soạn thiếu phải có lý do đầy đủ.")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'WH_MANAGER', 'ADMIN')")
    public ResponseEntity<PickingListResponse> completePickingList(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(pickingListService.completePickingList(id, actor));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Hủy phiếu soạn hàng",
            description = "Hủy phiếu soạn hàng kèm lý do, tự động trả các đơn hàng về trạng thái APPROVED nếu chưa xuất kho.")
    @PreAuthorize("hasAnyRole('WH_MANAGER', 'ADMIN')")
    public ResponseEntity<PickingListResponse> cancelPickingList(
            @PathVariable Long id,
            @Parameter(description = "Lý do hủy phiếu soạn hàng", required = true) @RequestParam String reason,
            @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.ok(pickingListService.cancelPickingList(id, reason, actor));
    }
}
