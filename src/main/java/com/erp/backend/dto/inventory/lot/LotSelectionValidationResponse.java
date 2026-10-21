package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO kết quả kiểm tra tính hợp lệ khi thủ kho chọn lô xuất kho (S6-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả kiểm tra chọn lô xuất kho (S6-04)")
public class LotSelectionValidationResponse {

    @Schema(description = "Hợp lệ để xuất kho hay không")
    private boolean valid;

    @Schema(description = "Thủ kho có chọn lô khác lô gợi ý FEFO hay không")
    private boolean isDifferentFromSuggested;

    @Schema(description = "Có bắt buộc phải nhập lý do hay không")
    private boolean requiresReason;

    @Schema(description = "Lý do nhập vào có hợp lệ hay không (tối thiểu 5 ký tự)")
    private boolean reasonValid;

    @Schema(description = "Lô có bị khóa (LOCKED) không")
    private boolean locked;

    @Schema(description = "Lô đã quá hạn sử dụng hay chưa")
    private boolean expired;

    @Schema(description = "Lô có sắp hết hạn không (ngưỡng 30 ngày)")
    private boolean nearExpiry;

    @Schema(description = "Tồn kho trong lô có bị thiếu so với số lượng yêu cầu không")
    private boolean insufficientQuantity;

    @Schema(description = "Có áp dụng ghi đè quá hạn hay không")
    private boolean overrideApplied;

    @Schema(description = "Số lô mặc định theo FEFO")
    private String suggestedBatchNumber;

    @Schema(description = "Số lô được chọn")
    private String selectedBatchNumber;

    @Schema(description = "Hạn sử dụng của lô được chọn")
    private LocalDate expiredDate;

    @Schema(description = "Số ngày tới hạn sử dụng")
    private Long daysToExpiration;

    @Schema(description = "Số lượng tồn khả dụng trong lô")
    private BigDecimal availableQuantity;

    @Schema(description = "Số lượng yêu cầu xuất")
    private BigDecimal requestedQuantity;

    @Schema(description = "Thông điệp phản hồi")
    private String message;
}
