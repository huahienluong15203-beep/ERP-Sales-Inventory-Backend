package com.erp.backend.service;

import com.erp.backend.dto.payment.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * S7-02: Dịch vụ ghi nhận thanh toán và cấn trừ công nợ đại lý.
 * - AC1: Ghi số tiền, hình thức thu, ngày thu, người thu, ghi chú.
 * - AC2: Cấn trừ tự động theo FIFO (hoá đơn cũ nhất trước) hoặc chỉ định vào hoá đơn cụ thể.
 * - AC3: Cho phép thu một phần; hoá đơn chuyển trạng thái sang PARTIALLY_PAID hoặc PAID.
 * - AC4: Không cho thu vượt tổng công nợ còn lại (FIFO) hoặc nợ còn lại của hoá đơn chỉ định.
 * - AC5: Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final PaymentReceiptRepository paymentReceiptRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final PaymentSequenceRepository sequenceRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final CreditFlagService creditFlagService;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    /**
     * Tạo phiếu thu và thực hiện cấn trừ công nợ.
     */
    @Transactional
    public PaymentReceiptResponse createReceipt(PaymentReceiptCreateRequest request, UserDetailsImpl actor) {
        if (request == null) {
            throw BusinessException.badRequest("INVALID_PAYMENT_DATA", "Dữ liệu tạo phiếu thu không hợp lệ");
        }
        if (request.getCustomerId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CUSTOMER_REQUIRED", "Vui lòng chọn đại lý", "customerId");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Số tiền thu phải lớn hơn 0", "amount");
        }
        if (!StringUtils.hasText(request.getCollectorName())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "COLLECTOR_REQUIRED", "Vui lòng nhập tên người thu tiền", "collectorName");
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate paymentDate = request.getPaymentDate() != null ? request.getPaymentDate() : today;
        if (paymentDate.isAfter(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_DATE_FUTURE", "Ngày thu tiền không được ở tương lai", "paymentDate");
        }

        // Khoá dòng đại lý
        Customer customer = customerRepository.findByIdForUpdate(request.getCustomerId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer, customerRepository);

        BigDecimal receiptAmount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        String allocationType = StringUtils.hasText(request.getAllocationType())
                ? request.getAllocationType().trim().toUpperCase()
                : PaymentReceipt.ALLOCATION_FIFO;

        // Sinh mã phiếu thu (PT2026-000001)
        int year = paymentDate.getYear();
        sequenceRepository.ensureYear(year);
        PaymentSequence seq = sequenceRepository.findForUpdate(year)
                .orElseThrow(() -> new IllegalStateException("Không khởi tạo được bộ đếm số phiếu thu năm " + year));
        long nextSeq = (seq.getLastNumber() != null ? seq.getLastNumber() : 0L) + 1;
        seq.setLastNumber(nextSeq);
        sequenceRepository.save(seq);
        String receiptCode = formatReceiptCode(year, nextSeq);

        LocalDateTime now = LocalDateTime.now(clock);

        PaymentReceipt receipt = PaymentReceipt.builder()
                .code(receiptCode)
                .customer(customer)
                .customerCode(customer.getCode())
                .customerName(customer.getName())
                .customerAddress(customer.getAddress())
                .customerPhone(customer.getPhone())
                .amount(receiptAmount)
                .paymentMethod(StringUtils.hasText(request.getPaymentMethod()) ? request.getPaymentMethod() : PaymentReceipt.METHOD_CASH)
                .paymentDate(paymentDate)
                .collectorName(request.getCollectorName().trim())
                .collectorUsername(actor != null ? actor.getUsername() : null)
                .collectorId(actor != null ? actor.getId() : null)
                .note(StringUtils.hasText(request.getNotes()) ? request.getNotes().trim() : null)
                .status(PaymentReceipt.STATUS_CONFIRMED)
                .allocationType(allocationType)
                .receiptImageUrl(request.getReceiptImageUrl())
                .confirmedAt(now)
                .confirmedByUsername(actor != null ? actor.getUsername() : null)
                .createdAt(now)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .build();

        // Thuật toán cấn trừ công nợ (SCRUM-259)
        if (PaymentReceipt.ALLOCATION_SPECIFIC.equalsIgnoreCase(allocationType)
                || "MANUAL".equalsIgnoreCase(allocationType)) {
            applySpecificAllocation(receipt, request.getSpecificAllocations(), receiptAmount, customer);
        } else {
            applyFifoAllocation(receipt, receiptAmount, customer);
        }

        PaymentReceipt saved = paymentReceiptRepository.save(receipt);

        // Đánh giá lại cờ công nợ cho đại lý (nếu hết nợ quá hạn/vượt hạn mức -> tự động gỡ cờ)
        try {
            creditFlagService.reevaluate(customer.getId(), "PAYMENT");
        } catch (Exception e) {
            log.warn("Lỗi khi đánh giá lại cờ công nợ cho đại lý {}: {}", customer.getCode(), e.getMessage());
        }

        // Ghi nhật ký hệ thống (Audit Log)
        auditLogService.record(
                AuditModule.INVOICE,
                "CREATE_PAYMENT_RECEIPT",
                "PAYMENT_RECEIPT",
                saved.getId(),
                saved.getCode(),
                null,
                "Thu tiền=" + vnd(saved.getAmount()) + "; HT=" + saved.getPaymentMethod() + "; Loại=" + saved.getAllocationType(),
                "Ghi nhận thanh toán và cấn trừ công nợ cho đại lý " + customer.getCode(),
                actor
        );

        return toReceiptResponse(saved);
    }

    /**
     * Thuật toán cấn trừ tự động theo FIFO (hoá đơn cũ nhất cấn trừ trước) (SCRUM-259 & SCRUM-234).
     */
    private void applyFifoAllocation(PaymentReceipt receipt, BigDecimal receiptAmount, Customer customer) {
        List<Invoice> unpaidInvoices = invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(customer.getId());

        BigDecimal totalRemainingDebt = unpaidInvoices.stream()
                .map(i -> i.getRemainingAmount() != null ? i.getRemainingAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // AC4: Không cho thu vượt tổng công nợ còn lại
        if (receiptAmount.compareTo(totalRemainingDebt) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AMOUNT_EXCEEDS_TOTAL_DEBT",
                    "Số tiền thu (" + vnd(receiptAmount) + ") vượt quá tổng công nợ còn lại ("
                            + vnd(totalRemainingDebt) + ") của đại lý", "amount");
        }

        BigDecimal unallocated = receiptAmount;
        for (Invoice invoice : unpaidInvoices) {
            if (unallocated.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal invoiceRemaining = invoice.getRemainingAmount() != null ? invoice.getRemainingAmount() : BigDecimal.ZERO;
            if (invoiceRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal allocated = unallocated.min(invoiceRemaining);
            BigDecimal remainingBefore = invoiceRemaining;

            // Cập nhật số tiền đã thanh toán, còn nợ và trạng thái của hoá đơn (AC3)
            invoice.applyPayment(allocated);
            BigDecimal remainingAfter = invoice.getRemainingAmount();

            PaymentAllocation allocation = PaymentAllocation.builder()
                    .paymentReceipt(receipt)
                    .invoice(invoice)
                    .invoiceCode(invoice.getCode())
                    .orderCode(extractFirstOrderCode(invoice))
                    .allocatedAmount(allocated)
                    .invoiceTotal(invoice.getTotalAmount())
                    .remainingBefore(remainingBefore)
                    .remainingAfter(remainingAfter)
                    .build();

            receipt.addAllocation(allocation);
            unallocated = unallocated.subtract(allocated);
        }

        invoiceRepository.saveAll(unpaidInvoices);
    }

    /**
     * Thuật toán cấn trừ chỉ định vào các hoá đơn cụ thể.
     */
    private void applySpecificAllocation(PaymentReceipt receipt, List<PaymentAllocationItemRequest> items,
                                         BigDecimal receiptAmount, Customer customer) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SPECIFIC_ALLOCATIONS_REQUIRED",
                    "Vui lòng chọn danh sách hoá đơn đối trừ cụ thể", "specificAllocations");
        }

        BigDecimal totalAllocated = items.stream()
                .map(it -> it.getAmount() != null ? it.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        if (totalAllocated.compareTo(receiptAmount) != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ALLOCATION_AMOUNT_MISMATCH",
                    "Tổng số tiền phân bổ cho các hoá đơn (" + vnd(totalAllocated) + ") phải bằng đúng số tiền thu ("
                            + vnd(receiptAmount) + ")", "specificAllocations");
        }

        List<Long> invoiceIds = items.stream().map(PaymentAllocationItemRequest::getInvoiceId).distinct().toList();
        List<Invoice> invoices = invoiceRepository.findAllByIdForUpdate(invoiceIds);
        Map<Long, Invoice> invoiceMap = new HashMap<>();
        for (Invoice inv : invoices) {
            invoiceMap.put(inv.getId(), inv);
        }

        for (PaymentAllocationItemRequest item : items) {
            Invoice inv = invoiceMap.get(item.getInvoiceId());
            if (inv == null) {
                throw BusinessException.notFound("Không tìm thấy hoá đơn có ID: " + item.getInvoiceId());
            }
            if (inv.getCustomer() == null || !Objects.equals(inv.getCustomer().getId(), customer.getId())) {
                throw BusinessException.badRequest("INVOICE_CUSTOMER_MISMATCH",
                        "Hoá đơn " + inv.getCode() + " không thuộc về đại lý " + customer.getCode());
            }

            BigDecimal allocAmount = item.getAmount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal remainingBefore = inv.getRemainingAmount() != null ? inv.getRemainingAmount() : BigDecimal.ZERO;

            // AC4: Không cho cấn trừ vượt nợ còn lại của hoá đơn
            if (allocAmount.compareTo(remainingBefore) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "AMOUNT_EXCEEDS_INVOICE_DEBT",
                        "Số tiền cấn trừ (" + vnd(allocAmount) + ") vượt quá số nợ còn lại ("
                                + vnd(remainingBefore) + ") của hoá đơn " + inv.getCode(), "specificAllocations");
            }

            inv.applyPayment(allocAmount);
            BigDecimal remainingAfter = inv.getRemainingAmount();

            PaymentAllocation allocation = PaymentAllocation.builder()
                    .paymentReceipt(receipt)
                    .invoice(inv)
                    .invoiceCode(inv.getCode())
                    .orderCode(extractFirstOrderCode(inv))
                    .allocatedAmount(allocAmount)
                    .invoiceTotal(inv.getTotalAmount())
                    .remainingBefore(remainingBefore)
                    .remainingAfter(remainingAfter)
                    .build();

            receipt.addAllocation(allocation);
        }

        invoiceRepository.saveAll(invoices);
    }

    /**
     * Lấy danh sách hoá đơn còn nợ của đại lý để kế toán chọn khi đối trừ.
     */
    @Transactional(readOnly = true)
    public List<CustomerInvoiceDebtResponse> getCustomerInvoicesForDebt(Long customerId, UserDetailsImpl actor) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer, customerRepository);

        List<Invoice> invoices = invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(customerId);
        LocalDate today = LocalDate.now(clock);
        Integer maxDebtDays = customer.getMaxDebtDays();

        return invoices.stream().map(inv -> {
            LocalDate dueDate = (maxDebtDays != null && maxDebtDays > 0)
                    ? inv.getInvoiceDate().plusDays(maxDebtDays)
                    : inv.getInvoiceDate();

            BigDecimal remaining = inv.getRemainingAmount() != null ? inv.getRemainingAmount() : BigDecimal.ZERO;
            boolean isOverdue = today.isAfter(dueDate) && remaining.compareTo(BigDecimal.ZERO) > 0;
            long overdueDays = isOverdue ? ChronoUnit.DAYS.between(dueDate, today) : 0L;

            List<String> orderCodes = inv.getLines().stream()
                    .map(InvoiceLine::getOrderCode)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            return new CustomerInvoiceDebtResponse(
                    inv.getId(),
                    inv.getCode(),
                    inv.getInvoiceDate(),
                    dueDate,
                    orderCodes,
                    inv.getTotalAmount(),
                    inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO,
                    remaining,
                    inv.getPaymentStatus() != null ? inv.getPaymentStatus() : Invoice.PAYMENT_STATUS_UNPAID,
                    isOverdue,
                    overdueDays
            );
        }).toList();
    }

    /**
     * Tìm kiếm và phân trang danh sách phiếu thu.
     */
    @Transactional(readOnly = true)
    public PageResponse<PaymentReceiptResponse> searchReceipts(Long customerId, String paymentMethod, String status,
                                                              LocalDate fromDate, LocalDate toDate, String keyword,
                                                              int page, int size, UserDetailsImpl actor) {
        int safePage = Math.max(0, page);
        int safeSize = size > 0 && size <= 100 ? size : 20;

        Specification<PaymentReceipt> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (customerId != null) {
                ps.add(cb.equal(root.get("customer").get("id"), customerId));
            }
            if (StringUtils.hasText(paymentMethod) && !"ALL".equalsIgnoreCase(paymentMethod)) {
                ps.add(cb.equal(root.get("paymentMethod"), paymentMethod.trim()));
            }
            if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status)) {
                ps.add(cb.equal(root.get("status"), status.trim()));
            }
            if (fromDate != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), fromDate));
            }
            if (toDate != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("paymentDate"), toDate));
            }
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(root.get("customerCode")), like),
                        cb.like(cb.lower(root.get("customerName")), like),
                        cb.like(cb.lower(root.get("collectorName")), like)
                ));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        var pageResult = paymentReceiptRepository.findAll(
                spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "paymentDate", "id"))
        );

        return PageResponse.of(pageResult.map(this::toReceiptResponse));
    }

    /**
     * Xem chi tiết 1 phiếu thu và các dòng phân bổ cấn trừ.
     */
    @Transactional(readOnly = true)
    public PaymentReceiptResponse getReceipt(Long id, UserDetailsImpl actor) {
        PaymentReceipt receipt = paymentReceiptRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu thu"));
        CustomerAccess.checkCanAccess(actor, receipt.getCustomer(), customerRepository);
        return toReceiptResponse(receipt);
    }

    // ================== HELPER METHODS ==================

    private PaymentReceiptResponse toReceiptResponse(PaymentReceipt r) {
        List<PaymentAllocationResponse> allocResponses = r.getAllocations().stream()
                .map(a -> new PaymentAllocationResponse(
                        a.getInvoice() != null ? a.getInvoice().getId() : null,
                        a.getInvoiceCode(),
                        a.getOrderCode(),
                        a.getAllocatedAmount(),
                        a.getInvoiceTotal(),
                        a.getRemainingBefore(),
                        a.getRemainingAfter()
                ))
                .toList();

        return new PaymentReceiptResponse(
                r.getId(),
                r.getCode(),
                r.getCustomer() != null ? r.getCustomer().getId() : null,
                r.getCustomerCode(),
                r.getCustomerName(),
                r.getCustomerAddress(),
                r.getCustomerPhone(),
                r.getAmount(),
                r.getPaymentMethod(),
                r.getPaymentDate(),
                r.getCollectorName(),
                r.getCollectorUsername(),
                r.getCollectorId(),
                r.getNote(),
                r.getStatus(),
                r.getReceiptImageUrl(),
                r.getConfirmedAt(),
                r.getConfirmedByUsername(),
                r.getAllocationType(),
                allocResponses,
                r.getCreatedAt(),
                r.getCreatedByUsername()
        );
    }

    private static String extractFirstOrderCode(Invoice invoice) {
        if (invoice.getLines() == null || invoice.getLines().isEmpty()) {
            return null;
        }
        return invoice.getLines().stream()
                .map(InvoiceLine::getOrderCode)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    static String formatReceiptCode(int year, long number) {
        return String.format("PT%d-%06d", year, number);
    }

    static String vnd(BigDecimal v) {
        if (v == null) {
            v = BigDecimal.ZERO;
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        DecimalFormat f = new DecimalFormat("#,##0", symbols);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v) + " ₫";
    }
}
