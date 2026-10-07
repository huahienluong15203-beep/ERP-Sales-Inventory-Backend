package com.erp.backend.service;

import com.erp.backend.dto.pricing.PriceHistoryResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.CustomerGroup;
import com.erp.backend.entity.PriceHistory;
import com.erp.backend.entity.PriceList;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.PriceHistoryRepository;
import com.erp.backend.repository.PriceHistorySpecifications;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * S3-02: Lịch sử thay đổi giá.
 * - Ghi trong cùng giao dịch với thay đổi bảng giá (lỗi thì cả hai cùng huỷ).
 * - Chỉ có ghi mới và tra cứu; không có chức năng sửa / xoá.
 */
@Service
@RequiredArgsConstructor
public class PriceHistoryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final PriceHistoryRepository historyRepository;

    /** Ghi các thay đổi giá của một bảng giá (gọi sau khi đã lưu bảng giá). */
    @Transactional
    public void record(PriceList priceList, List<PriceChange> changes, UserDetailsImpl actor) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        List<PriceHistory> rows = changes.stream()
                .map(c -> PriceHistory.builder()
                        .priceList(priceList)
                        .priceListCode(priceList.getCode())
                        .customerGroup(priceList.getCustomerGroup())
                        .product(c.product())
                        .productSku(c.product().getSku())
                        .productName(c.product().getName())
                        .changeType(c.changeType())
                        .oldPrice(c.oldPrice())
                        .newPrice(c.newPrice())
                        .oldFloorPrice(c.oldFloorPrice())
                        .newFloorPrice(c.newFloorPrice())
                        .effectiveDate(priceList.getStartDate())
                        .changedById(actor != null ? actor.getId() : null)
                        .changedByUsername(actor != null ? actor.getUsername() : null)
                        .changedByName(actor != null ? actor.getFullName() : null)
                        .build())
                .toList();
        historyRepository.saveAll(rows);
    }

    /** Vd: lịch sử giá của 1 sản phẩm: ?productSku=SP-COCA-330 ; của 1 bảng giá: ?priceListId=3 */
    @Transactional(readOnly = true)
    public PageResponse<PriceHistoryResponse> search(String productSku, Long priceListId, String customerGroup,
                                                     LocalDate fromDate, LocalDate toDate, int page, int size) {
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày kết thúc không được trước ngày bắt đầu", "toDate");
        }
        CustomerGroup group = parseGroup(customerGroup);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        return PageResponse.of(historyRepository.findAll(
                PriceHistorySpecifications.filter(productSku, priceListId, group, fromDate, toDate),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "changedAt").and(Sort.by("id").descending())))
                .map(this::toResponse));
    }

    private static CustomerGroup parseGroup(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return CustomerGroup.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CUSTOMER_GROUP",
                    "Nhóm khách hàng chỉ nhận DEALER_LEVEL_1, DEALER_LEVEL_2 hoặc RETAIL", "customerGroup");
        }
    }

    static String changeTypeLabel(String type) {
        return switch (type) {
            case PriceHistory.CREATE -> "Thêm giá";
            case PriceHistory.UPDATE -> "Đổi giá";
            case PriceHistory.DELETE -> "Bỏ giá";
            default -> type;
        };
    }

    PriceHistoryResponse toResponse(PriceHistory h) {
        return new PriceHistoryResponse(h.getId(), h.getPriceList().getId(), h.getPriceListCode(),
                h.getCustomerGroup().name(), h.getCustomerGroup().getLabel(), h.getProduct().getId(),
                h.getProductSku(), h.getProductName(), h.getChangeType(), changeTypeLabel(h.getChangeType()),
                h.getOldPrice(), h.getNewPrice(), h.getOldFloorPrice(), h.getNewFloorPrice(), h.getEffectiveDate(),
                h.getChangedByUsername(), h.getChangedByName(), h.getChangedAt());
    }
}
