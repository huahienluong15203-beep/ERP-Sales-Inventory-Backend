package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * S4-02: Kiểm tra công nợ khi tạo đơn.
 * - Công nợ hiện tại = tổng tiền các đơn đang nợ (SalesOrder.OUTSTANDING_DEBT_STATUSES).
 * - Tiền đơn + công nợ hiện tại > hạn mức -> đơn "cần duyệt" (không chặn, chuyển duyệt ở S4-05).
 * - Có khoản nợ quá số ngày nợ tối đa (maxDebtDays) -> chặn tạo đơn mới hoàn toàn.
 */
@Service
@RequiredArgsConstructor
public class CustomerCreditService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SalesOrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    // Cho phép test cố định "hôm nay"
    Clock clock = Clock.system(VN_ZONE);

    /** API xem công nợ của đại lý trên màn tạo đơn; orderAmount = tiền đơn đang gõ (có thể null). */
    @Transactional(readOnly = true)
    public CreditStatusResponse getStatus(Long customerId, BigDecimal orderAmount, UserDetailsImpl actor) {
        if (orderAmount != null && orderAmount.signum() < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_AMOUNT",
                    "Tiền đơn hàng không được âm", "orderAmount");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer);
        return evaluate(customer, orderAmount);
    }

    /** Tính tình trạng công nợ của đại lý nếu thêm một đơn có tổng tiền orderAmount. */
    @Transactional(readOnly = true)
    public CreditStatusResponse evaluate(Customer customer, BigDecimal orderAmount) {
        BigDecimal amount = money(orderAmount);
        BigDecimal limit = money(customer.getCreditLimit());
        BigDecimal currentDebt = money(orderRepository.sumTotalAmountByCustomerAndStatuses(
                customer.getId(), SalesOrder.OUTSTANDING_DEBT_STATUSES));
        BigDecimal available = limit.subtract(currentDebt);
        BigDecimal debtAfter = currentDebt.add(amount);
        boolean exceeds = amount.signum() > 0 && debtAfter.compareTo(limit) > 0;
        BigDecimal exceeded = exceeds ? debtAfter.subtract(limit) : BigDecimal.ZERO.setScale(2);

        Integer maxDays = customer.getMaxDebtDays();
        long overdueCount = 0;
        BigDecimal overdueAmount = BigDecimal.ZERO.setScale(2);
        long overdueDays = 0;
        if (maxDays != null && maxDays >= 0) {
            LocalDate today = LocalDate.now(clock);
            // Đơn duyệt ngày D có hạn trả D + maxDays; quá hạn khi hạn trả < hôm nay
            LocalDateTime dueBefore = today.minusDays(maxDays).atStartOfDay();
            overdueCount = orderRepository.countByCustomer_IdAndStatusInAndApprovedAtBefore(
                    customer.getId(), SalesOrder.OUTSTANDING_DEBT_STATUSES, dueBefore);
            if (overdueCount > 0) {
                overdueAmount = money(orderRepository.sumOverdueAmount(
                        customer.getId(), SalesOrder.OUTSTANDING_DEBT_STATUSES, dueBefore));
                overdueDays = orderRepository.findFirstByCustomer_IdAndStatusInAndApprovedAtBeforeOrderByApprovedAtAsc(
                                customer.getId(), SalesOrder.OUTSTANDING_DEBT_STATUSES, dueBefore)
                        .map(o -> ChronoUnit.DAYS.between(o.getApprovedAt().toLocalDate().plusDays(maxDays), today))
                        .orElse(0L);
            }
        }
        boolean overdue = overdueCount > 0;

        String message = null;
        if (overdue) {
            message = "Đại lý " + customer.getName() + " (" + customer.getCode() + ") có " + overdueCount
                    + " khoản nợ quá hạn " + overdueDays + " ngày (quá " + maxDays + " ngày cho phép), tổng "
                    + vnd(overdueAmount) + ". Chặn tạo đơn mới cho đến khi thu hồi công nợ.";
        } else if (exceeds) {
            message = "Công nợ sau đơn " + vnd(debtAfter) + " vượt hạn mức " + vnd(limit) + " là "
                    + vnd(exceeded) + ". Đơn cần được duyệt trước khi xử lý.";
        }
        return new CreditStatusResponse(customer.getId(), customer.getCode(), customer.getName(),
                limit, currentDebt, available, amount, debtAfter, exceeds, exceeded, maxDays,
                overdue, overdueCount, overdueAmount, overdueDays, overdue, message);
    }

    /** Có khoản nợ quá hạn thì chặn tạo đơn mới (409 để Frontend không tự đăng xuất). */
    @Transactional(readOnly = true)
    public void assertNoOverdueDebt(Customer customer) {
        CreditStatusResponse status = evaluate(customer, BigDecimal.ZERO);
        if (status.overdue()) {
            throw BusinessException.conflict("CUSTOMER_DEBT_OVERDUE", status.message(), "customerId");
        }
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /** Định dạng 1.234.567 ₫ */
    static String vnd(BigDecimal v) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        DecimalFormat f = new DecimalFormat("#,##0", symbols);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v) + " ₫";
    }
}
