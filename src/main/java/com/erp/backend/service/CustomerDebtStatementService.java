package com.erp.backend.service;

import com.erp.backend.dto.debt.CustomerDebtStatementResponse;
import com.erp.backend.dto.debt.DebtStatementEntry;
import com.erp.backend.entity.CreditNote;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Invoice;
import com.erp.backend.entity.PaymentReceipt;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CreditNoteRepository;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.InvoiceRepository;
import com.erp.backend.repository.PaymentReceiptRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * S8-10: Sổ chi tiết công nợ của 1 đại lý (dùng chung cho biên bản đối chiếu S7-06).
 * - Phát sinh tăng: hoá đơn đã phát hành (theo ngày hoá đơn).
 * - Phát sinh giảm: phiếu thu đã xác nhận (theo ngày thu) và chứng từ giảm trừ do trả hàng (theo ngày chứng từ).
 * - Số dư đầu kỳ = tổng tăng - tổng giảm của mọi chứng từ TRƯỚC ngày bắt đầu kỳ.
 * - Mỗi dòng có số dư luỹ kế; số dư cuối kỳ = đầu kỳ + tăng - giảm.
 * Chỉ tính chứng từ kế toán; đơn đã duyệt nhưng chưa xuất hoá đơn không nằm trong sổ chi tiết.
 */
@Service
@RequiredArgsConstructor
public class CustomerDebtStatementService {

    public static final String TYPE_INVOICE = "INVOICE";
    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_ADJUSTMENT = "ADJUSTMENT";
    /** Kỳ xem tối đa (ngày) để tránh xuất file quá lớn. */
    static final long MAX_PERIOD_DAYS = 366L * 5;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Map<String, Integer> TYPE_ORDER = Map.of(TYPE_INVOICE, 0, TYPE_PAYMENT, 1, TYPE_ADJUSTMENT, 2);

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final CreditNoteRepository creditNoteRepository;

    Clock clock = Clock.system(VN_ZONE);

    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    /** Kỳ đã chuẩn hoá: thiếu toDate = hôm nay, thiếu fromDate = ngày đầu tháng của toDate. */
    public record Period(LocalDate from, LocalDate to) {
    }

    @Transactional(readOnly = true)
    public CustomerDebtStatementResponse statement(Long customerId, LocalDate fromDate, LocalDate toDate,
                                                   UserDetailsImpl actor) {
        Customer customer = loadCustomer(customerId, actor);
        Period p = period(fromDate, toDate);
        return compute(customer, p.from(), p.to());
    }

    @Transactional(readOnly = true)
    public ExportFile export(Long customerId, LocalDate fromDate, LocalDate toDate, String format, boolean autoPrint,
                             UserDetailsImpl actor) {
        CustomerDebtStatementResponse s = statement(customerId, fromDate, toDate, actor);
        String base = "so-chi-tiet-cong-no-" + safe(s.customerCode()) + "-" + s.fromDate().format(FILE_DATE)
                + "-" + s.toDate().format(FILE_DATE);
        String f = format == null ? "xlsx" : format.trim().toLowerCase();
        return switch (f) {
            case "xlsx", "excel" -> new ExportFile(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel(s));
            // PDF: trả trang HTML khổ A4, trình duyệt in / "Lưu thành PDF" (giống in phiếu thu S7-09, không thêm thư viện PDF)
            case "pdf", "html" -> new ExportFile(base + ".html", "text/html;charset=UTF-8",
                    html(s, autoPrint).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_FORMAT",
                    "Định dạng xuất: xlsx hoặc pdf", "format");
        };
    }

