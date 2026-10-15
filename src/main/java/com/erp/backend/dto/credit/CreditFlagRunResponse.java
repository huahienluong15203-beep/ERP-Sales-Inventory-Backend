package com.erp.backend.dto.credit;

import java.time.LocalDateTime;

/** S7-04: Kết quả 1 lần quét cờ công nợ: số đại lý đã đánh giá, đang bị cờ, vừa gắn, vừa gỡ, lỗi. */
public record CreditFlagRunResponse(
        LocalDateTime runAt,
        int checked,
        int flagged,
        int newlyFlagged,
        int unflagged,
        int errors) {
}
