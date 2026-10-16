package com.erp.backend.service;

import com.erp.backend.dto.debt.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S8-09: Báo cáo công nợ quá hạn theo tuổi nợ.
 * - Tổng hợp theo nhóm tuổi nợ và theo NV kinh doanh phụ trách.
 * - Xếp hạng đại lý rủi ro cao nhất trước:
 *   HIGH = có nợ trên 90 ngày hoặc còn nợ vượt hạn mức; MEDIUM = có nợ quá số ngày nợ tối đa.
 *   Cùng mức: nợ trên 90 ngày nhiều hơn -> nợ quá hạn nhiều hơn -> khoản nợ lâu hơn -> mã đại lý.
 * - Xuất Excel. Số liệu lấy từ sổ công nợ S7-03 (cùng cách tính, cùng phân quyền: NV kinh doanh chỉ thấy đại lý của mình).
 */
@Service
@RequiredArgsConstructor
public class OverdueDebtReportService {

    static final String HIGH = "HIGH";
    static final String MEDIUM = "MEDIUM";
    static final int DEFAULT_TOP = 10;
    static final int MAX_TOP = 100;
    static final List<String> BUCKET_ORDER = List.of("D0_30", "D31_60", "D61_90", "D90_PLUS");
    static final Map<String, String> BUCKET_LABELS = Map.of("D0_30", "0–30 ngày", "D31_60", "31–60 ngày",
            "D61_90", "61–90 ngày", "D90_PLUS", "Trên 90 ngày");
    static final String UNASSIGNED_REP = "Chưa phân công nhân viên";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DebtLedgerService debtLedgerService;

    public record ExportFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public OverdueReportResponse report(Long salesRepId, Integer top, UserDetailsImpl actor) {
        int limit = top == null || top <= 0 ? DEFAULT_TOP : Math.min(top, MAX_TOP);
        DebtLedgerService.Computed c = debtLedgerService.compute(
                new DebtLedgerService.Filter(null, salesRepId, null, true, false, "OVERDUE"), actor);
        return build(c, limit);
    }

    @Transactional(readOnly = true)
    public ExportFile export(Long salesRepId, UserDetailsImpl actor) {
        OverdueReportResponse r = report(salesRepId, MAX_TOP, actor);
        return new ExportFile("bao-cao-no-qua-han-" + r.asOf().format(FILE_DATE) + ".xlsx", excel(r));
    }

