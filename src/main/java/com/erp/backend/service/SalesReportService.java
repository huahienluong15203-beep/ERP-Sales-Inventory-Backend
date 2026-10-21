package com.erp.backend.service;

import com.erp.backend.dto.report.SalesReportMarginResponse;
import com.erp.backend.dto.report.SalesReportMarginRow;
import com.erp.backend.dto.report.SalesReportResponse;
import com.erp.backend.dto.report.SalesReportRow;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SalesReportRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * S8-06: Báo cáo doanh số đa chiều.
 * - Nhóm theo NV kinh doanh (REP), khu vực (REGION), đại lý (CUSTOMER), nhóm hàng (CATEGORY) hoặc SKU.
 * - Doanh số (tiền sau chiết khấu), sản lượng (ĐVT cơ sở), số đơn; so sánh với kỳ trước liền kề (PREVIOUS)
 *   hoặc cùng kỳ năm trước (LAST_YEAR); xuất Excel.
 * - Giá vốn và biên lợi nhuận gộp CHỈ trả cho Quản lý kinh doanh, bằng DTO riêng (quy tắc 6).
 * - Đơn tính doanh số: từ Đã duyệt trở đi (không tính nháp, chờ duyệt, huỷ, từ chối, giao không thành công),
 *   theo ngày duyệt. Giá vốn lấy theo giá vốn hiện tại của SKU (chưa lưu giá vốn tại thời điểm bán).
 */
