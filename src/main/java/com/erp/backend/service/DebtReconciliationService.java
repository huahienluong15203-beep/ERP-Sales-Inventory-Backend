package com.erp.backend.service;

import com.erp.backend.dto.debt.CustomerDebtStatementResponse;
import com.erp.backend.dto.debt.DebtReconciliationCreateRequest;
import com.erp.backend.dto.debt.DebtReconciliationRespondRequest;
import com.erp.backend.dto.debt.DebtReconciliationResponse;
import com.erp.backend.dto.debt.DebtStatementEntry;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.DebtReconciliation;
import com.erp.backend.entity.DebtReconciliationLine;
import com.erp.backend.entity.Notification;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.DebtReconciliationRepository;
import com.erp.backend.repository.NotificationRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S7-06: Đối chiếu công nợ định kỳ.
 * - Kế toán lập biên bản theo kỳ: số dư đầu kỳ, phát sinh tăng / giảm, số dư cuối kỳ (tính bằng sổ chi tiết S8-10).
 * - Lưu nháp (DRAFT) hoặc chốt ngay (CONFIRMED). Chốt thì tính lại số liệu và gửi thông báo cho đại lý.
 * - Đại lý xác nhận (AGENCY_ACCEPTED) hoặc phản hồi lệch kèm lý do (AGENCY_DISPUTED) ngay trên cổng đại lý.
 * - In khổ A4 (trình duyệt "Lưu thành PDF") để gửi đại lý ký.
 * - Biên bản không sửa / xoá; bị phản hồi lệch thì lập biên bản mới cho cùng kỳ.
 * Lỗi nghiệp vụ trả 409 / 400 / 404 (không trả 403 vì giao diện sẽ đăng xuất).
 */
@Service
@RequiredArgsConstructor
public class DebtReconciliationService {

    static final String NOTIFICATION_TYPE = "DEBT_RECONCILIATION";
    static final Set<String> ACTIVE_STATUSES = Set.of(DebtReconciliation.STATUS_DRAFT,
            DebtReconciliation.STATUS_CONFIRMED, DebtReconciliation.STATUS_AGENCY_ACCEPTED);
    private static final Map<String, String> STATUS_LABELS = Map.of(
            DebtReconciliation.STATUS_DRAFT, "Nháp",
            DebtReconciliation.STATUS_CONFIRMED, "Chờ đại lý xác nhận",
            DebtReconciliation.STATUS_AGENCY_ACCEPTED, "Đại lý đã xác nhận",
            DebtReconciliation.STATUS_AGENCY_DISPUTED, "Đại lý phản hồi lệch");
    private static final DateTimeFormatter CODE_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final DebtReconciliationRepository reconciliationRepository;
    private final CustomerDebtStatementService statementService;
    private final CustomerRepository customerRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    // ======================= XEM =======================

