package com.erp.backend.service;

import com.erp.backend.dto.creditnote.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S8-02: Giảm trừ công nợ khi trả hàng.
 * - Chứng từ điều chỉnh sinh từ phiếu trả hàng ĐÃ được thủ kho xác nhận nhận hàng (RECEIVED), 1 phiếu trả = 1 chứng từ,
 *   giữ liên kết tới hoá đơn gốc và phiếu trả.
 * - Tiền giảm trừ theo GIÁ ĐÃ BÁN trên hoá đơn gốc: thành tiền dòng hoá đơn (sau chiết khấu) × SL trả / SL trên hoá đơn,
 *   không dùng giá hiện hành. Trả nốt phần còn lại của dòng thì lấy đúng phần tiền còn lại để tổng không lệch 1 đồng.
 * - Hoá đơn gốc giữ nguyên dòng hàng và tổng tiền; chỉ giảm SỐ CÒN NỢ (hoá đơn gốc trước, dư thì đối trừ các hoá đơn
 *   còn nợ cũ nhất). Không đối trừ hết thì phần dư là số dư có của đại lý, được trừ vào công nợ.
 * - Công nợ, hạn mức khả dụng, cờ công nợ của đại lý cập nhật ngay trong cùng transaction (khoá dòng đại lý như khi thu tiền).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditNoteService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final CreditNoteRepository creditNoteRepository;
    private final CreditNoteSequenceRepository sequenceRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final CreditFlagService creditFlagService;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= LẬP CHỨNG TỪ =======================

    @Transactional
    public CreditNoteResponse createFromReturn(CreditNoteCreateRequest req, UserDetailsImpl actor) {
        if (req == null || req.getSalesReturnId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SALES_RETURN_REQUIRED", "Vui lòng chọn phiếu trả hàng",
                    "salesReturnId");
        }
        String note = StringUtils.hasText(req.getNote()) ? req.getNote().trim() : null;
        if (note != null && note.length() > 500) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG", "Ghi chú tối đa 500 ký tự", "note");
        }

        // Khoá phiếu trả để 2 kế toán không lập 2 chứng từ cho cùng 1 phiếu
        SalesReturn ret = salesReturnRepository.findByIdForUpdate(req.getSalesReturnId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu trả hàng"));
        if (!SalesReturn.STATUS_RECEIVED.equals(ret.getStatus())) {
            throw BusinessException.conflict("SALES_RETURN_NOT_RECEIVED",
                    "Phiếu trả " + ret.getCode() + " chưa được thủ kho xác nhận nhận hàng (trạng thái " + ret.getStatus()
                            + "), chưa lập được chứng từ giảm trừ", "salesReturnId");
        }
        if (creditNoteRepository.existsBySalesReturn_Id(ret.getId())) {
            throw BusinessException.conflict("CREDIT_NOTE_EXISTS",
                    "Phiếu trả " + ret.getCode() + " đã có chứng từ giảm trừ", "salesReturnId");
        }
        Invoice original = ret.getInvoice();
        if (original == null || original.getCustomer() == null) {
            throw BusinessException.conflict("INVOICE_REQUIRED", "Phiếu trả không gắn với hoá đơn gốc", "salesReturnId");
        }
        // Khoá dòng đại lý (giống ghi nhận thanh toán) để đối trừ công nợ không chạy song song với thu tiền
        Customer customer = customerRepository.findByIdForUpdate(original.getCustomer().getId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));

        LocalDateTime now = LocalDateTime.now(clock);
        CreditNote cn = CreditNote.builder()
                .salesReturn(ret)
                .salesReturnCode(ret.getCode())
                .invoice(original)
                .invoiceCode(original.getCode())
                .customer(customer)
                .customerCode(customer.getCode())
                .customerName(customer.getName())
                .noteDate(now.toLocalDate())
                .returnReason(ret.getReturnReason())
                .note(note)
                .createdAt(now)
                .createdById(actor != null ? actor.getId() : null)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .build();

        BigDecimal total = ZERO;
        int lineNo = 1;
        for (SalesReturnLine rl : ret.getLines()) {
            InvoiceLine il = rl.getInvoiceLine();
            BigDecimal amount = lineCredit(il.getQuantity(), il.getNetAmount(),
                    creditNoteRepository.sumCreditedQuantityByInvoiceLine(il.getId()),
                    creditNoteRepository.sumCreditedAmountByInvoiceLine(il.getId()), rl.getReturnQuantity());
            cn.addLine(CreditNoteLine.builder()
                    .lineNo(lineNo++)
                    .salesReturnLineId(rl.getId())
                    .invoiceLineId(il.getId())
                    .productSku(il.getProductSku())
                    .productName(il.getProductName())
                    .unitName(il.getUnitName())
                    .returnQuantity(rl.getReturnQuantity())
                    .invoiceQuantity(il.getQuantity())
                    .invoiceNetAmount(money(il.getNetAmount()))
                    .amount(amount)
                    .build());
            total = total.add(amount);
        }
        if (total.signum() <= 0) {
            throw BusinessException.conflict("NOTHING_TO_CREDIT",
                    "Phiếu trả " + ret.getCode() + " không còn số tiền nào để giảm trừ", "salesReturnId");
        }

        BigDecimal applied = allocate(cn, total, original, customer.getId(), actor);
        cn.setAmount(total);
        cn.setAppliedAmount(applied);
        cn.setUnappliedAmount(total.subtract(applied));
        cn.setCode(nextCode(cn.getNoteDate().getYear()));

        CreditNote saved = creditNoteRepository.save(cn);

        auditLogService.record(AuditModule.INVOICE, "CREATE_CREDIT_NOTE", "CREDIT_NOTE", saved.getId(), saved.getCode(),
                null, "Giảm trừ=" + CustomerCreditService.vnd(total) + "; đã đối trừ="
                        + CustomerCreditService.vnd(applied) + "; số dư có=" + CustomerCreditService.vnd(saved.getUnappliedAmount()),
                cut("Giảm trừ công nợ đại lý " + customer.getCode() + " theo phiếu trả " + ret.getCode() + ", hoá đơn gốc "
                        + original.getCode() + (note != null ? ". " + note : ""), 500), actor);

        try {
            creditFlagService.reevaluate(customer.getId(), "CREDIT_NOTE");
        } catch (Exception e) {
            log.warn("S8-02: Lỗi đánh giá lại cờ công nợ cho đại lý {}: {}", customer.getCode(), e.getMessage());
        }
        log.info("S8-02: Lập chứng từ giảm trừ {} cho phiếu trả {}: {}", saved.getCode(), ret.getCode(), total);
        return toResponse(saved);
    }

    /**
     * Tiền giảm trừ của 1 dòng trả theo giá đã bán trên dòng hoá đơn gốc.
     * Trả nốt phần còn lại của dòng -> lấy đúng số tiền còn lại (tránh lệch do làm tròn); không vượt phần còn lại.
     */
    static BigDecimal lineCredit(BigDecimal invoiceQty, BigDecimal invoiceNet, BigDecimal creditedQty,
                                 BigDecimal creditedAmount, BigDecimal returnQty) {
        BigDecimal qty = nz(invoiceQty);
        BigDecimal net = money(invoiceNet);
        BigDecimal remainingAmount = net.subtract(money(creditedAmount)).max(ZERO);
        if (qty.signum() <= 0 || returnQty == null || returnQty.signum() <= 0) {
            return ZERO;
        }
        BigDecimal afterQty = nz(creditedQty).add(returnQty);
        if (afterQty.compareTo(qty) >= 0) {
            return remainingAmount;
        }
        BigDecimal amount = net.multiply(returnQty).divide(qty, 2, RoundingMode.HALF_UP);
        return amount.min(remainingAmount);
    }

    /** Đối trừ vào hoá đơn gốc trước, dư thì vào các hoá đơn còn nợ cũ nhất của đại lý. Trả về tổng đã đối trừ. */
    private BigDecimal allocate(CreditNote cn, BigDecimal total, Invoice original, Long customerId, UserDetailsImpl actor) {
        Map<Long, Invoice> targets = new LinkedHashMap<>();
        targets.put(original.getId(), original);
        for (Invoice inv : invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(customerId)) {
            targets.putIfAbsent(inv.getId(), inv);
        }
        // Khoá dòng các hoá đơn sẽ bị giảm số còn nợ
        Map<Long, Invoice> locked = new LinkedHashMap<>();
        for (Invoice inv : invoiceRepository.findAllByIdForUpdate(new ArrayList<>(targets.keySet()))) {
            locked.put(inv.getId(), inv);
        }
        BigDecimal left = total;
        BigDecimal applied = ZERO;
        for (Long id : targets.keySet()) {
            if (left.signum() <= 0) {
                break;
            }
            Invoice inv = locked.getOrDefault(id, targets.get(id));
            BigDecimal before = money(inv.getRemainingAmount() != null ? inv.getRemainingAmount() : inv.getTotalAmount());
            if (before.signum() <= 0) {
                continue;
            }
            BigDecimal take = left.min(before);
            BigDecimal after = before.subtract(take);
            inv.setRemainingAmount(after);
            inv.setPaymentStatus(after.signum() == 0 ? Invoice.PAYMENT_STATUS_PAID : Invoice.PAYMENT_STATUS_PARTIALLY_PAID);
            invoiceRepository.save(inv);
            cn.addAllocation(CreditNoteAllocation.builder()
                    .invoice(inv)
                    .invoiceCode(inv.getCode())
                    .amount(take)
                    .remainingBefore(before)
                    .remainingAfter(after)
                    .build());
            auditLogService.record(AuditModule.INVOICE, "CREDIT_NOTE_OFFSET", "INVOICE", inv.getId(), inv.getCode(),
                    before.toPlainString(), after.toPlainString(),
                    cut("Giảm số còn nợ do trả hàng (phiếu trả " + cn.getSalesReturnCode() + ")", 500), actor);
            left = left.subtract(take);
            applied = applied.add(take);
        }
        return applied;
    }

    private String nextCode(int year) {
        sequenceRepository.ensureYear(year);
        CreditNoteSequence seq = sequenceRepository.findForUpdate(year)
                .orElseThrow(() -> new IllegalStateException("Không khởi tạo được bộ đếm số chứng từ giảm trừ năm " + year));
        long next = (seq.getLastNumber() != null ? seq.getLastNumber() : 0L) + 1;
        seq.setLastNumber(next);
        sequenceRepository.save(seq);
        return String.format("GG%d-%06d", year, next);
    }

    // ======================= XEM =======================

    /** Phiếu trả đã nhập kho chờ lập chứng từ giảm trừ, kèm số tiền sẽ giảm trừ. */
    @Transactional(readOnly = true)
    public List<PendingCreditReturnResponse> pendingReturns(UserDetailsImpl actor) {
        if (!CustomerAccess.hasFullAccess(actor)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CREDIT_NOTE_FORBIDDEN",
                    "Chỉ kế toán / quản lý được xem phiếu trả chờ giảm trừ", null);
        }
        List<PendingCreditReturnResponse> out = new ArrayList<>();
        for (SalesReturn r : creditNoteRepository.findReceivedReturnsWithoutCreditNote()) {
            BigDecimal amount = ZERO;
            for (SalesReturnLine rl : r.getLines()) {
                InvoiceLine il = rl.getInvoiceLine();
                amount = amount.add(lineCredit(il.getQuantity(), il.getNetAmount(),
                        creditNoteRepository.sumCreditedQuantityByInvoiceLine(il.getId()),
                        creditNoteRepository.sumCreditedAmountByInvoiceLine(il.getId()), rl.getReturnQuantity()));
            }
            out.add(new PendingCreditReturnResponse(r.getId(), r.getCode(),
                    r.getInvoice() != null ? r.getInvoice().getId() : null, r.getInvoiceCode(),
                    r.getCustomer() != null ? r.getCustomer().getId() : null, r.getCustomerCode(), r.getCustomerName(),
                    amount, r.getReceivedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public CreditNoteResponse get(Long id, UserDetailsImpl actor) {
        CreditNote cn = creditNoteRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chứng từ giảm trừ"));
        CustomerAccess.checkCanAccess(actor, cn.getCustomer(), customerRepository);
        return toResponse(cn);
    }

    /** Tra cứu chứng từ giảm trừ; đại lý chỉ thấy của mình, NV kinh doanh chỉ đại lý mình phụ trách. */
    @Transactional(readOnly = true)
    public PageResponse<CreditNoteResponse> search(Long customerId, Long invoiceId, LocalDate fromDate, LocalDate toDate,
                                                   int page, int size, UserDetailsImpl actor) {
        int safePage = Math.max(0, page);
        int safeSize = size > 0 && size <= 100 ? size : 20;
        Long scopedCustomer = customerId;
        Long repId = null;
        if (!CustomerAccess.hasFullAccess(actor) && CustomerAccess.isCustomer(actor)) {
            Customer own = CustomerAccess.resolveCustomerForActor(actor, customerRepository);
            if (own == null) {
                return PageResponse.of(Page.<CreditNoteResponse>empty(PageRequest.of(safePage, safeSize)));
            }
            scopedCustomer = own.getId();
        } else {
            repId = CustomerAccess.restrictedSalesRepId(actor);
        }
        final Long fc = scopedCustomer;
        final Long fr = repId;
        Specification<CreditNote> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (fc != null) {
                ps.add(cb.equal(root.get("customer").get("id"), fc));
            }
            if (fr != null) {
                ps.add(cb.equal(root.get("customer").get("salesRep").get("id"), fr));
            }
            if (invoiceId != null) {
                ps.add(cb.equal(root.get("invoice").get("id"), invoiceId));
            }
            if (fromDate != null) {
                ps.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("noteDate"), fromDate));
            }
            if (toDate != null) {
                ps.add(cb.lessThanOrEqualTo(root.<LocalDate>get("noteDate"), toDate));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<CreditNote> result = creditNoteRepository.findAll(spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "noteDate", "id")));
        return PageResponse.of(result.map(CreditNoteService::toResponse));
    }

    static CreditNoteResponse toResponse(CreditNote c) {
        List<CreditNoteLineResponse> lines = c.getLines().stream()
                .map(l -> new CreditNoteLineResponse(l.getLineNo(), l.getInvoiceLineId(), l.getProductSku(),
                        l.getProductName(), l.getUnitName(), l.getReturnQuantity(), l.getInvoiceQuantity(),
                        l.getInvoiceNetAmount(), l.getAmount()))
                .toList();
        List<CreditNoteAllocationResponse> allocs = c.getAllocations().stream()
                .map(a -> new CreditNoteAllocationResponse(a.getInvoice() != null ? a.getInvoice().getId() : null,
                        a.getInvoiceCode(), a.getAmount(), a.getRemainingBefore(), a.getRemainingAfter()))
                .toList();
        return new CreditNoteResponse(c.getId(), c.getCode(),
                c.getSalesReturn() != null ? c.getSalesReturn().getId() : null, c.getSalesReturnCode(),
                c.getInvoice() != null ? c.getInvoice().getId() : null, c.getInvoiceCode(),
                c.getCustomer() != null ? c.getCustomer().getId() : null, c.getCustomerCode(), c.getCustomerName(),
                c.getNoteDate(), c.getAmount(), c.getAppliedAmount(), c.getUnappliedAmount(), c.getStatus(),
                c.getReturnReason(), c.getNote(), c.getCreatedAt(), c.getCreatedByUsername(), lines, allocs);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
