package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Customer;
import com.erp.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S7-04: Tự gắn / gỡ cờ cảnh báo công nợ cho đại lý.
 * - Gắn cờ khi công nợ hiện tại vượt hạn mức tiền, hoặc có khoản nợ quá số ngày nợ cho phép.
 * - Hết vi phạm thì tự gỡ cờ. Mỗi lần gắn / gỡ ghi nhật ký (phân hệ Hạn mức công nợ).
 * - Chạy mỗi ngày 1 lần cho mọi đại lý đang hoạt động (CreditFlagRunner / CreditFlagJob).
 * - Sau mỗi lần ghi nhận thu tiền (S7-02) gọi {@link #reevaluate(Long, String)} cho đại lý đó để đánh giá lại ngay.
 * - Đại lý bị gắn cờ: đơn mới bị chặn hoặc chuyển sang cần duyệt theo cấu hình erp.credit.flag-action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditFlagService {

    /** Cấu hình erp.credit.flag-action: chặn tạo đơn mới. */
    public static final String ACTION_BLOCK = "BLOCK";
    /** Cấu hình erp.credit.flag-action: vẫn tạo đơn nhưng khi chốt phải được QL kinh doanh duyệt (mặc định). */
    public static final String ACTION_REQUIRE_APPROVAL = "REQUIRE_APPROVAL";

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CustomerRepository customerRepository;
    private final CustomerCreditService creditService;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    /** Kết quả đánh giá 1 đại lý: đang bị gắn cờ không, lần này có đổi (gắn mới / gỡ) không. */
    public record Result(Long customerId, boolean flagged, boolean changed, String reason) {
    }

    /** true nếu cấu hình là chặn hẳn đơn mới (giá trị lạ / trống coi như cần duyệt). */
    public static boolean blocks(String action) {
        return ACTION_BLOCK.equalsIgnoreCase(action != null ? action.trim() : "");
    }

    /**
     * Đánh giá lại cờ công nợ của 1 đại lý (khoá dòng đại lý). trigger: DAILY_JOB / MANUAL / PAYMENT...
     * Gọi được từ chỗ khác (vd sau khi ghi nhận thu tiền) để cập nhật cờ ngay.
     */
    @Transactional
    public Result reevaluate(Long customerId, String trigger) {
        Customer c = customerRepository.findByIdForUpdate(customerId).orElse(null);
        if (c == null) {
            return new Result(customerId, false, false, null);
        }
        CreditStatusResponse s = creditService.evaluate(c, BigDecimal.ZERO);
        List<String> reasons = new ArrayList<>();
        BigDecimal limit = s.creditLimit() != null ? s.creditLimit() : BigDecimal.ZERO;
        BigDecimal debt = s.currentDebt() != null ? s.currentDebt() : BigDecimal.ZERO;
        if (debt.compareTo(limit) > 0) {
            reasons.add("Công nợ " + CustomerCreditService.vnd(debt) + " vượt hạn mức " + CustomerCreditService.vnd(limit)
                    + " là " + CustomerCreditService.vnd(debt.subtract(limit)));
        }
        if (s.overdue()) {
            reasons.add("Có " + s.overdueOrderCount() + " khoản nợ quá hạn " + s.overdueDays() + " ngày, tổng "
                    + CustomerCreditService.vnd(s.overdueAmount() != null ? s.overdueAmount() : BigDecimal.ZERO));
        }
        boolean flag = !reasons.isEmpty();
        String reason = flag ? cut(String.join("; ", reasons), 500) : null;
        boolean was = c.isCreditFlaggedNow();
        LocalDateTime now = LocalDateTime.now(clock);
        c.setCreditCheckedAt(now);

        boolean changed = flag != was;
        if (flag && !was) {
            c.setCreditFlagged(true);
            c.setCreditFlaggedAt(now);
            c.setCreditFlagReason(reason);
        } else if (!flag && was) {
            c.setCreditFlagged(false);
            c.setCreditFlaggedAt(null);
            c.setCreditFlagReason(null);
        } else if (flag && !Objects.equals(reason, c.getCreditFlagReason())) {
            // Vẫn bị cờ nhưng số liệu đổi (vd nợ tăng thêm): cập nhật lý do, giữ nguyên thời điểm gắn cờ
            c.setCreditFlagReason(reason);
        }
        customerRepository.save(c);
        if (changed) {
            auditLogService.record(AuditModule.DEBT_LIMIT, flag ? "CREDIT_FLAG_ON" : "CREDIT_FLAG_OFF", "CUSTOMER",
                    c.getId(), c.getCode(), was ? "FLAGGED" : "NORMAL", flag ? "FLAGGED" : "NORMAL",
                    cut((flag ? "Tự gắn cờ công nợ: " + reason : "Tự gỡ cờ công nợ: hết vượt hạn mức và nợ quá hạn")
                            + " (" + (trigger != null ? trigger : "SYSTEM") + ")", 500), null);
            log.info("S7-04: Đại lý {} {} cờ công nợ ({})", c.getCode(), flag ? "bị gắn" : "được gỡ", trigger);
        }
        return new Result(c.getId(), flag, changed, reason);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