    @Transactional(readOnly = true)
    public List<DebtReconciliationResponse> list(Long customerId, String status, UserDetailsImpl actor) {
        String st = StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())
                ? status.trim().toUpperCase(Locale.ROOT) : null;
        Long scopeCustomerId = customerId;
        boolean isCustomer = CustomerAccess.isCustomer(actor);
        if (isCustomer) {
            Customer own = CustomerAccess.resolveCustomerForActor(actor, customerRepository);
            if (own == null || (customerId != null && !customerId.equals(own.getId()))) {
                return List.of();
            }
            scopeCustomerId = own.getId();
        }
        Long repScope = CustomerAccess.hasFullAccess(actor) || isCustomer ? null : CustomerAccess.restrictedSalesRepId(actor);
        List<DebtReconciliation> found = scopeCustomerId != null
                ? reconciliationRepository.findByCustomer_IdOrderByIdDesc(scopeCustomerId)
                : reconciliationRepository.findTop500ByOrderByIdDesc();
        List<DebtReconciliationResponse> out = new ArrayList<>();
        for (DebtReconciliation r : found) {
            if (st != null && !st.equals(r.getStatus())) {
                continue;
            }
            // Đại lý không thấy bản nháp
            if (isCustomer && DebtReconciliation.STATUS_DRAFT.equals(r.getStatus())) {
                continue;
            }
            if (repScope != null && (r.getCustomer().getSalesRep() == null
                    || !repScope.equals(r.getCustomer().getSalesRep().getId()))) {
                continue;
            }
            out.add(toResponse(r));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public DebtReconciliationResponse get(Long id, UserDetailsImpl actor) {
        return toResponse(loadVisible(id, actor));
    }

    /** Xem trước số liệu biên bản (chưa lưu) để kế toán kiểm tra trước khi lập. */
    @Transactional(readOnly = true)
    public DebtReconciliationResponse preview(Long customerId, LocalDate fromDate, LocalDate toDate,
                                              LocalDate statementDate, UserDetailsImpl actor) {
        Customer customer = statementService.loadCustomer(customerId, actor);
        LocalDate[] p = validatePeriod(fromDate, toDate, statementDate);
        CustomerDebtStatementResponse s = statementService.compute(customer, p[0], p[1]);
        List<DebtReconciliationResponse.Transaction> tx = new ArrayList<>();
        int i = 1;
        for (DebtStatementEntry e : s.entries()) {
            tx.add(new DebtReconciliationResponse.Transaction((long) i++, e.date(), e.code(), e.type(), e.typeLabel(),
                    e.debit(), e.credit(), e.balance(), e.description()));
        }
        return new DebtReconciliationResponse(null, null, customer.getId(), customer.getCode(), customer.getName(),
                customer.getAddress(), customer.getTaxCode(), customer.getPhone(), p[0], p[1], p[2],
                s.openingBalance(), s.totalDebit(), s.totalCredit(), s.closingBalance(), null, null, null,
                null, null, null, null, null, null, null, tx);
    }

    @Transactional(readOnly = true)
    public String printHtml(Long id, boolean autoPrint, UserDetailsImpl actor) {
        return render(toResponse(loadVisible(id, actor)), autoPrint);
    }

    // ======================= KẾ TOÁN LẬP / CHỐT =======================

    @Transactional
    public DebtReconciliationResponse create(DebtReconciliationCreateRequest req, UserDetailsImpl actor) {
        requireAccountant(actor);
        if (req == null) {
            throw BusinessException.badRequest("Thiếu thông tin biên bản đối chiếu");
        }
        Customer customer = statementService.loadCustomer(req.customerId(), actor);
        LocalDate[] p = validatePeriod(req.fromDate(), req.toDate(), req.statementDate());
        if (reconciliationRepository.existsByCustomer_IdAndFromDateAndToDateAndStatusIn(customer.getId(), p[0], p[1],
                ACTIVE_STATUSES)) {
            throw BusinessException.conflict("RECONCILIATION_DUPLICATE",
                    "Đại lý " + customer.getCode() + " đã có biên bản đối chiếu kỳ " + p[0].format(DATE) + " - "
                            + p[1].format(DATE) + " (nháp / chờ xác nhận / đã xác nhận). Chỉ lập lại khi đại lý phản hồi lệch.",
                    "fromDate");
        }
        String notes = trimTo(req.notes(), 500);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean draft = Boolean.TRUE.equals(req.draft());

        DebtReconciliation r = DebtReconciliation.builder()
                .code(nextCode(p[2]))
                .customer(customer)
                .fromDate(p[0])
                .toDate(p[1])
                .statementDate(p[2])
                .notes(notes)
                .status(DebtReconciliation.STATUS_DRAFT)
                .createdAt(now)
                .createdById(actor.getId())
                .createdByUsername(actor.getUsername())
                .build();
        snapshot(r, customer);
        if (!draft) {
            markConfirmed(r, actor, now);
        }
        DebtReconciliation saved = reconciliationRepository.save(r);
        audit(saved, draft ? "RECONCILIATION_CREATE_DRAFT" : "RECONCILIATION_CREATE", null, summary(saved), actor);
        if (!draft) {
            notifyCustomer(saved);
        }
        return toResponse(saved);
    }

    /** Chốt bản nháp: tính lại số liệu tới thời điểm chốt rồi gửi đại lý xác nhận. */
    @Transactional
    public DebtReconciliationResponse confirm(Long id, UserDetailsImpl actor) {
        requireAccountant(actor);
        DebtReconciliation r = reconciliationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy biên bản đối chiếu"));
        CustomerAccess.checkCanAccess(actor, r.getCustomer(), customerRepository);
        if (!DebtReconciliation.STATUS_DRAFT.equals(r.getStatus())) {
            throw BusinessException.conflict("RECONCILIATION_NOT_DRAFT",
                    "Biên bản " + r.getCode() + " không còn là bản nháp (" + statusLabel(r.getStatus()) + ")", "status");
        }
        String before = summary(r);
        snapshot(r, r.getCustomer());
        markConfirmed(r, actor, LocalDateTime.now(clock));
        DebtReconciliation saved = reconciliationRepository.save(r);
        audit(saved, "RECONCILIATION_CONFIRM", before, summary(saved), actor);
        notifyCustomer(saved);
        return toResponse(saved);
    }

    // ======================= ĐẠI LÝ PHẢN HỒI =======================

    @Transactional
    public DebtReconciliationResponse respond(Long id, DebtReconciliationRespondRequest req, UserDetailsImpl actor) {
        if (!CustomerAccess.isCustomer(actor)) {
            throw BusinessException.conflict("RECONCILIATION_CUSTOMER_ONLY",
                    "Chỉ đại lý mới xác nhận hoặc phản hồi biên bản đối chiếu trên cổng đại lý", "status");
        }
        String decision = req != null && req.status() != null ? req.status().trim().toUpperCase(Locale.ROOT) : null;
        if (!DebtReconciliation.STATUS_AGENCY_ACCEPTED.equals(decision)
                && !DebtReconciliation.STATUS_AGENCY_DISPUTED.equals(decision)) {
            throw BusinessException.badRequest("INVALID_RECONCILIATION_RESPONSE",
                    "Chọn xác nhận (AGENCY_ACCEPTED) hoặc phản hồi lệch (AGENCY_DISPUTED)");
        }
        String feedback = trimTo(req.feedback(), 1000);
        if (DebtReconciliation.STATUS_AGENCY_DISPUTED.equals(decision) && (feedback == null || feedback.length() < 5)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECONCILIATION_FEEDBACK_REQUIRED",
                    "Vui lòng ghi rõ số liệu lệch (tối thiểu 5 ký tự) để kế toán kiểm tra lại", "feedback");
        }
        DebtReconciliation r = reconciliationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy biên bản đối chiếu"));
        CustomerAccess.checkCanAccess(actor, r.getCustomer(), customerRepository);
        if (DebtReconciliation.STATUS_DRAFT.equals(r.getStatus())) {
            throw BusinessException.notFound("Không tìm thấy biên bản đối chiếu");
        }
        if (!DebtReconciliation.STATUS_CONFIRMED.equals(r.getStatus())) {
            throw BusinessException.conflict("RECONCILIATION_ALREADY_RESPONDED",
                    "Biên bản " + r.getCode() + " đã được phản hồi (" + statusLabel(r.getStatus()) + ")", "status");
        }
        String before = summary(r);
        String signer = trimTo(req.signerName(), 150);
        r.setStatus(decision);
        r.setAgencySignedAt(LocalDateTime.now(clock));
        r.setAgencySignedByUserId(actor.getId());
        r.setAgencySignedBy(signer != null ? signer
                : (actor.getFullName() != null ? actor.getFullName() : actor.getUsername()));
        r.setAgencyFeedback(feedback);
        DebtReconciliation saved = reconciliationRepository.save(r);
        audit(saved, DebtReconciliation.STATUS_AGENCY_ACCEPTED.equals(decision)
                ? "RECONCILIATION_AGENCY_ACCEPT" : "RECONCILIATION_AGENCY_DISPUTE", before, summary(saved), actor);
        return toResponse(saved);
    }

