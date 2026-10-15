package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO trả về kết quả gợi ý lô xuất theo nguyên tắc FEFO (S6-04).
 * Hỗ trợ tự động gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng,
 * đồng thời cung cấp danh sách đầy đủ các lô khả dụng cho component dropdown trên giao diện.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả gợi ý lô xuất kho theo nguyên tắc FEFO (S6-04)")
public class FefoLotSuggestionResponse {

    @Schema(description = "ID sản phẩm")
    private Long productId;

    @Schema(description = "Mã SKU sản phẩm")
    private String productSku;

    @Schema(description = "Tên sản phẩm")
    private String productName;

    @Schema(description = "Đơn vị tính cơ sở")
    private String baseUnit;

    @Schema(description = "Cờ sản phẩm có quản lý theo lô hay không")
    private Boolean hasBatchManagement;

    @Schema(description = "ID kho")
    private Long warehouseId;

    @Schema(description = "Mã kho")
    private String warehouseCode;

    @Schema(description = "Tên kho")
    private String warehouseName;

    @Schema(description = "Số lượng yêu cầu xuất (theo ĐVT cơ sở)")
    private BigDecimal requestedQuantity;

    @Schema(description = "Lô mặc định được gợi ý (hạn dùng gần nhất còn đủ số lượng)")
    private SuggestedLotItem suggestedLot;

    @Schema(description = "Danh sách phân bổ chi tiết các lô theo thứ tự FEFO (hỗ trợ trường hợp cần ghép nhiều lô)")
    private List<FefoAllocationItem> allocations;

    @Schema(description = "Danh sách tất cả các lô khả dụng để đổ vào Dropdown chọn lô cho thủ kho")
    private List<SuggestedLotItem> availableLots;

    @Schema(description = "Đã phân bổ đủ số lượng yêu cầu hay chưa")
    private Boolean isFullyAllocated;

    @Schema(description = "Tổng số lượng đã phân bổ được")
    private BigDecimal allocatedQuantity;

    @Schema(description = "Số lượng còn thiếu nếu không đủ tồn kho")
    private BigDecimal unallocatedQuantity;

    @Schema(description = "Cờ thông báo bắt buộc nhập lý do nếu chọn lô khác lô mặc định")
    private Boolean requiresReasonIfDifferent;

    @Schema(description = "Thông điệp diễn giải cho thủ kho")
    private String message;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Thông tin chi tiết một lô khả dụng")
    public static class SuggestedLotItem {
        private Long lotId;
        private String batchNumber;
        private LocalDate manufactureDate;
        private LocalDate expiredDate;
        private BigDecimal quantity;
        private BigDecimal availableQuantity;
        private Long daysToExpiration;
        private String expiryStatus; // SAFE, NEAR_EXPIRATION
        private Long locationId;
        private String locationCode;
        private String locationName;
        private String zone;
        private String shelf;
        private Boolean isSufficient; // Tồn của riêng lô này >= số lượng yêu cầu
        private Boolean isDefault;    // Là lô mặc định được gợi ý theo FEFO
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Phân bổ số lượng theo từng lô")
    public static class FefoAllocationItem {
        private Long lotId;
        private String batchNumber;
        private BigDecimal allocatedQuantity;
        private LocalDate expiredDate;
        private Long daysToExpiration;
        private String locationCode;
    }
}
