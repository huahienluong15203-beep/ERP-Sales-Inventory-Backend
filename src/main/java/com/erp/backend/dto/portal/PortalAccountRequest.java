package com.erp.backend.dto.portal;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S4-10: Gắn / gỡ tài khoản cổng đại lý (userId = null là gỡ). */
@Getter
@Setter
public class PortalAccountRequest {
    private Long userId;

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
