package com.erp.backend.controller;

import com.erp.backend.dto.pricing.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.PriceListService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * S2-10: Bảng giá theo nhóm khách hàng.
 * - Xem, tra giá: Admin, QL kinh doanh, NV kinh doanh, Kế toán.
 * - Tạo / sửa / tạo phiên bản / bật tắt / sửa dòng giá: Admin, QL kinh doanh.
 */
@RestController
@RequestMapping("/api/price-lists")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP', 'ACCOUNTANT')")
public class PriceListController {

    private final PriceListService priceListService;

    /** Lọc + phân trang phía server. Vd: ?customerGroup=DEALER_LEVEL_1&status=ACTIVE&keyword=thang10&page=0&size=20 */
    @GetMapping
    public PageResponse<PriceListResponse> search(@RequestParam(required = false) String customerGroup,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return priceListService.search(customerGroup, status, keyword, page, size);
    }

    /** Số liệu cho các thẻ thống kê đầu trang (không phụ thuộc trang/bộ lọc). */
    @GetMapping("/stats")
    public PriceListStatsResponse stats() {
        return priceListService.stats();
    }

    /** Giá đang áp dụng. Vd: ?customerGroup=DEALER_LEVEL_1&productSku=SP-COCA-330&date=2026-10-15 */
    @GetMapping("/lookup")
    public PriceLookupResponse lookup(@RequestParam String customerGroup,
                                      @RequestParam String productSku,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return priceListService.lookup(customerGroup, productSku, date);
    }

    @GetMapping("/{id}")
    public PriceListResponse getById(@PathVariable Long id) {
        return priceListService.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<PriceListResponse> create(@RequestBody PriceListRequest request,
                                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(priceListService.create(request, actor));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public PriceListResponse update(@PathVariable Long id, @RequestBody PriceListRequest request,
                                    @AuthenticationPrincipal UserDetailsImpl actor) {
        return priceListService.update(id, request, actor);
    }

    /** Tạo phiên bản mới. Body có thể rỗng {} (kế thừa toàn bộ từ bảng gốc). */
    @PostMapping("/{id}/clone-version")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ResponseEntity<PriceListResponse> cloneVersion(@PathVariable Long id,
                                                          @RequestBody(required = false) PriceListRequest request,
                                                          @AuthenticationPrincipal UserDetailsImpl actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(priceListService.cloneVersion(id, request, actor));
    }

    /** Vd: PATCH /api/price-lists/3/status?status=INACTIVE */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public PriceListResponse changeStatus(@PathVariable Long id, @RequestParam String status,
                                          @AuthenticationPrincipal UserDetailsImpl actor) {
        return priceListService.changeStatus(id, status, actor);
    }

    /** Thêm dòng giá, hoặc sửa giá nếu sản phẩm đã có trong bảng. */
    @PostMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public PriceListResponse upsertItem(@PathVariable Long id, @RequestBody PriceListItemRequest request,
                                        @AuthenticationPrincipal UserDetailsImpl actor) {
        return priceListService.upsertItem(id, request, actor);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public PriceListResponse deleteItem(@PathVariable Long id, @PathVariable Long itemId,
                                        @AuthenticationPrincipal UserDetailsImpl actor) {
        return priceListService.deleteItem(id, itemId, actor);
    }
}