    /** Đại lý tồn tại và người xem được phép xem (NV kinh doanh chỉ đại lý mình phụ trách, đại lý chỉ chính mình) -> khác 404. */
    Customer loadCustomer(Long customerId, UserDetailsImpl actor) {
        if (customerId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CUSTOMER_REQUIRED", "Vui lòng chọn đại lý", "customerId");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer, customerRepository);
        return customer;
    }

    Period period(LocalDate fromDate, LocalDate toDate) {
        LocalDate to = toDate != null ? toDate : LocalDate.now(clock);
        LocalDate from = fromDate != null ? fromDate : to.withDayOfMonth(1);
        if (from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD",
                    "Từ ngày phải trước hoặc bằng đến ngày", "fromDate");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PERIOD_TOO_LONG",
                    "Kỳ xem tối đa 5 năm", "fromDate");
        }
        return new Period(from, to);
    }

    /** Tính sổ chi tiết của đại lý trong kỳ [from, to] (cả 2 đầu). */
    CustomerDebtStatementResponse compute(Customer c, LocalDate from, LocalDate to) {
        List<DebtStatementEntry> all = new ArrayList<>();
        for (Invoice i : invoiceRepository.findByCustomer_IdAndInvoiceDateLessThanEqual(c.getId(), to)) {
            if (i.getStatus() != null && !Invoice.STATUS_ISSUED.equals(i.getStatus())) {
                continue;
            }
            all.add(new DebtStatementEntry(i.getId(), i.getInvoiceDate(), TYPE_INVOICE, "Hoá đơn bán hàng", i.getCode(),
                    "Mua hàng theo hoá đơn " + i.getCode(), money(i.getTotalAmount()), ZERO, null));
        }
        for (PaymentReceipt r : paymentReceiptRepository.findByCustomer_IdAndStatusAndPaymentDateLessThanEqual(
                c.getId(), PaymentReceipt.STATUS_CONFIRMED, to)) {
            String method = PaymentReceiptPrintService.methodLabel(r.getPaymentMethod());
            String desc = "Thanh toán (" + method.toLowerCase() + ")"
                    + (r.getNote() != null && !r.getNote().isBlank() ? " - " + r.getNote().trim() : "");
            all.add(new DebtStatementEntry(r.getId(), r.getPaymentDate(), TYPE_PAYMENT, "Phiếu thu", r.getCode(),
                    desc, ZERO, money(r.getAmount()), null));
        }
        for (CreditNote n : creditNoteRepository.findByCustomer_IdAndNoteDateLessThanEqual(c.getId(), to)) {
            if (n.getStatus() != null && !CreditNote.STATUS_CONFIRMED.equals(n.getStatus())) {
                continue;
            }
            all.add(new DebtStatementEntry(n.getId(), n.getNoteDate(), TYPE_ADJUSTMENT, "Giảm trừ do trả hàng",
                    n.getCode(), "Giảm trừ theo phiếu trả " + n.getSalesReturnCode() + " (HĐ " + n.getInvoiceCode() + ")",
                    ZERO, money(n.getAmount()), null));
        }
        all.sort(Comparator.comparing(DebtStatementEntry::date, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(e -> TYPE_ORDER.getOrDefault(e.type(), 9))
                .thenComparing(DebtStatementEntry::code, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DebtStatementEntry::refId, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal opening = ZERO;
        BigDecimal debit = ZERO;
        BigDecimal credit = ZERO;
        List<DebtStatementEntry> inPeriod = new ArrayList<>();
        BigDecimal running = null;
        for (DebtStatementEntry e : all) {
            boolean before = e.date() == null || e.date().isBefore(from);
            if (before) {
                opening = opening.add(e.debit()).subtract(e.credit());
                continue;
            }
            if (running == null) {
                running = opening;
            }
            running = running.add(e.debit()).subtract(e.credit());
            debit = debit.add(e.debit());
            credit = credit.add(e.credit());
            inPeriod.add(new DebtStatementEntry(e.refId(), e.date(), e.type(), e.typeLabel(), e.code(),
                    e.description(), e.debit(), e.credit(), running));
        }
        BigDecimal closing = opening.add(debit).subtract(credit);
        return new CustomerDebtStatementResponse(c.getId(), c.getCode(), c.getName(), c.getAddress(), c.getTaxCode(),
                c.getPhone(), from, to, opening, debit, credit, closing, List.copyOf(inPeriod));
    }

    // ======================= EXCEL =======================

    static byte[] excel(CustomerDebtStatementResponse s) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("SoChiTietCongNo");
            CellStyle title = wb.createCellStyle();
            Font tf = wb.createFont();
            tf.setBold(true);
            tf.setFontHeightInPoints((short) 14);
            title.setFont(tf);
            CellStyle bold = wb.createCellStyle();
            Font bf = wb.createFont();
            bf.setBold(true);
            bold.setFont(bf);
            CellStyle header = wb.createCellStyle();
            header.cloneStyleFrom(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setWrapText(true);
            CellStyle num = wb.createCellStyle();
            num.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            CellStyle numBold = wb.createCellStyle();
            numBold.cloneStyleFrom(num);
            numBold.setFont(bf);

            int r = 0;
            cell(sh.createRow(r++), 0, "SỔ CHI TIẾT CÔNG NỢ ĐẠI LÝ", title);
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));
            cell(sh.createRow(r++), 0, "Đại lý: " + s.customerCode() + " - " + s.customerName(), bold);
            if (s.customerAddress() != null) {
                cell(sh.createRow(r++), 0, "Địa chỉ: " + s.customerAddress(), null);
            }
            cell(sh.createRow(r++), 0, "Kỳ: từ " + s.fromDate().format(DATE) + " đến " + s.toDate().format(DATE)
                    + " · Đơn vị tính: đồng", null);
            r++;
            Row h = sh.createRow(r++);
            String[] heads = {"Ngày", "Loại chứng từ", "Số chứng từ", "Diễn giải", "Phát sinh tăng (Nợ)",
                    "Phát sinh giảm (Có)", "Số dư luỹ kế"};
            for (int i = 0; i < heads.length; i++) {
                cell(h, i, heads[i], header);
            }
            Row open = sh.createRow(r++);
            cell(open, 3, "Số dư đầu kỳ", bold);
            amount(open, 6, s.openingBalance(), numBold);
            for (DebtStatementEntry e : s.entries()) {
                Row row = sh.createRow(r++);
                cell(row, 0, e.date() != null ? e.date().format(DATE) : "", null);
                cell(row, 1, e.typeLabel(), null);
                cell(row, 2, e.code(), null);
                cell(row, 3, e.description(), null);
                amount(row, 4, e.debit(), num);
                amount(row, 5, e.credit(), num);
                amount(row, 6, e.balance(), num);
            }
            Row sum = sh.createRow(r++);
            cell(sum, 3, "Cộng phát sinh trong kỳ", bold);
            amount(sum, 4, s.totalDebit(), numBold);
            amount(sum, 5, s.totalCredit(), numBold);
            Row close = sh.createRow(r);
            cell(close, 3, "Số dư cuối kỳ", bold);
            amount(close, 6, s.closingBalance(), numBold);

            int[] widths = {12, 22, 18, 50, 20, 20, 20};
            for (int i = 0; i < widths.length; i++) {
                sh.setColumnWidth(i, widths[i] * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được file Excel sổ chi tiết công nợ", e);
        }
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    private static void amount(Row row, int col, BigDecimal value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue((value == null ? ZERO : value).setScale(0, RoundingMode.HALF_UP).doubleValue());
        c.setCellStyle(style);
    }

    // ======================= IN / PDF =======================

    static String html(CustomerDebtStatementResponse s, boolean autoPrint) {
        StringBuilder rows = new StringBuilder();
        rows.append("<tr class=\"b\"><td></td><td></td><td></td><td>Số dư đầu kỳ</td><td></td><td></td><td class=\"n\">")
                .append(vnd(s.openingBalance())).append("</td></tr>");
        for (DebtStatementEntry e : s.entries()) {
            rows.append("<tr><td>").append(e.date() != null ? e.date().format(DATE) : "").append("</td><td>")
                    .append(esc(e.typeLabel())).append("</td><td>").append(esc(e.code())).append("</td><td>")
                    .append(esc(e.description())).append("</td><td class=\"n\">").append(blankZero(e.debit()))
                    .append("</td><td class=\"n\">").append(blankZero(e.credit())).append("</td><td class=\"n\">")
                    .append(vnd(e.balance())).append("</td></tr>");
        }
        if (s.entries().isEmpty()) {
            rows.append("<tr><td colspan=\"7\" class=\"c\">Không có phát sinh trong kỳ</td></tr>");
        }
        rows.append("<tr class=\"b\"><td></td><td></td><td></td><td>Cộng phát sinh trong kỳ</td><td class=\"n\">")
                .append(vnd(s.totalDebit())).append("</td><td class=\"n\">").append(vnd(s.totalCredit()))
                .append("</td><td></td></tr>")
                .append("<tr class=\"b\"><td></td><td></td><td></td><td>Số dư cuối kỳ</td><td></td><td></td><td class=\"n\">")
                .append(vnd(s.closingBalance())).append("</td></tr>");

        return "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\"><title>Sổ chi tiết công nợ "
                + esc(s.customerCode()) + "</title><style>"
                + "@page{size:A4 landscape;margin:12mm}body{font-family:Arial,sans-serif;font-size:12px;color:#111}"
                + "h1{text-align:center;font-size:18px;margin:8px 0}.muted{color:#555}.c{text-align:center}"
                + "table{width:100%;border-collapse:collapse;margin-top:10px}th,td{border:1px solid #999;padding:4px 6px}"
                + "th{background:#eee}.n{text-align:right;white-space:nowrap}.b td{font-weight:bold}"
                + ".sign{display:flex;justify-content:space-around;margin-top:30px;text-align:center}.sign i{display:block;color:#555}"
                + "</style></head><body>"
                + "<div class=\"muted\">" + esc(OrderPrintService.COMPANY) + "</div>"
                + "<h1>SỔ CHI TIẾT CÔNG NỢ ĐẠI LÝ</h1>"
                + "<div class=\"c\">Từ ngày " + s.fromDate().format(DATE) + " đến ngày " + s.toDate().format(DATE) + "</div>"
                + "<p><b>Đại lý:</b> " + esc(s.customerCode()) + " - " + esc(s.customerName())
                + (s.customerAddress() != null ? "<br><b>Địa chỉ:</b> " + esc(s.customerAddress()) : "")
                + (s.customerTaxCode() != null ? "<br><b>MST:</b> " + esc(s.customerTaxCode()) : "") + "</p>"
                + "<table><thead><tr><th>Ngày</th><th>Loại chứng từ</th><th>Số chứng từ</th><th>Diễn giải</th>"
                + "<th>Phát sinh tăng</th><th>Phát sinh giảm</th><th>Số dư luỹ kế</th></tr></thead><tbody>"
                + rows + "</tbody></table>"
                + "<p>Số dư cuối kỳ bằng chữ: <b>" + esc(words(s.closingBalance())) + "</b></p>"
                + "<div class=\"sign\"><div>Người lập<i>(Ký, ghi rõ họ tên)</i></div>"
                + "<div>Kế toán công nợ<i>(Ký, ghi rõ họ tên)</i></div></div>"
                + (autoPrint ? "<script>window.onload=function(){window.print()}</script>" : "")
                + "</body></html>";
    }

    /** Đọc số dư bằng chữ; số âm (đại lý trả thừa) ghi rõ "Âm". */
    static String words(BigDecimal v) {
        BigDecimal x = v == null ? ZERO : v;
        return x.signum() < 0 ? "Âm " + lowerFirst(MoneyWords.vnd(x.negate())) : MoneyWords.vnd(x);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String blankZero(BigDecimal v) {
        return v == null || v.signum() == 0 ? "" : vnd(v);
    }

    static String vnd(BigDecimal v) {
        return CustomerCreditService.vnd(v == null ? ZERO : v);
    }

    static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    private static String safe(String code) {
        return code == null ? "dai-ly" : code.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
