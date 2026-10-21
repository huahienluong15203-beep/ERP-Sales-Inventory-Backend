package com.erp.backend.dto.portal;

import java.math.BigDecimal;
import java.util.List;

/**
 * S5-02: Kết quả đặt lại đơn cũ: các dòng còn đặt được (giá áp lại theo bảng giá hiện hành, trong preview)
 * và các dòng bị loại kèm lý do. Đại lý xác nhận thì gửi keptLines qua POST /api/portal/orders.
 *
 * @param preview tính tiền đơn mới với keptLines (null nếu không còn dòng nào đặt được)
 */
public record ReorderPreviewResponse(
        Long sourceOrderId,
        String sourceOrderCode,
        List<PortalOrderLineRequest> keptLines,
        List<RemovedLine> removedLines,
        PortalOrderResponse preview) {

    public record RemovedLine(String productSku, String productName, String unitName, BigDecimal quantity, String reason) {
    }
}
