package com.erp.backend.dto.creditnote;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** S8-02: Lập chứng từ giảm trừ công nợ từ 1 phiếu trả hàng đã nhập kho. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditNoteCreateRequest {
    @NotNull(message = "Vui lòng chọn phiếu trả hàng")
    private Long salesReturnId;
    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
