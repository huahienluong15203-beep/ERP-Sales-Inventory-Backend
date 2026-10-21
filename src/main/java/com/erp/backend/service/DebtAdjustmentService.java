package com.erp.backend.service;

import com.erp.backend.dto.creditnote.CreditNoteCreateRequest;
import com.erp.backend.dto.debtadjustment.DebtAdjustmentItemResponse;
import com.erp.backend.dto.debtadjustment.DebtAdjustmentResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S8-02: Màn hình "Chứng từ giảm trừ công nợ" của FE (/api/debt-adjustments) – lớp ghép nối trên CreditNoteService.
 * - Mỗi phiếu trả hàng đã được thủ kho xác nhận nhận hàng là 1 chứng từ điều chỉnh (id = id phiếu trả):
 *   chưa xử lý = PENDING_APPROVAL, đã lập chứng từ giảm trừ = APPROVED, kế toán từ chối = REJECTED.
 * - Duyệt = lập chứng từ giảm trừ (CreditNoteService): giá theo hoá đơn gốc, giảm công nợ ngay, hoá đơn gốc giữ nguyên.
 * - Từ chối bắt buộc lý do; đã duyệt / đã từ chối thì không đổi được nữa.
 */
@Service
@RequiredArgsConstructor
public class DebtAdjustmentService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String PENDING = "PENDING_APPROVAL";
    static final String APPROVED = "APPROVED";
    static final String REJECTED = "REJECTED";
    static final Map<String, String> REASON_LABELS = Map.of(
            SalesReturn.REASON_DEFECTIVE_TRANSPORT, "Hỏng/vỡ do vận chuyển",
            SalesReturn.REASON_EXPIRED_OR_NEAR, "Hết hạn / cận hạn sử dụng",
            SalesReturn.REASON_WRONG_SPECIFICATION, "Sai quy cách / không đúng mẫu",
            SalesReturn.REASON_AGENCY_OVERSTOCK, "Đại lý thừa hàng / giảm quy mô",
            SalesReturn.REASON_CUSTOMER_COMPLAINT, "Khiếu nại chất lượng",
            SalesReturn.REASON_OTHER, "Lý do khác");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final DebtAdjustmentRejectionRepository rejectionRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final PriceListItemRepository priceItemRepository;
    private final CreditNoteService creditNoteService;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= DANH SÁCH =======================

    @Transactional(readOnly = true)
    public List<DebtAdjustmentResponse> list(String status, Long customerId, String keyword, UserDetailsImpl actor) {
        requireView(actor);
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "ALL";
        if (!List.of("ALL", PENDING, APPROVED, REJECTED).contains(st)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Trạng thái: PENDING_APPROVAL, APPROVED, REJECTED hoặc ALL", "status");
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;

        List<SalesReturn> returns = rejectionRepository.findReceivedReturns().stream()
                .filter(r -> customerId == null || (r.getCustomer() != null && customerId.equals(r.getCustomer().getId())))
                .toList();
        if (returns.isEmpty()) {
            return List.of();
        }
        List<Long> ids = returns.stream().map(SalesReturn::getId).toList();
        Map<Long, CreditNote> notes = new HashMap<>();
        for (CreditNote cn : creditNoteRepository.findBySalesReturn_IdIn(ids)) {
            notes.put(cn.getSalesReturn().getId(), cn);
        }
        Map<Long, DebtAdjustmentRejection> rejections = new HashMap<>();
        for (DebtAdjustmentRejection rj : rejectionRepository.findBySalesReturn_IdIn(ids)) {
            rejections.put(rj.getSalesReturn().getId(), rj);
        }

        List<DebtAdjustmentResponse> out = new ArrayList<>();
        for (SalesReturn r : returns) {
            String rowStatus = notes.containsKey(r.getId()) ? APPROVED : rejections.containsKey(r.getId()) ? REJECTED : PENDING;
            if (!"ALL".equals(st) && !st.equals(rowStatus)) {
                continue;
            }
            DebtAdjustmentResponse row = toResponse(r, notes.get(r.getId()), rejections.get(r.getId()));
            if (kw != null && !matches(row, kw)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public DebtAdjustmentResponse get(Long returnId, UserDetailsImpl actor) {
        requireView(actor);
        SalesReturn r = salesReturnRepository.findById(returnId)
                .filter(x -> SalesReturn.STATUS_RECEIVED.equals(x.getStatus()))
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chứng từ điều chỉnh"));
        return toResponse(r, creditNoteRepository.findBySalesReturn_Id(r.getId()).orElse(null),
                rejectionRepository.findBySalesReturn_IdIn(List.of(r.getId())).stream().findFirst().orElse(null));
    }

    // ======================= DUYỆT / TỪ CHỐI =======================

    /** Duyệt = lập chứng từ giảm trừ và giảm công nợ ngay (kiểm tra trạng thái phiếu trả, chống lập trùng ở CreditNoteService). */
    @Transactional
    public DebtAdjustmentResponse approve(Long returnId, String approvalNote, UserDetailsImpl actor) {
        requireDecide(actor);
        // Khoá phiếu trả trước khi kiểm tra từ chối để không vừa duyệt vừa từ chối cùng lúc
        SalesReturn r = salesReturnRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu trả hàng"));
        if (rejectionRepository.existsBySalesReturn_Id(returnId)) {
            throw BusinessException.conflict("DEBT_ADJUSTMENT_REJECTED",
                    "Chứng từ điều chỉnh này đã bị từ chối, không duyệt lại được", "id");
        }
        creditNoteService.createFromReturn(new CreditNoteCreateRequest(returnId, approvalNote), actor);
        return toResponse(r, creditNoteRepository.findBySalesReturn_Id(returnId).orElse(null), null);
    }

    @Transactional
    public DebtAdjustmentResponse reject(Long returnId, String reason, UserDetailsImpl actor) {
        requireDecide(actor);
        String why = reason != null ? reason.trim() : "";
        if (why.length() < 5) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REJECT_REASON_REQUIRED",
                    "Vui lòng nhập lý do từ chối (tối thiểu 5 ký tự)", "reason");
        }
        if (why.length() > 500) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REJECT_REASON_TOO_LONG", "Lý do tối đa 500 ký tự", "reason");
        }
        // Khoá phiếu trả để không vừa duyệt vừa từ chối cùng lúc
        SalesReturn r = salesReturnRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu trả hàng"));
        if (!SalesReturn.STATUS_RECEIVED.equals(r.getStatus())) {
            throw BusinessException.conflict("SALES_RETURN_NOT_RECEIVED",
                    "Phiếu trả " + r.getCode() + " chưa được thủ kho xác nhận nhận hàng", "id");
        }
        if (creditNoteRepository.existsBySalesReturn_Id(returnId)) {
            throw BusinessException.conflict("CREDIT_NOTE_EXISTS",
                    "Phiếu trả " + r.getCode() + " đã được duyệt giảm trừ, không từ chối được", "id");
        }
        if (rejectionRepository.existsBySalesReturn_Id(returnId)) {
            throw BusinessException.conflict("DEBT_ADJUSTMENT_REJECTED", "Chứng từ điều chỉnh này đã bị từ chối", "id");
        }
        DebtAdjustmentRejection rj = rejectionRepository.save(DebtAdjustmentRejection.builder()
                .salesReturn(r)
                .reason(why)
                .rejectedAt(LocalDateTime.now(clock))
                .rejectedById(actor != null ? actor.getId() : null)
                .rejectedByUsername(actor != null ? actor.getUsername() : null)
                .build());
        auditLogService.record(AuditModule.INVOICE, "REJECT_DEBT_ADJUSTMENT", "SALES_RETURN", r.getId(), r.getCode(),
                PENDING, REJECTED, "Từ chối giảm trừ công nợ: " + why, actor);
        return toResponse(r, null, rj);
    }

    // ======================= CHUYỂN ĐỔI =======================

    DebtAdjustmentResponse toResponse(SalesReturn r, CreditNote cn, DebtAdjustmentRejection rj) {
        Invoice inv = r.getInvoice();
        Customer c = r.getCustomer() != null ? r.getCustomer() : (inv != null ? inv.getCustomer() : null);
        Map<Long, CreditNoteLine> creditBySalesReturnLine = new HashMap<>();
        if (cn != null) {
            cn.getLines().forEach(l -> creditBySalesReturnLine.put(l.getSalesReturnLineId(), l));
        }
        LocalDate today = LocalDate.now(clock);
        List<DebtAdjustmentItemResponse> items = new ArrayList<>();
        BigDecimal total = ZERO;
        for (SalesReturnLine rl : r.getLines()) {
            InvoiceLine il = rl.getInvoiceLine();
            BigDecimal amount;
            CreditNoteLine done = creditBySalesReturnLine.get(rl.getId());
            if (done != null) {
                amount = done.getAmount();
            } else if (rj != null) {
                amount = ZERO;
            } else {
                // Chờ duyệt: số sẽ giảm trừ nếu duyệt bây giờ (giống hệt cách CreditNoteService tính)
                amount = CreditNoteService.lineCredit(il.getQuantity(), il.getNetAmount(),
                        creditNoteRepository.sumCreditedQuantityByInvoiceLine(il.getId()),
                        creditNoteRepository.sumCreditedAmountByInvoiceLine(il.getId()), rl.getReturnQuantity());
            }
            total = total.add(amount);
            items.add(new DebtAdjustmentItemResponse(rl.getId(), il.getProductSku(), il.getProductName(), il.getUnitName(),
                    rl.getReturnQuantity(), soldPrice(il), catalogPrice(c, il, today), amount));
        }
        if (cn != null) {
            total = cn.getAmount();
        }
        String status = cn != null ? APPROVED : rj != null ? REJECTED : PENDING;
        String reason = REASON_LABELS.getOrDefault(r.getReturnReason(), r.getReturnReason());
        if (StringUtils.hasText(r.getReasonDescription())) {
            reason = reason + ": " + r.getReasonDescription().trim();
        }
        return new DebtAdjustmentResponse(r.getId(), cn != null ? cn.getCode() : null, r.getCode(),
                inv != null ? inv.getId() : null, inv != null ? inv.getCode() : r.getInvoiceCode(),
                inv != null && inv.getInvoiceDate() != null ? inv.getInvoiceDate().toString() : null,
                c != null ? c.getId() : null, c != null ? c.getCode() : r.getCustomerCode(),
                c != null ? c.getName() : r.getCustomerName(), c != null ? c.getPhone() : null,
                c != null ? c.getAddress() : null, status, items, total, reason, r.getNotes(),
                fmt(r.getReceivedAt() != null ? r.getReceivedAt() : r.getCreatedAt()),
                r.getReceivedByName() != null ? r.getReceivedByName() : r.getCreatedByName(),
                cn != null ? fmt(cn.getCreatedAt()) : rj != null ? fmt(rj.getRejectedAt()) : null,
                cn != null ? cn.getCreatedByUsername() : rj != null ? rj.getRejectedByUsername() : null,
                cn != null ? cn.getNote() : rj != null ? "Từ chối: " + rj.getReason() : null,
                cn != null ? cn.getId() : null);
    }

    /** Giá đã bán / 1 ĐVT trên hoá đơn gốc = thành tiền dòng (sau chiết khấu) / số lượng. */
    static BigDecimal soldPrice(InvoiceLine il) {
        if (il.getQuantity() == null || il.getQuantity().signum() <= 0 || il.getNetAmount() == null) {
            return il.getPricePerUnit();
        }
        return il.getNetAmount().divide(il.getQuantity(), 2, RoundingMode.HALF_UP);
    }

    /** Giá bảng giá hiện hành của nhóm đại lý cho cùng ĐVT (giá ĐVT cơ sở x hệ số quy đổi); null nếu không còn giá. */
    private BigDecimal catalogPrice(Customer c, InvoiceLine il, LocalDate today) {
        if (c == null || c.getCustomerGroup() == null || il.getProductSku() == null) {
            return null;
        }
        return priceItemRepository.findEffective(c.getCustomerGroup(), il.getProductSku(), today, PageRequest.of(0, 1))
                .stream().findFirst()
                .map(p -> p.getPrice() == null ? null : p.getPrice()
                        .multiply(il.getConversionFactor() != null ? il.getConversionFactor() : BigDecimal.ONE)
                        .setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private static boolean matches(DebtAdjustmentResponse r, String kw) {
        return contains(r.adjustmentCode(), kw) || contains(r.returnOrderCode(), kw) || contains(r.originalInvoiceCode(), kw)
                || contains(r.customerCode(), kw) || contains(r.customerName(), kw);
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(DATE_TIME);
    }

    private static void requireView(UserDetailsImpl actor) {
        if (!CustomerAccess.hasFullAccess(actor)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "DEBT_ADJUSTMENT_FORBIDDEN",
                    "Chỉ kế toán / quản lý kinh doanh / admin xem được chứng từ giảm trừ công nợ", null);
        }
    }

    private static void requireDecide(UserDetailsImpl actor) {
        boolean ok = actor != null && actor.getAuthorities() != null && actor.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ACCOUNTANT".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
        if (!ok) {
            // 409 (không dùng 403) vì FE tự đăng xuất khi gặp 403, mà QL kinh doanh vẫn được mở màn hình này để xem
            throw BusinessException.conflict("DEBT_ADJUSTMENT_ACCOUNTANT_ONLY",
                    "Chỉ kế toán công nợ / admin được duyệt hoặc từ chối chứng từ giảm trừ", null);
        }
    }
}