    // ======================= NỘI BỘ =======================

    private DebtReconciliation loadVisible(Long id, UserDetailsImpl actor) {
        DebtReconciliation r = reconciliationRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy biên bản đối chiếu"));
        CustomerAccess.checkCanAccess(actor, r.getCustomer(), customerRepository);
        if (CustomerAccess.isCustomer(actor) && DebtReconciliation.STATUS_DRAFT.equals(r.getStatus())) {
            throw BusinessException.notFound("Không tìm thấy biên bản đối chiếu");
        }
        return r;
    }

    /** Chỉ Kế toán công nợ / Admin lập và chốt biên bản. */
    static void requireAccountant(UserDetailsImpl actor) {
        boolean ok = actor != null && actor.getAuthorities() != null && actor.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ACCOUNTANT".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
        if (!ok) {
            throw BusinessException.conflict("RECONCILIATION_ACCOUNTANT_ONLY",
                    "Chỉ Kế toán công nợ mới lập / chốt biên bản đối chiếu", "status");
        }
    }

    /** [from, to, statementDate]: kỳ phải hợp lệ, không ở tương lai; ngày lập không trước ngày cuối kỳ. */
    LocalDate[] validatePeriod(LocalDate fromDate, LocalDate toDate, LocalDate statementDate) {
        if (fromDate == null || toDate == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PERIOD_REQUIRED",
                    "Vui lòng chọn kỳ đối chiếu (từ ngày, đến ngày)", "fromDate");
        }
        CustomerDebtStatementService.Period p = statementService.period(fromDate, toDate);
        LocalDate today = LocalDate.now(clock);
        if (p.to().isAfter(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PERIOD_IN_FUTURE",
                    "Kỳ đối chiếu chưa kết thúc (đến ngày phải trước hoặc bằng hôm nay)", "toDate");
        }
        LocalDate sd = statementDate != null ? statementDate : today;
        if (sd.isBefore(p.to())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATEMENT_DATE",
                    "Ngày lập biên bản phải sau hoặc bằng ngày cuối kỳ", "statementDate");
        }
        if (sd.isAfter(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATEMENT_DATE",
                    "Ngày lập biên bản không được ở tương lai", "statementDate");
        }
        return new LocalDate[]{p.from(), p.to(), sd};
    }

    /** Chụp lại thông tin đại lý và số liệu sổ chi tiết của kỳ vào biên bản. */
    void snapshot(DebtReconciliation r, Customer c) {
        CustomerDebtStatementResponse s = statementService.compute(c, r.getFromDate(), r.getToDate());
        r.setCustomerCode(c.getCode());
        r.setCustomerName(c.getName());
        r.setCustomerAddress(c.getAddress());
        r.setCustomerTaxCode(c.getTaxCode());
        r.setCustomerPhone(c.getPhone());
        r.setOpeningBalance(s.openingBalance());
        r.setTotalDebit(s.totalDebit());
        r.setTotalCredit(s.totalCredit());
        r.setClosingBalance(s.closingBalance());
        r.getLines().clear();
        int no = 1;
        for (DebtStatementEntry e : s.entries()) {
            r.getLines().add(DebtReconciliationLine.builder()
                    .reconciliation(r)
                    .lineNo(no++)
                    .txDate(e.date())
                    .docType(e.type())
                    .typeLabel(e.typeLabel())
                    .docId(e.refId())
                    .docCode(e.code())
                    .description(e.description())
                    .debit(e.debit())
                    .credit(e.credit())
                    .balance(e.balance())
                    .build());
        }
    }

    private static void markConfirmed(DebtReconciliation r, UserDetailsImpl actor, LocalDateTime now) {
        r.setStatus(DebtReconciliation.STATUS_CONFIRMED);
        r.setConfirmedAt(now);
        r.setConfirmedById(actor.getId());
        r.setConfirmedBy(actor.getFullName() != null ? actor.getFullName() : actor.getUsername());
    }

    String nextCode(LocalDate statementDate) {
        String prefix = "BB-DC-" + statementDate.format(CODE_MONTH) + "-";
        int max = 0;
        for (String code : reconciliationRepository.findCodesByPrefix(prefix)) {
            try {
                max = Math.max(max, Integer.parseInt(code.substring(prefix.length())));
            } catch (RuntimeException ignored) {
                // mã lạ: bỏ qua
            }
        }
        return prefix + String.format("%03d", max + 1);
    }

    private void notifyCustomer(DebtReconciliation r) {
        Customer c = r.getCustomer();
        notificationRepository.save(Notification.builder()
                .userId(c.getPortalUser() != null ? c.getPortalUser().getId() : null)
                .customerId(c.getId())
                .customerCode(c.getCode())
                .title("Biên bản đối chiếu công nợ " + r.getCode() + " cần xác nhận")
                .content("Kỳ " + r.getFromDate().format(DATE) + " - " + r.getToDate().format(DATE)
                        + ": số dư cuối kỳ " + CustomerDebtStatementService.vnd(r.getClosingBalance())
                        + ". Vui lòng xác nhận hoặc phản hồi nếu số liệu chưa khớp.")
                .notificationType(NOTIFICATION_TYPE)
                .referenceType("DEBT_RECONCILIATION")
                .referenceId(r.getId())
                .referenceCode(r.getCode())
                .isRead(false)
                .createdAt(LocalDateTime.now(clock))
                .build());
    }

    private void audit(DebtReconciliation r, String action, String before, String after, UserDetailsImpl actor) {
        auditLogService.record(AuditModule.INVOICE, action, "DEBT_RECONCILIATION", r.getId(), r.getCode(),
                before, after, r.getAgencyFeedback() != null ? "Phản hồi đại lý: " + r.getAgencyFeedback() : r.getNotes(),
                actor);
    }

    private static String summary(DebtReconciliation r) {
        return "Trạng thái: " + statusLabel(r.getStatus()) + "; Đại lý: " + r.getCustomerCode()
                + "; Kỳ: " + r.getFromDate() + " → " + r.getToDate()
                + "; Đầu kỳ: " + plain(r.getOpeningBalance()) + "; Tăng: " + plain(r.getTotalDebit())
                + "; Giảm: " + plain(r.getTotalCredit()) + "; Cuối kỳ: " + plain(r.getClosingBalance());
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    static String statusLabel(String status) {
        return status == null ? "" : STATUS_LABELS.getOrDefault(status, status);
    }

    private static String trimTo(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static DebtReconciliationResponse toResponse(DebtReconciliation r) {
        List<DebtReconciliationResponse.Transaction> tx = new ArrayList<>();
        for (DebtReconciliationLine l : r.getLines()) {
            tx.add(new DebtReconciliationResponse.Transaction(l.getId() != null ? l.getId() : (long) l.getLineNo(),
                    l.getTxDate(), l.getDocCode(), l.getDocType(), l.getTypeLabel(), l.getDebit(), l.getCredit(),
                    l.getBalance(), l.getDescription()));
        }
        return new DebtReconciliationResponse(r.getId(), r.getCode(), r.getCustomer() != null ? r.getCustomer().getId() : null,
                r.getCustomerCode(), r.getCustomerName(), r.getCustomerAddress(), r.getCustomerTaxCode(),
                r.getCustomerPhone(), r.getFromDate(), r.getToDate(), r.getStatementDate(), r.getOpeningBalance(),
                r.getTotalDebit(), r.getTotalCredit(), r.getClosingBalance(), r.getStatus(), statusLabel(r.getStatus()),
                r.getNotes(), r.getCreatedByUsername(), r.getCreatedAt(), r.getConfirmedBy(), r.getConfirmedAt(),
                r.getAgencySignedBy(), r.getAgencySignedAt(), r.getAgencyFeedback(), tx);
    }

    // ======================= IN BIÊN BẢN =======================

    static String render(DebtReconciliationResponse s, boolean autoPrint) {
        StringBuilder rows = new StringBuilder();
        for (DebtReconciliationResponse.Transaction t : s.transactions()) {
            rows.append("<tr><td>").append(t.date() != null ? t.date().format(DATE) : "").append("</td><td>")
                    .append(e(t.code())).append("</td><td>").append(e(t.typeLabel())).append("</td><td>")
                    .append(e(t.notes())).append("</td><td class=\"n\">").append(blank(t.debit()))
                    .append("</td><td class=\"n\">").append(blank(t.credit())).append("</td><td class=\"n\">")
                    .append(CustomerDebtStatementService.vnd(t.balance())).append("</td></tr>");
        }
        String mark = DebtReconciliation.STATUS_DRAFT.equals(s.status()) ? "BẢN NHÁP – CHƯA GỬI ĐẠI LÝ"
                : DebtReconciliation.STATUS_AGENCY_DISPUTED.equals(s.status()) ? "ĐẠI LÝ PHẢN HỒI LỆCH – CHƯA THỐNG NHẤT" : null;
        return "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\"><title>Biên bản đối chiếu "
                + e(s.statementCode()) + "</title><style>"
                + "@page{size:A4;margin:14mm}body{font-family:'Times New Roman',serif;font-size:13px;color:#111}"
                + ".c{text-align:center}.n{text-align:right;white-space:nowrap}h1{font-size:18px;margin:10px 0 4px}"
                + "table{width:100%;border-collapse:collapse;margin:8px 0}th,td{border:1px solid #888;padding:4px 6px}"
                + "th{background:#eee}.sum td{border:none;padding:3px 0}.mark{border:2px solid #c00;color:#c00;"
                + "text-align:center;font-weight:bold;padding:4px;margin:6px 0}"
                + ".sign{display:flex;justify-content:space-around;margin-top:30px;text-align:center}.sign i{display:block;color:#555}"
                + "</style></head><body>"
                + "<div class=\"c\"><b>CỘNG HOÀ XÃ HỘI CHỦ NGHĨA VIỆT NAM</b><br>Độc lập - Tự do - Hạnh phúc</div>"
                + "<h1 class=\"c\">BIÊN BẢN ĐỐI CHIẾU VÀ XÁC NHẬN CÔNG NỢ</h1>"
                + "<div class=\"c\">Số: " + e(s.statementCode()) + " · Kỳ: từ " + d(s.fromDate()) + " đến " + d(s.toDate())
                + "</div>"
                + (mark != null ? "<div class=\"mark\">" + mark + "</div>" : "")
                + "<p>Hôm nay, ngày " + d(s.statementDate()) + ", chúng tôi gồm có:</p>"
                + "<p><b>BÊN A (BÊN BÁN):</b> " + e(OrderPrintService.COMPANY) + "</p>"
                + "<p><b>BÊN B (ĐẠI LÝ):</b> " + e(s.customerName()) + " – Mã: " + e(s.customerCode())
                + (s.customerTaxCode() != null ? " – MST: " + e(s.customerTaxCode()) : "")
                + (s.customerAddress() != null ? "<br>Địa chỉ: " + e(s.customerAddress()) : "")
                + (s.customerPhone() != null ? " – ĐT: " + e(s.customerPhone()) : "") + "</p>"
                + "<p>Cùng đối chiếu công nợ, số liệu như sau (đơn vị: đồng):</p>"
                + "<table class=\"sum\">"
                + "<tr><td>I. Số dư nợ đầu kỳ (đến hết ngày trước " + d(s.fromDate()) + ")</td><td class=\"n\">"
                + CustomerDebtStatementService.vnd(s.openingBalance()) + "</td></tr>"
                + "<tr><td>II. Phát sinh tăng trong kỳ (mua hàng)</td><td class=\"n\">"
                + CustomerDebtStatementService.vnd(s.totalDebit()) + "</td></tr>"
                + "<tr><td>III. Phát sinh giảm trong kỳ (thanh toán, giảm trừ)</td><td class=\"n\">"
                + CustomerDebtStatementService.vnd(s.totalCredit()) + "</td></tr>"
                + "<tr><td><b>IV. Số dư nợ cuối kỳ (đến ngày " + d(s.toDate()) + ")</b></td><td class=\"n\"><b>"
                + CustomerDebtStatementService.vnd(s.closingBalance()) + "</b></td></tr></table>"
                + "<p>Bằng chữ: <b>" + e(CustomerDebtStatementService.words(s.closingBalance())) + "</b></p>"
                + "<table><thead><tr><th>Ngày</th><th>Số chứng từ</th><th>Loại</th><th>Diễn giải</th><th>Phát sinh tăng</th>"
                + "<th>Phát sinh giảm</th><th>Số dư</th></tr></thead><tbody>"
                + (rows.length() > 0 ? rows : "<tr><td colspan=\"7\" class=\"c\">Không có phát sinh trong kỳ</td></tr>")
                + "</tbody></table>"
                + "<p>Hai bên thống nhất số liệu công nợ nêu trên. Biên bản lập thành 02 bản, mỗi bên giữ 01 bản.</p>"
                + (s.agencyFeedback() != null ? "<p><b>Ý kiến của đại lý:</b> " + e(s.agencyFeedback()) + "</p>" : "")
                + "<div class=\"sign\"><div><b>ĐẠI DIỆN BÊN A</b><i>(Ký, ghi rõ họ tên)</i><br><br>"
                + e(s.confirmedBy()) + (s.confirmedAt() != null ? "<br><small>Chốt lúc " + s.confirmedAt().format(DATE_TIME)
                + "</small>" : "") + "</div>"
                + "<div><b>ĐẠI DIỆN BÊN B</b><i>(Ký, ghi rõ họ tên)</i><br><br>" + e(s.agencySignedBy())
                + (s.agencySignedAt() != null ? "<br><small>" + (DebtReconciliation.STATUS_AGENCY_ACCEPTED.equals(s.status())
                ? "Xác nhận trên cổng đại lý lúc " : "Phản hồi lúc ") + s.agencySignedAt().format(DATE_TIME) + "</small>" : "")
                + "</div></div>"
                + (autoPrint ? "<script>window.onload=function(){window.print()}</script>" : "")
                + "</body></html>";
    }

    private static String e(String s) {
        return CustomerDebtStatementService.esc(s);
    }

    private static String d(LocalDate date) {
        return date == null ? "" : date.format(DATE);
    }

    private static String blank(BigDecimal v) {
        return v == null || v.signum() == 0 ? "" : CustomerDebtStatementService.vnd(v);
    }
}
