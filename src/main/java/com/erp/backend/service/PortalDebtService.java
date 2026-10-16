package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.payment.CustomerInvoiceDebtResponse;
import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.dto.portal.PortalDebtSummaryResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Invoice;
import com.erp.backend.entity.InvoiceLine;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.InvoiceRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * S7-07: Đại lý tự xem công nợ của chính mình.
 * - Tổng còn nợ, hạn mức, hạn mức còn lại, nợ quá hạn (cùng cách tính với kiểm tra công nợ khi tạo đơn).
 * - Danh sách hoá đơn: số tiền, đã trả, còn nợ, hạn thanh toán, trạng thái; tải lại hoá đơn qua GET /api/invoices/{id}/print.
 * - Phiếu thu đã ghi nhận của đại lý.
 * Đại lý luôn bị khoá vào hồ sơ của chính mình (không nhận customerId từ client) để chống tra cứu chéo.
 */
@Service
@RequiredArgsConstructor
public class PortalDebtService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String FILTER_OPEN = "OPEN";
    static final String FILTER_OVERDUE = "OVERDUE";
    static final String FILTER_PAID = "PAID";
    static final String FILTER_ALL = "ALL";
    static final int MAX_PAGE_SIZE = 100;

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerCreditService creditService;
    private final PaymentService paymentService;

    Clock clock = Clock.system(VN_ZONE);

    @Transactional(readOnly = true)
    public PortalDebtSummaryResponse summary(UserDetailsImpl actor) {
        Customer c = currentCustomer(actor);
        CreditStatusResponse credit = creditService.evaluate(c, BigDecimal.ZERO);
        BigDecimal invoiced = money(invoiceRepository.sumOpenRemainingByCustomer(c.getId()));
        BigDecimal uninvoiced = money(credit.currentDebt()).subtract(invoiced).max(BigDecimal.ZERO.setScale(2));

        LocalDate today = LocalDate.now(clock);
        List<Invoice> open = invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(c.getId());
        LocalDate nextDue = null;
        BigDecimal nextDueAmount = BigDecimal.ZERO.setScale(2);
        if (c.getMaxDebtDays() != null && c.getMaxDebtDays() >= 0) {
            for (Invoice inv : open) {
                LocalDate due = dueDate(inv, c.getMaxDebtDays());
                if (due == null || due.isBefore(today)) {
                    continue; // đã quá hạn -> nằm trong overdueAmount
                }
                if (nextDue == null || due.isBefore(nextDue)) {
                    nextDue = due;
                    nextDueAmount = BigDecimal.ZERO.setScale(2);
                }
                if (due.equals(nextDue)) {
                    nextDueAmount = nextDueAmount.add(money(remaining(inv)));
                }
            }
        }
        return new PortalDebtSummaryResponse(c.getId(), c.getCode(), c.getName(), credit.creditLimit(),
                credit.currentDebt(), credit.availableCredit(), invoiced, uninvoiced, c.getMaxDebtDays(), open.size(),
                credit.overdue(), credit.overdueOrderCount(), credit.overdueAmount(), credit.overdueDays(),
                nextDue, nextDueAmount, credit.message());
    }

    /**
     * Hoá đơn của đại lý, mới nhất trước.
     * status: OPEN (còn nợ, mặc định) | OVERDUE (còn nợ đã quá hạn) | PAID (đã trả đủ) | ALL.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerInvoiceDebtResponse> invoices(String status, int page, int size, UserDetailsImpl actor) {
        Customer c = currentCustomer(actor);
        String filter = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : FILTER_OPEN;
        if (!List.of(FILTER_OPEN, FILTER_OVERDUE, FILTER_PAID, FILTER_ALL).contains(filter)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_INVOICE_FILTER",
                    "Bộ lọc hoá đơn: OPEN, OVERDUE, PAID hoặc ALL", "status");
        }
        LocalDate today = LocalDate.now(clock);
        Integer maxDays = c.getMaxDebtDays();
        if (FILTER_OVERDUE.equals(filter) && (maxDays == null || maxDays < 0)) {
            // Chưa đặt số ngày nợ tối đa thì không có hoá đơn nào bị tính quá hạn
            return PageResponse.of(Page.<CustomerInvoiceDebtResponse>empty(PageRequest.of(Math.max(0, page), safeSize(size))));
        }
        Long customerId = c.getId();
        Specification<Invoice> spec = (root, query, cb) -> {
            var remaining = cb.coalesce(root.<BigDecimal>get("remainingAmount"), root.<BigDecimal>get("totalAmount"));
            var byCustomer = cb.equal(root.get("customer").get("id"), customerId);
            return switch (filter) {
                case FILTER_OPEN -> cb.and(byCustomer, cb.gt(remaining, BigDecimal.ZERO));
                case FILTER_OVERDUE -> cb.and(byCustomer, cb.gt(remaining, BigDecimal.ZERO),
                        cb.lessThan(root.<LocalDate>get("invoiceDate"), today.minusDays(maxDays)));
                case FILTER_PAID -> cb.and(byCustomer, cb.le(remaining, BigDecimal.ZERO));
                default -> byCustomer;
            };
        };
        Page<Invoice> result = invoiceRepository.findAll(spec, PageRequest.of(Math.max(0, page), safeSize(size),
                Sort.by(Sort.Direction.DESC, "invoiceDate").and(Sort.by(Sort.Direction.DESC, "id"))));
        return PageResponse.of(result.map(inv -> toInvoice(inv, maxDays, today)));
    }

    /** Phiếu thu của đại lý (mới nhất trước); PaymentService tự khoá phạm vi về đúng đại lý đăng nhập. */
    @Transactional(readOnly = true)
    public PageResponse<PaymentReceiptResponse> payments(int page, int size, UserDetailsImpl actor) {
        Customer c = currentCustomer(actor);
        return paymentService.searchReceipts(c.getId(), null, null, null, null, null, Math.max(0, page),
                safeSize(size), actor);
    }

    static CustomerInvoiceDebtResponse toInvoice(Invoice inv, Integer maxDays, LocalDate today) {
        BigDecimal remaining = money(remaining(inv));
        LocalDate due = dueDate(inv, maxDays);
        boolean overdue = due != null && due.isBefore(today) && remaining.signum() > 0;
        long overdueDays = overdue ? ChronoUnit.DAYS.between(due, today) : 0L;
        List<String> orderCodes = inv.getLines() == null ? List.of() : inv.getLines().stream()
                .map(InvoiceLine::getOrderCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        String paymentStatus = remaining.signum() <= 0 ? Invoice.PAYMENT_STATUS_PAID
                : (inv.getPaymentStatus() != null ? inv.getPaymentStatus() : Invoice.PAYMENT_STATUS_UNPAID);
        BigDecimal total = money(inv.getTotalAmount());
        BigDecimal paid = inv.getPaidAmount() != null ? money(inv.getPaidAmount()) : total.subtract(remaining);
        return new CustomerInvoiceDebtResponse(inv.getId(), inv.getCode(), inv.getInvoiceDate(), due, orderCodes,
                total, paid, remaining, paymentStatus, overdue, overdueDays);
    }

    /** Hạn thanh toán = ngày hoá đơn + số ngày nợ tối đa (chưa đặt số ngày thì không có hạn). */
    static LocalDate dueDate(Invoice inv, Integer maxDays) {
        if (inv.getInvoiceDate() == null || maxDays == null || maxDays < 0) {
            return null;
        }
        return inv.getInvoiceDate().plusDays(maxDays);
    }

    private static BigDecimal remaining(Invoice inv) {
        return inv.getRemainingAmount() != null ? inv.getRemainingAmount() : inv.getTotalAmount();
    }

    private Customer currentCustomer(UserDetailsImpl actor) {
        Customer c = CustomerAccess.resolveCustomerForActor(actor, customerRepository);
        if (c == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND",
                    "Tài khoản chưa được gắn với hồ sơ đại lý", null);
        }
        return c;
    }

    private static int safeSize(int size) {
        return size > 0 ? Math.min(size, MAX_PAGE_SIZE) : 20;
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
