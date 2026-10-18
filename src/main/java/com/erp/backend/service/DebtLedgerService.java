package com.erp.backend.service;

import com.erp.backend.dto.debt.DebtAgingRow;
import com.erp.backend.dto.debt.DebtAgingTotals;
import com.erp.backend.dto.debt.DebtLedgerResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.User;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CreditNoteRepository;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.DebtAgingRepository;
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
import java.util.function.Function;

/**
 * S7-03: Sổ công nợ & tuổi nợ theo đại lý.
 * - Cột: phải thu, đã thu, còn nợ; còn nợ chia theo tuổi nợ 0–30, 31–60, 61–90, trên 90 ngày.
 * - Tuổi nợ = số ngày từ ngày hoá đơn (hoặc ngày duyệt với đơn chưa xuất hoá đơn) đến hôm nay (giờ Việt Nam).
 * - Lọc riêng nhóm quá hạn (quá số ngày nợ tối đa của từng đại lý) hoặc theo 1 nhóm tuổi nợ; tổng cộng ở chân bảng.
 * - Mặc định sắp khoản nợ lâu nhất lên đầu để tập trung đòi trước. Xuất Excel.
 * - NV kinh doanh chỉ thấy đại lý mình phụ trách; Kế toán / QL kinh doanh / Admin thấy tất cả.
 * Cách tính công nợ giống kiểm tra công nợ khi tạo đơn (đơn chưa xuất hoá đơn + số còn nợ trên hoá đơn
 * - giảm trừ do trả hàng chưa đối trừ được, trừ vào khoản nợ cũ nhất trước).
 */
