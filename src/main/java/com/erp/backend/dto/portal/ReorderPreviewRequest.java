package com.erp.backend.dto.portal;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** S5-02: Chọn dòng của đơn cũ để đặt lại; để trống = đặt lại toàn bộ. */
@Getter
@Setter
public class ReorderPreviewRequest {

    @Size(max = 200, message = "Tối đa 200 dòng")
    private List<Long> lineIds;
}
