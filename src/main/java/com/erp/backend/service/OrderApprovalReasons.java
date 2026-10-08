package com.erp.backend.service;

import com.erp.backend.dto.order.ApprovalReason;
import com.erp.backend.entity.SalesOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** S4-05: Dựng danh sách lý do cần duyệt + mức vi phạm từ các cột đã lưu trên đơn lúc chốt. */
final class OrderApprovalReasons {

    private OrderApprovalReasons() {
    }

    static List<ApprovalReason> of(SalesOrder o) {
        List<ApprovalReason> reasons = new ArrayList<>();
        if (o.getCreditExceededAmount() != null && o.getCreditExceededAmount().signum() > 0) {
            String pct = o.getCreditExceededPercent() != null ? " (" + percent(o.getCreditExceededPercent()) + " hạn mức)" : "";
            reasons.add(new ApprovalReason(ApprovalReason.CREDIT_LIMIT, "Vượt hạn mức công nợ",
                    "Công nợ sau đơn vượt hạn mức " + CustomerCreditService.vnd(o.getCreditExceededAmount()) + pct,
                    o.getCreditExceededAmount(), o.getCreditExceededPercent()));
        }
        if (o.getBelowFloorLineCount() != null && o.getBelowFloorLineCount() > 0) {
            String amount = o.getBelowFloorAmount() != null ? CustomerCreditService.vnd(o.getBelowFloorAmount()) : "0 ₫";
            String pct = o.getBelowFloorMaxPercent() != null
                    ? ", dòng thấp nhất dưới giá sàn " + percent(o.getBelowFloorMaxPercent()) : "";
            reasons.add(new ApprovalReason(ApprovalReason.BELOW_FLOOR_PRICE, "Bán dưới giá sàn",
                    o.getBelowFloorLineCount() + " dòng hàng bán dưới giá sàn, thiếu " + amount + " so với giá sàn" + pct,
                    o.getBelowFloorAmount(), o.getBelowFloorMaxPercent()));
        }
        return List.copyOf(reasons);
    }

    /** 12.5 -> "12,5%" */
    static String percent(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',') + "%";
    }
}