@Service
@RequiredArgsConstructor
public class DebtLedgerService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Set<String> BUCKETS = Set.of("D0_30", "D31_60", "D61_90", "D90_PLUS");
    static final Set<String> SORTS = Set.of("OLDEST", "NEWEST", "OUTSTANDING", "OVERDUE", "CODE");
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 500;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DebtAgingRepository agingRepository;
    private final CustomerRepository customerRepository;
    private final CreditNoteRepository creditNoteRepository;

    Clock clock = Clock.system(VN_ZONE);

    /** Bộ lọc sổ công nợ. */
    public record Filter(String keyword, Long salesRepId, String bucket, boolean overdueOnly, boolean includeSettled,
                         String sort) {
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    /** Kết quả đã lọc + sắp xếp (đủ mọi dòng) để dùng cho màn hình, Excel và báo cáo nợ quá hạn (S8-09). */
    public record Computed(LocalDate asOf, List<DebtAgingRow> rows, DebtAgingTotals totals) {
    }

    @Transactional(readOnly = true)
    public DebtLedgerResponse ledger(Filter filter, int page, int size, UserDetailsImpl actor) {
        Computed c = compute(filter, actor);
        int safeSize = size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int safePage = Math.max(0, page);
        int from = (int) Math.min((long) safePage * safeSize, c.rows().size());
        int to = Math.min(from + safeSize, c.rows().size());
        int totalPages = (int) Math.ceil(c.rows().size() / (double) safeSize);
        return new DebtLedgerResponse(c.asOf(), List.copyOf(c.rows().subList(from, to)), safePage, safeSize,
                c.rows().size(), totalPages, c.totals());
    }

    @Transactional(readOnly = true)
    public ExportFile export(Filter filter, UserDetailsImpl actor) {
        Computed c = compute(filter, actor);
        return new ExportFile("so-cong-no-" + c.asOf().format(FILE_DATE) + ".xlsx", excel(c, filter));
    }

    @Transactional(readOnly = true)
    public Computed compute(Filter filter, UserDetailsImpl actor) {
        Filter f = normalize(filter);
        Long repScope = scopeSalesRep(actor, f.salesRepId());
        LocalDate today = LocalDate.now(clock);

        List<Object[]> invoices = repScope == null ? agingRepository.invoiceItems()
                : agingRepository.invoiceItemsBySalesRep(repScope);
        List<Object[]> orders = repScope == null
                ? agingRepository.uninvoicedOrderItems(SalesOrder.OUTSTANDING_DEBT_STATUSES)
                : agingRepository.uninvoicedOrderItemsBySalesRep(SalesOrder.OUTSTANDING_DEBT_STATUSES, repScope);

        Map<Long, Acc> byCustomer = new LinkedHashMap<>();
        for (Object[] r : nz(invoices)) {
            Long cid = id(r[0]);
            if (cid == null) {
                continue;
            }
            BigDecimal total = money(r[2]);
            BigDecimal remaining = money(r[4]).max(ZERO);
            BigDecimal paid = r[3] != null ? money(r[3]) : total.subtract(remaining).max(ZERO);
            LocalDate invDate = (LocalDate) r[1];
            LocalDateTime invDt = invDate != null ? invDate.atStartOfDay() : today.atStartOfDay();
            byCustomer.computeIfAbsent(cid, k -> new Acc()).add(total, paid, remaining, invDate, false, invDt);
        }
        for (Object[] r : nz(orders)) {
            Long cid = id(r[0]);
            if (cid == null) {
                continue;
            }
            BigDecimal total = money(r[2]);
            LocalDateTime approvedAt = (LocalDateTime) r[1];
            LocalDateTime ordDt = approvedAt != null ? approvedAt : today.atStartOfDay();
            byCustomer.computeIfAbsent(cid, k -> new Acc())
                    .add(total, ZERO, total, approvedAt != null ? approvedAt.toLocalDate() : today, true, ordDt);
        }

        Map<Long, Customer> customers = new HashMap<>();
        Map<Long, BigDecimal> credits = new HashMap<>();
        if (!byCustomer.isEmpty()) {
            for (Customer c : customerRepository.findAllById(byCustomer.keySet())) {
                customers.put(c.getId(), c);
            }
            for (Object[] r : nz(creditNoteRepository.sumUnappliedByCustomerIds(byCustomer.keySet()))) {
                Long cid = id(r[0]);
                if (cid != null) {
                    credits.put(cid, money(r[1]).max(ZERO));
                }
            }
        }

        String kw = f.keyword() == null ? null : f.keyword().toLowerCase(Locale.ROOT);
        List<DebtAgingRow> rows = new ArrayList<>();
        for (Map.Entry<Long, Acc> e : byCustomer.entrySet()) {
            Customer c = customers.get(e.getKey());
            if (c == null) {
                continue;
            }
            // Lọc theo phạm vi đã xác định ở scopeSalesRep (NV kinh doanh luôn là chính mình, không dùng salesRepId gửi lên)
            if (repScope != null && (c.getSalesRep() == null || !repScope.equals(c.getSalesRep().getId()))) {
                continue;
            }
            if (kw != null && !contains(c.getCode(), kw) && !contains(c.getName(), kw) && !contains(c.getPhone(), kw)) {
                continue;
            }
            DebtAgingRow row = e.getValue().toRow(c, today, credits.getOrDefault(c.getId(), ZERO));
            if (!f.includeSettled() && row.outstanding().signum() <= 0) {
                continue;
            }
            if (f.overdueOnly() && row.overdueAmount().signum() <= 0) {
                continue;
            }
            if (f.bucket() != null && bucketAmount(row, f.bucket()).signum() <= 0) {
                continue;
            }
            rows.add(row);
        }
        rows.sort(comparator(f.sort()));
        return new Computed(today, rows, totals(rows));
    }

    // ======================= TÍNH TUỔI NỢ =======================

    /** Nhóm tuổi nợ: 0–30, 31–60, 61–90, trên 90 ngày (ngày tương lai coi như 0). */
    static String bucketOf(long ageDays) {
        if (ageDays <= 30) {
            return "D0_30";
        }
        if (ageDays <= 60) {
            return "D31_60";
        }
        if (ageDays <= 90) {
            return "D61_90";
        }
        return "D90_PLUS";
    }

    static BigDecimal bucketAmount(DebtAgingRow r, String bucket) {
        return switch (bucket) {
            case "D0_30" -> r.d0To30();
            case "D31_60" -> r.d31To60();
            case "D61_90" -> r.d61To90();
            default -> r.over90();
        };
    }

    /** Cộng dồn các khoản của 1 đại lý. */
    static final class Acc {
        private record Item(BigDecimal remaining, LocalDate date) {
        }

        private BigDecimal receivable = ZERO;
        private BigDecimal collected = ZERO;
        private BigDecimal outstanding = ZERO;
        private BigDecimal uninvoiced = ZERO;
        private LocalDateTime latestActivity;
        private final List<Item> items = new ArrayList<>();

        void add(BigDecimal total, BigDecimal paid, BigDecimal remaining, LocalDate date, boolean uninvoicedOrder) {
            add(total, paid, remaining, date, uninvoicedOrder, null);
        }

        void add(BigDecimal total, BigDecimal paid, BigDecimal remaining, LocalDate date, boolean uninvoicedOrder,
                 LocalDateTime activityTime) {
            receivable = receivable.add(total);
            collected = collected.add(paid);
            outstanding = outstanding.add(remaining);
            if (uninvoicedOrder) {
                uninvoiced = uninvoiced.add(remaining);
            }
            if (remaining.signum() > 0) {
                items.add(new Item(remaining, date));
            }
            if (activityTime != null) {
                if (latestActivity == null || activityTime.isAfter(latestActivity)) {
                    latestActivity = activityTime;
                }
            }
        }

        DebtAgingRow toRow(Customer c, LocalDate today) {
            return toRow(c, today, ZERO);
        }

        /** credit: giảm trừ chưa đối trừ của đại lý, trừ vào khoản nợ cũ nhất trước (khoản về 0 thì không còn tính tuổi nợ). */
        DebtAgingRow toRow(Customer c, LocalDate today, BigDecimal credit) {
            Map<String, BigDecimal> buckets = new HashMap<>();
            BUCKETS.forEach(b -> buckets.put(b, ZERO));
            BigDecimal overdue = ZERO;
            long oldest = 0;
            Integer maxDays = c.getMaxDebtDays();
            BigDecimal creditLeft = credit == null ? ZERO : credit.max(ZERO);
            List<Item> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparing(Item::date, Comparator.nullsLast(Comparator.naturalOrder())));
            for (Item it : sorted) {
                BigDecimal remaining = it.remaining();
                if (creditLeft.signum() > 0) {
                    BigDecimal used = creditLeft.min(remaining);
                    remaining = remaining.subtract(used);
                    creditLeft = creditLeft.subtract(used);
                }
                if (remaining.signum() <= 0) {
                    continue;
                }
                long age = it.date() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(it.date(), today));
                buckets.merge(bucketOf(age), remaining, BigDecimal::add);
                if (maxDays != null && maxDays >= 0 && age > maxDays) {
                    overdue = overdue.add(remaining);
                }
                oldest = Math.max(oldest, age);
            }
            BigDecimal creditBalance = credit == null ? ZERO : credit.max(ZERO);
            User rep = c.getSalesRep();
            return new DebtAgingRow(c.getId(), c.getCode(), c.getName(), rep != null ? rep.getId() : null,
                    rep != null ? rep.getFullName() : null, money(c.getCreditLimit()), maxDays, receivable, collected,
                    outstanding.subtract(creditBalance), uninvoiced, buckets.get("D0_30"), buckets.get("D31_60"),
                    buckets.get("D61_90"), buckets.get("D90_PLUS"), overdue, oldest, creditBalance, latestActivity);
        }
    }

    static DebtAgingTotals totals(List<DebtAgingRow> rows) {
        return new DebtAgingTotals(rows.size(),
                sum(rows, DebtAgingRow::receivable), sum(rows, DebtAgingRow::collected),
                sum(rows, DebtAgingRow::outstanding), sum(rows, DebtAgingRow::uninvoiced),
                sum(rows, DebtAgingRow::d0To30), sum(rows, DebtAgingRow::d31To60),
                sum(rows, DebtAgingRow::d61To90), sum(rows, DebtAgingRow::over90),
                sum(rows, DebtAgingRow::overdueAmount), sum(rows, DebtAgingRow::creditBalance));
    }

    static Comparator<DebtAgingRow> comparator(String sort) {
        Comparator<DebtAgingRow> byCode = Comparator.comparing(DebtAgingRow::customerCode,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        return switch (sort) {
            case "NEWEST" -> Comparator.comparing(DebtAgingRow::latestActivity, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparing(DebtAgingRow::customerId).reversed());
            case "OUTSTANDING" -> Comparator.comparing(DebtAgingRow::outstanding).reversed().thenComparing(byCode);
            case "OVERDUE" -> Comparator.comparing(DebtAgingRow::overdueAmount).reversed()
                    .thenComparing(Comparator.comparing(DebtAgingRow::outstanding).reversed()).thenComparing(byCode);
            case "CODE" -> byCode;
            // OLDEST: khoản nợ lâu nhất trước, cùng tuổi thì nợ nhiều hơn trước
            default -> Comparator.comparingLong(DebtAgingRow::oldestDebtDays).reversed()
                    .thenComparing(Comparator.comparing(DebtAgingRow::outstanding).reversed()).thenComparing(byCode);
        };
    }

    // ======================= QUYỀN & THAM SỐ =======================

    /** null = xem mọi đại lý; khác null = chỉ đại lý của NV kinh doanh đó. */
    static Long scopeSalesRep(UserDetailsImpl actor, Long requestedRepId) {
        if (CustomerAccess.hasFullAccess(actor)) {
            return requestedRepId;
        }
        if (CustomerAccess.isSalesRep(actor)) {
            return actor.getId(); // bỏ qua salesRepId gửi lên để không xem được đại lý người khác
        }
        throw new BusinessException(HttpStatus.FORBIDDEN, "DEBT_LEDGER_FORBIDDEN",
                "Bạn không có quyền xem sổ công nợ", null);
    }

    static Filter normalize(Filter f) {
        if (f == null) {
            return new Filter(null, null, null, false, false, "OLDEST");
        }
        String bucket = StringUtils.hasText(f.bucket()) ? f.bucket().trim().toUpperCase(Locale.ROOT) : null;
        if (bucket != null && !BUCKETS.contains(bucket)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AGING_BUCKET",
                    "Nhóm tuổi nợ: D0_30, D31_60, D61_90 hoặc D90_PLUS", "bucket");
        }
        String sort = StringUtils.hasText(f.sort()) ? f.sort().trim().toUpperCase(Locale.ROOT) : "OLDEST";
        if (!SORTS.contains(sort)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SORT",
                    "Sắp xếp: NEWEST, OLDEST, OUTSTANDING, OVERDUE hoặc CODE", "sort");
        }
        String kw = StringUtils.hasText(f.keyword()) ? f.keyword().trim() : null;
        if (kw != null && kw.length() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "KEYWORD_TOO_LONG", "Từ khoá tối đa 100 ký tự", "keyword");
        }
        return new Filter(kw, f.salesRepId(), bucket, f.overdueOnly(), f.includeSettled(), sort);
    }

    // ======================= EXCEL =======================

    static byte[] excel(Computed c, Filter filter) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("SoCongNo");
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
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(fmt.getFormat("#,##0"));
            CellStyle totalMoney = wb.createCellStyle();
            totalMoney.cloneStyleFrom(money);
            totalMoney.setFont(bold);
            CellStyle totalText = wb.createCellStyle();
            totalText.setFont(bold);

            String[] headers = {"STT", "Mã đại lý", "Tên đại lý", "NV phụ trách", "Hạn mức (₫)", "Số ngày nợ tối đa",
                    "Phải thu (₫)", "Đã thu (₫)", "Còn nợ (₫)", "Trong đó chưa xuất HĐ (₫)", "0–30 ngày (₫)",
                    "31–60 ngày (₫)", "61–90 ngày (₫)", "Trên 90 ngày (₫)", "Quá hạn (₫)", "Tuổi nợ lâu nhất (ngày)",
                    "Giảm trừ chưa đối trừ (₫)"};
            Row r0 = sheet.createRow(0);
            text(r0, 0, "SỔ CÔNG NỢ & TUỔI NỢ ĐẠI LÝ", title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.length - 1));
            text(sheet.createRow(1), 0, "Tính đến ngày: " + c.asOf().format(DATE) + describe(filter), null);
            Row h = sheet.createRow(3);
            for (int i = 0; i < headers.length; i++) {
                text(h, i, headers[i], header);
            }
            int r = 4;
            int stt = 1;
            for (DebtAgingRow row : c.rows()) {
                Row x = sheet.createRow(r++);
                x.createCell(0).setCellValue(stt++);
                text(x, 1, row.customerCode(), null);
                text(x, 2, row.customerName(), null);
                text(x, 3, row.salesRepName(), null);
                num(x, 4, row.creditLimit(), money);
                if (row.maxDebtDays() != null) {
                    x.createCell(5).setCellValue(row.maxDebtDays());
                }
                num(x, 6, row.receivable(), money);
                num(x, 7, row.collected(), money);
                num(x, 8, row.outstanding(), money);
                num(x, 9, row.uninvoiced(), money);
                num(x, 10, row.d0To30(), money);
                num(x, 11, row.d31To60(), money);
                num(x, 12, row.d61To90(), money);
                num(x, 13, row.over90(), money);
                num(x, 14, row.overdueAmount(), money);
                x.createCell(15).setCellValue(row.oldestDebtDays());
                num(x, 16, row.creditBalance(), money);
            }
            DebtAgingTotals t = c.totals();
            Row tr = sheet.createRow(r);
            text(tr, 2, "TỔNG CỘNG (" + t.customerCount() + " đại lý)", totalText);
            num(tr, 6, t.receivable(), totalMoney);
            num(tr, 7, t.collected(), totalMoney);
            num(tr, 8, t.outstanding(), totalMoney);
            num(tr, 9, t.uninvoiced(), totalMoney);
            num(tr, 10, t.d0To30(), totalMoney);
            num(tr, 11, t.d31To60(), totalMoney);
            num(tr, 12, t.d61To90(), totalMoney);
            num(tr, 13, t.over90(), totalMoney);
            num(tr, 14, t.overdueAmount(), totalMoney);
            num(tr, 16, t.creditBalance(), totalMoney);
            int[] widths = {6, 14, 32, 22, 16, 12, 16, 16, 16, 18, 16, 16, 16, 16, 16, 14, 18};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(3, 4);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED",
                    "Không tạo được tệp Excel, vui lòng thử lại", null);
        }
    }

    private static String describe(Filter filter) {
        Filter f = normalize(filter);
        StringBuilder sb = new StringBuilder();
        if (f.overdueOnly()) {
            sb.append(" · Chỉ đại lý có nợ quá hạn");
        }
        if (f.bucket() != null) {
            sb.append(" · Nhóm tuổi nợ: ").append(switch (f.bucket()) {
                case "D0_30" -> "0–30 ngày";
                case "D31_60" -> "31–60 ngày";
                case "D61_90" -> "61–90 ngày";
                default -> "trên 90 ngày";
            });
        }
        if (f.keyword() != null) {
            sb.append(" · Từ khoá: ").append(f.keyword());
        }
        return sb.toString();
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
        // VND không có số lẻ: ghi số nguyên đồng (đủ chính xác cho tới hàng triệu tỷ)
        cell.setCellValue((value != null ? value : BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP).longValueExact());
        cell.setCellStyle(style);
    }

    // ======================= TIỆN ÍCH =======================

    private static BigDecimal sum(List<DebtAgingRow> rows, Function<DebtAgingRow, BigDecimal> f) {
        BigDecimal s = ZERO;
        for (DebtAgingRow r : rows) {
            s = s.add(f.apply(r));
        }
        return s;
    }

    private static boolean contains(String s, String kwLower) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(kwLower);
    }

    private static List<Object[]> nz(List<Object[]> rows) {
        return rows != null ? rows : List.of();
    }

    private static Long id(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    static BigDecimal money(Object v) {
        BigDecimal b;
        if (v == null) {
            b = BigDecimal.ZERO;
        } else if (v instanceof BigDecimal d) {
            b = d;
        } else {
            b = new BigDecimal(v.toString());
        }
        return b.setScale(2, RoundingMode.HALF_UP);
    }
}
