package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * DTO yêu cầu kiểm tra tính hợp lệ khi thủ kho chọn lô xuất kho (S6-04).
 * Hỗ trợ xác thực lý do bắt buộc khi chọn lô khác lô gợi ý FEFO.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu kiểm tra khi thủ kho chọn lô xuất kho (S6-04)")
public class LotSelectionValidationRequest {

    @NotNull(message = "Mã sản phẩm không được để trống")
    @Schema(description = "ID sản phẩm")
    private Long productId;

    @NotNull(message = "Mã kho không được để trống")
    @Schema(description = "ID kho xuất")
    private Long warehouseId;

    @NotNull(message = "Số lượng yêu cầu không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng yêu cầu phải lớn hơn 0")
    @Schema(description = "Số lượng cần xuất (theo ĐVT cơ sở)")
    private BigDecimal requestedQuantity;

    @NotBlank(message = "Số lô được chọn không được để trống")
    @Schema(description = "Số lô mà thủ kho chọn xuất")
    private String selectedBatchNumber;

    @Schema(description = "Số lô mặc định do FEFO gợi ý (nếu có, dùng để đối chiếu phát hiện đổi lô)")
    private String suggestedBatchNumber;

    @Size(max = 500, message = "Lý do đổi lô tối đa 500 ký tự")
    @Schema(description = "Lý do chọn lô khác lô gợi ý (BẮT BUỘC nếu selectedBatchNumber != suggestedBatchNumber)")
    private String lotChangeReason;

    @Schema(description = "Xác nhận ghi đè cho phép xuất nếu lô đã quá hạn (chỉ WH_MANAGER / ADMIN)")
    private Boolean overrideExpired;

    @Size(max = 500, message = "Lý do ghi đè xuất quá hạn tối đa 500 ký tự")
    @Schema(description = "Lý do ghi đè xuất lô quá hạn")
    private String overrideReason;
}
