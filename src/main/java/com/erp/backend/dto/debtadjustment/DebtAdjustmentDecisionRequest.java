package com.erp.backend.dto.debtadjustment;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** S8-02: Body duyệt { approvalNote } / từ chối { reason }. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DebtAdjustmentDecisionRequest {
    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String approvalNote;
    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