    static OverdueReportResponse build(DebtLedgerService.Computed c, int top) {
        List<DebtAgingRow> overdueRows = c.rows().stream().filter(r -> r.overdueAmount().signum() > 0).toList();

        List<OverdueRiskRow> ranked = new ArrayList<>();
        List<DebtAgingRow> sorted = new ArrayList<>(overdueRows);
        sorted.sort(riskOrder());
        int rank = 1;
        for (DebtAgingRow r : sorted) {
            ranked.add(new OverdueRiskRow(rank++, r.customerId(), r.customerCode(), r.customerName(), r.salesRepId(),
                    r.salesRepName(), riskLevel(r), reasons(r), r.creditLimit(), r.outstanding(), r.overdueAmount(),
                    r.over90(), r.oldestDebtDays()));
        }

        List<OverdueBucketSummary> byBucket = new ArrayList<>();
        for (String b : BUCKET_ORDER) {
            int count = 0;
            BigDecimal amount = ZERO;
            for (DebtAgingRow r : overdueRows) {
                BigDecimal v = DebtLedgerService.bucketAmount(r, b);
                if (v.signum() > 0) {
                    count++;
                    amount = amount.add(v);
                }
            }
            byBucket.add(new OverdueBucketSummary(b, BUCKET_LABELS.get(b), count, amount));
        }

        Map<Long, List<DebtAgingRow>> perRep = new LinkedHashMap<>();
        for (DebtAgingRow r : overdueRows) {
            perRep.computeIfAbsent(r.salesRepId() != null ? r.salesRepId() : -1L, k -> new ArrayList<>()).add(r);
        }
        List<OverdueRepSummary> byRep = new ArrayList<>();
        for (Map.Entry<Long, List<DebtAgingRow>> e : perRep.entrySet()) {
            List<DebtAgingRow> rows = e.getValue();
            DebtAgingRow first = rows.get(0);
            byRep.add(new OverdueRepSummary(e.getKey() < 0 ? null : e.getKey(),
                    e.getKey() < 0 || first.salesRepName() == null ? UNASSIGNED_REP : first.salesRepName(),
                    rows.size(), (int) rows.stream().filter(r -> HIGH.equals(riskLevel(r))).count(),
                    sum(rows, DebtAgingRow::outstanding), sum(rows, DebtAgingRow::overdueAmount),
                    sum(rows, DebtAgingRow::d0To30), sum(rows, DebtAgingRow::d31To60),
                    sum(rows, DebtAgingRow::d61To90), sum(rows, DebtAgingRow::over90)));
        }
        byRep.sort(Comparator.comparing(OverdueRepSummary::overdueAmount).reversed()
                .thenComparing(OverdueRepSummary::salesRepName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        int highCount = (int) ranked.stream().filter(r -> HIGH.equals(r.riskLevel())).count();
        return new OverdueReportResponse(c.asOf(), ranked.size(), highCount,
                sum(overdueRows, DebtAgingRow::outstanding), sum(overdueRows, DebtAgingRow::overdueAmount),
                byBucket, byRep, List.copyOf(ranked.subList(0, Math.min(top, ranked.size()))), ranked);
    }

    // ======================= XẾP HẠNG RỦI RO =======================

    static String riskLevel(DebtAgingRow r) {
        return r.over90().signum() > 0 || overLimit(r) ? HIGH : MEDIUM;
    }

    static boolean overLimit(DebtAgingRow r) {
        return r.creditLimit() != null && r.creditLimit().signum() > 0 && r.outstanding().compareTo(r.creditLimit()) > 0;
    }

    static List<String> reasons(DebtAgingRow r) {
        List<String> out = new ArrayList<>();
        if (r.over90().signum() > 0) {
            out.add("Nợ trên 90 ngày " + CustomerCreditService.vnd(r.over90()));
        }
        if (overLimit(r)) {
            out.add("Còn nợ " + CustomerCreditService.vnd(r.outstanding()) + " vượt hạn mức "
                    + CustomerCreditService.vnd(r.creditLimit()));
        }
        out.add("Quá hạn " + CustomerCreditService.vnd(r.overdueAmount()) + ", khoản lâu nhất " + r.oldestDebtDays()
                + " ngày");
        return out;
    }

    static Comparator<DebtAgingRow> riskOrder() {
        Comparator<DebtAgingRow> byLevel = Comparator.comparingInt(r -> HIGH.equals(riskLevel(r)) ? 0 : 1);
        return byLevel
                .thenComparing(Comparator.comparing(DebtAgingRow::over90).reversed())
                .thenComparing(Comparator.comparing(DebtAgingRow::overdueAmount).reversed())
                .thenComparing(Comparator.comparingLong(DebtAgingRow::oldestDebtDays).reversed())
                .thenComparing(DebtAgingRow::customerCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    // ======================= EXCEL =======================

    static byte[] excel(OverdueReportResponse r) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat fmt = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            Font redFont = wb.createFont();
            redFont.setBold(true);
            redFont.setColor(IndexedColors.RED.getIndex());
            CellStyle title = wb.createCellStyle();
            title.setFont(titleFont);
            CellStyle header = wb.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setWrapText(true);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(fmt.getFormat("#,##0"));
            CellStyle totalMoney = wb.createCellStyle();
            totalMoney.cloneStyleFrom(money);
            totalMoney.setFont(bold);
            CellStyle totalText = wb.createCellStyle();
            totalText.setFont(bold);
            CellStyle high = wb.createCellStyle();
            high.setFont(redFont);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            String asOf = "Tính đến ngày: " + r.asOf().format(DATE) + " · " + r.overdueCustomerCount()
                    + " đại lý có nợ quá hạn, " + r.highRiskCount() + " đại lý rủi ro cao";

            // Sheet 1: xếp hạng rủi ro
            Sheet s1 = wb.createSheet("XepHangRuiRo");
            String[] h1 = {"Hạng", "Mã đại lý", "Tên đại lý", "NV phụ trách", "Mức rủi ro", "Lý do", "Hạn mức (₫)",
                    "Còn nợ (₫)", "Quá hạn (₫)", "Trên 90 ngày (₫)", "Tuổi nợ lâu nhất (ngày)"};
            header(s1, "BÁO CÁO CÔNG NỢ QUÁ HẠN – XẾP HẠNG RỦI RO", asOf, h1, title, header);
            int row = 4;
            for (OverdueRiskRow x : r.customers()) {
                Row xr = s1.createRow(row++);
                xr.createCell(0).setCellValue(x.rank());
                text(xr, 1, x.customerCode(), null);
                text(xr, 2, x.customerName(), null);
                text(xr, 3, x.salesRepName() != null ? x.salesRepName() : UNASSIGNED_REP, null);
                text(xr, 4, HIGH.equals(x.riskLevel()) ? "Cao" : "Trung bình", HIGH.equals(x.riskLevel()) ? high : null);
                text(xr, 5, String.join("; ", x.reasons()), wrap);
                num(xr, 6, x.creditLimit(), money);
                num(xr, 7, x.outstanding(), money);
                num(xr, 8, x.overdueAmount(), money);
                num(xr, 9, x.over90(), money);
                xr.createCell(10).setCellValue(x.oldestDebtDays());
            }
            Row t1 = s1.createRow(row);
            text(t1, 2, "TỔNG CỘNG", totalText);
            num(t1, 7, r.totalOutstanding(), totalMoney);
            num(t1, 8, r.totalOverdue(), totalMoney);
            widths(s1, 7, 14, 30, 22, 12, 50, 16, 16, 16, 16, 14);
            s1.createFreezePane(3, 4);

            // Sheet 2: theo nhóm tuổi nợ
            Sheet s2 = wb.createSheet("TheoNhomTuoiNo");
            header(s2, "NỢ QUÁ HẠN THEO NHÓM TUỔI NỢ", asOf, new String[]{"Nhóm tuổi nợ", "Số đại lý", "Số tiền (₫)"},
                    title, header);
            row = 4;
            BigDecimal bucketTotal = ZERO;
            for (OverdueBucketSummary b : r.byBucket()) {
                Row br = s2.createRow(row++);
                text(br, 0, b.label(), null);
                br.createCell(1).setCellValue(b.customerCount());
                num(br, 2, b.amount(), money);
                bucketTotal = bucketTotal.add(b.amount());
            }
            Row t2 = s2.createRow(row);
            text(t2, 0, "TỔNG CỘNG", totalText);
            num(t2, 2, bucketTotal, totalMoney);
            widths(s2, 20, 12, 18);

            // Sheet 3: theo NV phụ trách
            Sheet s3 = wb.createSheet("TheoNhanVien");
            String[] h3 = {"NV phụ trách", "Số đại lý nợ quá hạn", "Số đại lý rủi ro cao", "Còn nợ (₫)", "Quá hạn (₫)",
                    "0–30 ngày (₫)", "31–60 ngày (₫)", "61–90 ngày (₫)", "Trên 90 ngày (₫)"};
            header(s3, "NỢ QUÁ HẠN THEO NHÂN VIÊN PHỤ TRÁCH", asOf, h3, title, header);
            row = 4;
            for (OverdueRepSummary p : r.byRep()) {
                Row pr = s3.createRow(row++);
                text(pr, 0, p.salesRepName(), null);
                pr.createCell(1).setCellValue(p.customerCount());
                pr.createCell(2).setCellValue(p.highRiskCount());
                num(pr, 3, p.outstanding(), money);
                num(pr, 4, p.overdueAmount(), money);
                num(pr, 5, p.d0To30(), money);
                num(pr, 6, p.d31To60(), money);
                num(pr, 7, p.d61To90(), money);
                num(pr, 8, p.over90(), money);
            }
            Row t3 = s3.createRow(row);
            text(t3, 0, "TỔNG CỘNG", totalText);
            num(t3, 3, r.totalOutstanding(), totalMoney);
            num(t3, 4, r.totalOverdue(), totalMoney);
            widths(s3, 26, 14, 14, 16, 16, 16, 16, 16, 16);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED",
                    "Không tạo được tệp Excel, vui lòng thử lại", null);
        }
    }

    private static void header(Sheet s, String titleText, String sub, String[] headers, CellStyle title, CellStyle header) {
        text(s.createRow(0), 0, titleText, title);
        s.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.max(1, headers.length - 1)));
        text(s.createRow(1), 0, sub, null);
        Row h = s.createRow(3);
        for (int i = 0; i < headers.length; i++) {
            text(h, i, headers[i], header);
        }
    }

    private static void widths(Sheet s, int... w) {
        for (int i = 0; i < w.length; i++) {
            s.setColumnWidth(i, w[i] * 256);
        }
    }

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void num(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue((value != null ? value : BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP).longValueExact());
        cell.setCellStyle(style);
    }

    private static BigDecimal sum(List<DebtAgingRow> rows, java.util.function.Function<DebtAgingRow, BigDecimal> f) {
        BigDecimal s = ZERO;
        for (DebtAgingRow r : rows) {
            s = s.add(f.apply(r));
        }
        return s;
    }
}