@Service
@RequiredArgsConstructor
public class SalesReportService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final List<String> SALES_STATUSES = SalesOrder.OUTSTANDING_DEBT_STATUSES;
    static final Set<String> GROUPS = Set.of("REP", "REGION", "CUSTOMER", "CATEGORY", "SKU");
    static final Set<String> COMPARES = Set.of("PREVIOUS", "LAST_YEAR", "NONE");
    static final Map<String, String> GROUP_LABELS = Map.of("REP", "Nhân viên kinh doanh", "REGION", "Khu vực",
            "CUSTOMER", "Đại lý", "CATEGORY", "Nhóm hàng", "SKU", "Sản phẩm");
    static final Map<String, String> UNASSIGNED = Map.of("REP", "Chưa phân công nhân viên", "REGION", "Chưa gán khu vực",
            "CATEGORY", "Chưa phân nhóm hàng");
    static final long MAX_RANGE_DAYS = 366;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SalesReportRepository reportRepository;

    Clock clock = Clock.system(VN_ZONE);

    /** Số liệu tổng hợp của 1 nhóm trong 1 kỳ. */
    record Agg(Long id, String code, String name, BigDecimal revenue, BigDecimal quantity, BigDecimal cost, long orders) {
    }

    /** Kết quả tính đầy đủ (luôn có giá vốn); khi trả ra ngoài mới chọn DTO theo quyền. */
    record Computed(String groupBy, LocalDate from, LocalDate to, String compare, LocalDate compareFrom,
                    LocalDate compareTo, List<SalesReportMarginRow> rows, SalesReportMarginRow total) {
    }

    /**
     * QL kinh doanh nhận SalesReportMarginResponse (có biên lợi nhuận), Admin nhận SalesReportResponse (không có).
     */
    @Transactional(readOnly = true)
    public Object report(String groupBy, LocalDate fromDate, LocalDate toDate, String compare, UserDetailsImpl actor) {
        Computed c = compute(groupBy, fromDate, toDate, compare, actor);
        if (canSeeMargin(actor)) {
            return new SalesReportMarginResponse(c.groupBy(), c.from(), c.to(), c.compare(), c.compareFrom(),
                    c.compareTo(), true, c.rows(), c.total());
        }
        return new SalesReportResponse(c.groupBy(), c.from(), c.to(), c.compare(), c.compareFrom(), c.compareTo(),
                false, c.rows().stream().map(SalesReportService::withoutMargin).toList(), withoutMargin(c.total()));
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public ExportFile export(String groupBy, LocalDate fromDate, LocalDate toDate, String compare, UserDetailsImpl actor) {
        Computed c = compute(groupBy, fromDate, toDate, compare, actor);
        boolean margin = canSeeMargin(actor);
        String name = "BaoCaoDoanhSo_" + c.groupBy() + "_" + c.from().format(FILE_DATE) + "_" + c.to().format(FILE_DATE) + ".xlsx";
        return new ExportFile(name, excel(c, margin));
    }

    Computed compute(String groupBy, LocalDate fromDate, LocalDate toDate, String compare, UserDetailsImpl actor) {
        requireAccess(actor);
        String g = StringUtils.hasText(groupBy) ? groupBy.trim().toUpperCase(Locale.ROOT) : "REP";
        if (!GROUPS.contains(g)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_GROUP_BY",
                    "Nhóm theo: REP, REGION, CUSTOMER, CATEGORY hoặc SKU", "groupBy");
        }
        String cmp = StringUtils.hasText(compare) ? compare.trim().toUpperCase(Locale.ROOT) : "PREVIOUS";
        if (!COMPARES.contains(cmp)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_COMPARE",
                    "So sánh: PREVIOUS (kỳ trước liền kề), LAST_YEAR (cùng kỳ năm trước) hoặc NONE", "compare");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate to = toDate != null ? toDate : today;
        LocalDate from = fromDate != null ? fromDate : to.withDayOfMonth(1);
        if (from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày bắt đầu không được sau ngày kết thúc", "fromDate");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DATE_RANGE_TOO_LONG",
                    "Khoảng thời gian báo cáo tối đa 1 năm", "toDate");
        }
        LocalDate cFrom = null;
        LocalDate cTo = null;
        if ("PREVIOUS".equals(cmp)) {
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            cTo = from.minusDays(1);
            cFrom = cTo.minusDays(days - 1);
        } else if ("LAST_YEAR".equals(cmp)) {
            cFrom = from.minusYears(1);
            cTo = to.minusYears(1);
        }

        Map<String, Agg> current = load(g, from, to);
        Map<String, Agg> previous = cFrom != null ? load(g, cFrom, cTo) : Map.of();

        Set<String> keys = new LinkedHashSet<>(current.keySet());
        keys.addAll(previous.keySet());
        List<SalesReportMarginRow> rows = new ArrayList<>();
        BigDecimal tRev = BigDecimal.ZERO, tQty = BigDecimal.ZERO, tCost = BigDecimal.ZERO;
        BigDecimal pRev = BigDecimal.ZERO, pQty = BigDecimal.ZERO, pCost = BigDecimal.ZERO;
        long tOrders = 0;
        for (String key : keys) {
            Agg cur = current.get(key);
            Agg prev = previous.get(key);
            Agg ref = cur != null ? cur : prev;
            BigDecimal rev = cur != null ? cur.revenue() : BigDecimal.ZERO;
            BigDecimal qty = cur != null ? cur.quantity() : BigDecimal.ZERO;
            BigDecimal cost = cur != null ? cur.cost() : BigDecimal.ZERO;
            long orders = cur != null ? cur.orders() : 0;
            rows.add(row(key, ref.id(), ref.code(), ref.name(), rev, qty, orders, cost,
                    cFrom != null ? (prev != null ? prev.revenue() : BigDecimal.ZERO) : null,
                    cFrom != null ? (prev != null ? prev.quantity() : BigDecimal.ZERO) : null,
                    cFrom != null ? (prev != null ? prev.cost() : BigDecimal.ZERO) : null));
            tRev = tRev.add(rev);
            tQty = tQty.add(qty);
            tCost = tCost.add(cost);
            tOrders += orders;
            if (prev != null) {
                pRev = pRev.add(prev.revenue());
                pQty = pQty.add(prev.quantity());
                pCost = pCost.add(prev.cost());
            }
        }
        // Doanh số lớn lên trước; bằng nhau thì theo tên
        rows.sort(Comparator.comparing(SalesReportMarginRow::revenue).reversed()
                .thenComparing(r -> r.name() != null ? r.name() : ""));
        // Số đơn của dòng tổng tính theo từng nhóm nên với nhóm theo SKU / nhóm hàng có thể đếm 1 đơn nhiều lần
        SalesReportMarginRow total = row("TOTAL", null, null, "Tổng cộng", tRev, tQty, tOrders, tCost,
                cFrom != null ? pRev : null, cFrom != null ? pQty : null, cFrom != null ? pCost : null);
        return new Computed(g, from, to, cmp, cFrom, cTo, rows, total);
    }

    private Map<String, Agg> load(String group, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<Object[]> raw = switch (group) {
            case "REGION" -> reportRepository.byRegion(SALES_STATUSES, start, end);
            case "CUSTOMER" -> reportRepository.byCustomer(SALES_STATUSES, start, end);
            case "CATEGORY" -> reportRepository.byCategory(SALES_STATUSES, start, end);
            case "SKU" -> reportRepository.bySku(SALES_STATUSES, start, end);
            default -> reportRepository.byRep(SALES_STATUSES, start, end);
        };
        Map<String, Agg> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (Object[] r : raw) {
            if (r == null || r.length < 7) {
                continue;
            }
            Long id = r[0] != null ? ((Number) r[0]).longValue() : null;
            String key = id != null ? group + ":" + id : group + ":NONE";
            String code = r[1] != null ? String.valueOf(r[1]) : null;
            String name = r[2] != null ? String.valueOf(r[2]) : UNASSIGNED.getOrDefault(group, "Không xác định");
            map.put(key, new Agg(id, code, name, dec(r[3]), dec(r[4]), dec(r[5]),
                    r[6] != null ? ((Number) r[6]).longValue() : 0));
        }
        return map;
    }

    static SalesReportMarginRow row(String key, Long id, String code, String name, BigDecimal revenue, BigDecimal qty,
                                    long orders, BigDecimal cost, BigDecimal prevRevenue, BigDecimal prevQty,
                                    BigDecimal prevCost) {
        BigDecimal rev = money(revenue);
        BigDecimal c = money(cost);
        BigDecimal margin = rev.subtract(c);
        BigDecimal prevMargin = prevRevenue != null ? money(prevRevenue).subtract(money(prevCost)) : null;
        return new SalesReportMarginRow(key, id, code, name, rev, strip(qty), orders,
                prevRevenue != null ? money(prevRevenue) : null, prevQty != null ? strip(prevQty) : null,
                changePercent(rev, prevRevenue), c, margin, percentOf(margin, rev), prevMargin);
    }

    static SalesReportRow withoutMargin(SalesReportMarginRow r) {
        return new SalesReportRow(r.key(), r.id(), r.code(), r.name(), r.revenue(), r.quantity(), r.orderCount(),
                r.previousRevenue(), r.previousQuantity(), r.revenueChangePercent());
    }

    /** (hiện tại - kỳ trước) / kỳ trước x 100, 1 số lẻ; kỳ trước không có doanh số -> null. */
    static BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return money(current).subtract(previous).multiply(HUNDRED).divide(previous.abs(), 1, RoundingMode.HALF_UP);
    }

    static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** Biên lợi nhuận chỉ Quản lý kinh doanh xem được (Admin cũng không, theo quy tắc 6). */
    static boolean canSeeMargin(UserDetailsImpl actor) {
        return actor != null && actor.getAuthorities() != null
                && actor.getAuthorities().stream().anyMatch(a -> "ROLE_SALES_MANAGER".equals(a.getAuthority()));
    }

    private static void requireAccess(UserDetailsImpl actor) {
        boolean ok = actor != null && actor.getAuthorities() != null && actor.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES_MANAGER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
        if (!ok) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REPORT_NOT_ALLOWED",
                    "Chỉ Quản lý kinh doanh hoặc Quản trị xem được báo cáo doanh số", null);
        }
    }

    // ======================= EXCEL =======================

    static byte[] excel(Computed c, boolean margin) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("DoanhSo");
            DataFormat fmt = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
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
            CellStyle qty = wb.createCellStyle();
            qty.setDataFormat(fmt.getFormat("#,##0.####"));
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(fmt.getFormat("0.0"));
            CellStyle totalMoney = wb.createCellStyle();
            totalMoney.cloneStyleFrom(money);
            totalMoney.setFont(bold);
            CellStyle totalQty = wb.createCellStyle();
            totalQty.cloneStyleFrom(qty);
            totalQty.setFont(bold);
            CellStyle totalText = wb.createCellStyle();
            totalText.setFont(bold);

            List<String> headers = new ArrayList<>(List.of("STT", "Mã", GROUP_LABELS.getOrDefault(c.groupBy(), "Nhóm"),
                    "Doanh số (₫)", "Sản lượng (ĐVT cơ sở)", "Số đơn"));
            if (c.compareFrom() != null) {
                headers.addAll(List.of("Doanh số kỳ so sánh (₫)", "Tăng / giảm (%)"));
            }
            if (margin) {
                headers.addAll(List.of("Giá vốn (₫)", "Lãi gộp (₫)", "Biên LN gộp (%)"));
            }

            Row r0 = sheet.createRow(0);
            text(r0, 0, "BÁO CÁO DOANH SỐ THEO " + GROUP_LABELS.getOrDefault(c.groupBy(), "").toUpperCase(Locale.ROOT), title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.size() - 1));
            text(sheet.createRow(1), 0, "Kỳ: " + c.from().format(DATE) + " - " + c.to().format(DATE)
                    + (c.compareFrom() != null ? " · So sánh với: " + c.compareFrom().format(DATE) + " - "
                    + c.compareTo().format(DATE) : ""), null);
            Row h = sheet.createRow(3);
            for (int i = 0; i < headers.size(); i++) {
                text(h, i, headers.get(i), header);
            }
            int r = 4;
            int stt = 1;
            for (SalesReportMarginRow row : c.rows()) {
                Row x = sheet.createRow(r++);
                x.createCell(0).setCellValue(stt++);
                text(x, 1, row.code(), null);
                text(x, 2, row.name(), null);
                fill(x, row, c.compareFrom() != null, margin, money, qty, pct);
            }
            Row t = sheet.createRow(r);
            text(t, 2, "TỔNG CỘNG", totalText);
            fill(t, c.total(), c.compareFrom() != null, margin, totalMoney, totalQty, pct);
            int[] widths = {6, 14, 34, 18, 18, 10, 20, 14, 18, 18, 14};
            for (int i = 0; i < headers.size() && i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 4);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED",
                    "Không tạo được tệp Excel, vui lòng thử lại", null);
        }
    }

    /** Ghi các cột số (đổi sang double chỉ ở bước ghi Excel; mọi phép tính đã làm bằng BigDecimal). */
    private static void fill(Row x, SalesReportMarginRow row, boolean compare, boolean margin,
                             CellStyle money, CellStyle qty, CellStyle pct) {
        int col = 3;
        num(x, col++, row.revenue(), money);
        num(x, col++, row.quantity(), qty);
        num(x, col++, BigDecimal.valueOf(row.orderCount()), qty);
        if (compare) {
            num(x, col++, row.previousRevenue(), money);
            num(x, col++, row.revenueChangePercent(), pct);
        }
        if (margin) {
            num(x, col++, row.costOfGoods(), money);
            num(x, col++, row.grossMargin(), money);
            num(x, col, row.grossMarginPercent(), pct);
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
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        return new BigDecimal(String.valueOf(o));
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = (v == null ? BigDecimal.ZERO : v).stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
