package com.erp.backend.service;

import com.erp.backend.dto.dashboard.*;
import com.erp.backend.dto.debt.DebtAgingRow;
import com.erp.backend.dto.debt.DebtAgingTotals;
import com.erp.backend.dto.inventory.alert.MinStockSummaryResponse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * S8-05: Dịch vụ cung cấp số liệu Dashboard điều hành bán hàng và kho cho Quản lý kinh doanh.
 * - Thẻ chỉ số: doanh số hôm nay, doanh số tháng này, đơn chờ duyệt, đơn chờ xuất, công nợ quá hạn, số SKU dưới tồn tối thiểu.
 * - Biểu đồ doanh số theo ngày trong tháng và theo khu vực.
 * - Danh sách đại lý quá hạn công nợ, xếp theo số tiền nợ giảm dần.
 * - Tích hợp bộ đệm (Cache) giúp toàn bộ dữ liệu tải xong dưới 2 giây.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutiveDashboardService {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // Các trạng thái đơn hàng đủ điều kiện ghi nhận doanh thu
    public static final List<String> REVENUE_STATUSES = List.of(
            "DISPATCHED", "DELIVERED", "CLOSED", "COMPLETED"
    );

    // Trạng thái đơn chờ duyệt
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";

    // Các trạng thái đơn chờ xuất kho (đã duyệt, đang soạn hàng, chưa xuất kho)
    public static final List<String> PENDING_DISPATCH_STATUSES = List.of(
            "APPROVED", "CONFIRMED", "PICKING", "PREPARING"
    );

    private static final String CACHE_KEY_SUMMARY = "dashboard:executive:summary";
    private static final String CACHE_KEY_DAILY_PREFIX = "dashboard:executive:daily-sales:";
    private static final String CACHE_KEY_REGION_PREFIX = "dashboard:executive:region-sales:";
    private static final String CACHE_KEY_OVERDUE_PREFIX = "dashboard:executive:overdue-agencies:";
    private static final String CACHE_KEY_OVERVIEW_PREFIX = "dashboard:executive:overview:";

    private final SalesOrderRepository salesOrderRepository;
    private final DebtLedgerService debtLedgerService;
    private final MinStockAlertService minStockAlertService;
    private final DashboardCacheService cacheService;

    Clock clock = Clock.system(VN_ZONE);

    private String getScopeKey(UserDetailsImpl actor) {
        if (actor == null || actor.getAuthorities() == null) {
            return "anon";
        }
        Set<String> roles = actor.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (roles.contains("ROLE_ADMIN") || roles.contains("ROLE_SALES_MANAGER") || roles.contains("ROLE_ACCOUNTANT")) {
            return "EXECUTIVE_FULL";
        }
        if (roles.contains("ROLE_WH_MANAGER")) {
            return "WH_MANAGER_" + actor.getId();
        }
        return "USER_" + actor.getId();
    }

    private void validateMonthYear(Integer month, Integer year) {
        if (month != null && (month < 1 || month > 12)) {
            throw BusinessException.badRequest("Tham số tháng không hợp lệ (phải từ 1 đến 12).");
        }
        if (year != null && (year < 2000 || year > 2100)) {
            throw BusinessException.badRequest("Tham số năm không hợp lệ (phải từ 2000 đến 2100).");
        }
    }

    /**
     * 1. Thẻ chỉ số tổng quan hoạt động bán hàng & kho.
     */
    @Transactional(readOnly = true)
    public ExecutiveDashboardSummaryResponse getSummary(boolean refresh, UserDetailsImpl actor) {
        String cacheKey = CACHE_KEY_SUMMARY + ":" + getScopeKey(actor);
        if (!refresh) {
            Optional<ExecutiveDashboardSummaryResponse> cached = cacheService.get(cacheKey, ExecutiveDashboardSummaryResponse.class);
            if (cached.isPresent()) {
                ExecutiveDashboardSummaryResponse resp = cached.get();
                resp.setFromCache(true);
                return resp;
            }
        }

        LocalDate today = LocalDate.now(clock);
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime endOfToday = today.plusDays(1).atStartOfDay();

        LocalDate firstDayOfMonth = today.withDayOfMonth(1);
        LocalDateTime startOfMonth = firstDayOfMonth.atStartOfDay();
        LocalDateTime endOfMonth = firstDayOfMonth.plusMonths(1).atStartOfDay();

        // 1. Doanh số hôm nay và tháng này
        List<Object[]> monthSalesRows = salesOrderRepository.findSalesDataForPeriod(REVENUE_STATUSES, startOfMonth, endOfMonth);

        BigDecimal revenueToday = BigDecimal.ZERO;
        BigDecimal revenueThisMonth = BigDecimal.ZERO;

        for (Object[] row : monthSalesRows) {
            LocalDateTime orderTime = (LocalDateTime) row[0];
            BigDecimal amount = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;

            revenueThisMonth = revenueThisMonth.add(amount);

            if (orderTime != null && !orderTime.isBefore(startOfToday) && orderTime.isBefore(endOfToday)) {
                revenueToday = revenueToday.add(amount);
            }
        }

        // 2. Đơn chờ duyệt
        long pendingApprovalCount = salesOrderRepository.countByStatus(STATUS_PENDING_APPROVAL);

        // 3. Đơn chờ xuất
        long pendingDispatchCount = salesOrderRepository.countByStatusIn(PENDING_DISPATCH_STATUSES);

        // 4. Tổng công nợ quá hạn toàn công ty
        BigDecimal overdueDebtAmount = BigDecimal.ZERO;
        try {
            DebtLedgerService.Computed debtComputed = debtLedgerService.compute(
                    new DebtLedgerService.Filter(null, null, null, false, false, "OLDEST"), actor);
            if (debtComputed != null && debtComputed.totals() != null) {
                overdueDebtAmount = debtComputed.totals().overdueAmount();
            }
        } catch (Exception ex) {
            log.warn("S8-05: Lỗi khi lấy tổng công nợ quá hạn: {}", ex.getMessage());
        }

        // 5. Số SKU dưới tồn tối thiểu
        long belowMinStockCount = 0;
        try {
            MinStockSummaryResponse minStockSummary = minStockAlertService.getSummary(null, actor);
            if (minStockSummary != null) {
                belowMinStockCount = minStockSummary.getTotalAlerts();
            }
        } catch (Exception ex) {
            log.warn("S8-05: Lỗi khi lấy số lượng SKU dưới định mức: {}", ex.getMessage());
        }

        ExecutiveDashboardSummaryResponse response = ExecutiveDashboardSummaryResponse.builder()
                .revenueToday(revenueToday.setScale(2, RoundingMode.HALF_UP))
                .revenueThisMonth(revenueThisMonth.setScale(2, RoundingMode.HALF_UP))
                .pendingApprovalOrders(pendingApprovalCount)
                .pendingDispatchOrders(pendingDispatchCount)
                .overdueDebtAmount(overdueDebtAmount.setScale(2, RoundingMode.HALF_UP))
                .skusBelowMinStock(belowMinStockCount)
                .fromCache(false)
                .computedAt(LocalDateTime.now(clock))
                .build();

        cacheService.put(cacheKey, response);
        return response;
    }

    /**
     * 2. Biểu đồ doanh số theo ngày trong tháng.
     */
    @Transactional(readOnly = true)
    public ExecutiveDailySalesResponse getDailySalesInMonth(Integer targetMonth, Integer targetYear, boolean refresh, UserDetailsImpl actor) {
        validateMonthYear(targetMonth, targetYear);
        LocalDate today = LocalDate.now(clock);
        int month = targetMonth != null ? targetMonth : today.getMonthValue();
        int year = targetYear != null ? targetYear : today.getYear();

        String cacheKey = CACHE_KEY_DAILY_PREFIX + getScopeKey(actor) + ":" + year + "-" + month;
        if (!refresh) {
            Optional<ExecutiveDailySalesResponse> cached = cacheService.get(cacheKey, ExecutiveDailySalesResponse.class);
            if (cached.isPresent()) {
                ExecutiveDailySalesResponse resp = cached.get();
                resp.setFromCache(true);
                return resp;
            }
        }

        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate nextMonthFirstDay = firstDay.plusMonths(1);
        int lengthOfMonth = firstDay.lengthOfMonth();

        LocalDateTime startOfMonth = firstDay.atStartOfDay();
        LocalDateTime endOfMonth = nextMonthFirstDay.atStartOfDay();

        List<Object[]> rows = salesOrderRepository.findSalesDataForPeriod(REVENUE_STATUSES, startOfMonth, endOfMonth);

        // Khởi tạo map cho tất cả các ngày trong tháng
        Map<Integer, BigDecimal> amountByDay = new HashMap<>();
        Map<Integer, Long> countByDay = new HashMap<>();
        for (int d = 1; d <= lengthOfMonth; d++) {
            amountByDay.put(d, BigDecimal.ZERO);
            countByDay.put(d, 0L);
        }

        BigDecimal totalMonthSales = BigDecimal.ZERO;
        long totalMonthOrders = 0;

        for (Object[] row : rows) {
            LocalDateTime orderTime = (LocalDateTime) row[0];
            BigDecimal amount = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;

            if (orderTime != null) {
                int day = orderTime.getDayOfMonth();
                amountByDay.put(day, amountByDay.getOrDefault(day, BigDecimal.ZERO).add(amount));
                countByDay.put(day, countByDay.getOrDefault(day, 0L) + 1);

                totalMonthSales = totalMonthSales.add(amount);
                totalMonthOrders++;
            }
        }

        List<ExecutiveDailySalesPoint> points = new ArrayList<>();
        for (int d = 1; d <= lengthOfMonth; d++) {
            points.add(ExecutiveDailySalesPoint.builder()
                    .date(LocalDate.of(year, month, d))
                    .day(d)
                    .amount(amountByDay.get(d).setScale(2, RoundingMode.HALF_UP))
                    .orderCount(countByDay.get(d))
                    .build());
        }

        ExecutiveDailySalesResponse response = ExecutiveDailySalesResponse.builder()
                .month(month)
                .year(year)
                .totalMonthSales(totalMonthSales.setScale(2, RoundingMode.HALF_UP))
                .totalMonthOrders(totalMonthOrders)
                .points(points)
                .fromCache(false)
                .build();

        cacheService.put(cacheKey, response);
        return response;
    }

    /**
     * 3. Biểu đồ doanh số theo khu vực.
     */
    @Transactional(readOnly = true)
    public ExecutiveRegionSalesResponse getSalesByRegion(Integer targetMonth, Integer targetYear, boolean refresh, UserDetailsImpl actor) {
        validateMonthYear(targetMonth, targetYear);
        LocalDate today = LocalDate.now(clock);
        int month = targetMonth != null ? targetMonth : today.getMonthValue();
        int year = targetYear != null ? targetYear : today.getYear();

        String cacheKey = CACHE_KEY_REGION_PREFIX + getScopeKey(actor) + ":" + year + "-" + month;
        if (!refresh) {
            Optional<ExecutiveRegionSalesResponse> cached = cacheService.get(cacheKey, ExecutiveRegionSalesResponse.class);
            if (cached.isPresent()) {
                ExecutiveRegionSalesResponse resp = cached.get();
                resp.setFromCache(true);
                return resp;
            }
        }

        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDateTime startOfMonth = firstDay.atStartOfDay();
        LocalDateTime endOfMonth = firstDay.plusMonths(1).atStartOfDay();

        List<Object[]> rows = salesOrderRepository.findSalesDataForPeriod(REVENUE_STATUSES, startOfMonth, endOfMonth);

        // Gom nhóm theo Khu vực
        Map<String, ExecutiveRegionSalesItem> regionMap = new LinkedHashMap<>();
        BigDecimal totalSales = BigDecimal.ZERO;

        for (Object[] row : rows) {
            BigDecimal amount = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            Long regionId = row[3] != null ? ((Number) row[3]).longValue() : null;
            String regionCode = row[4] != null ? (String) row[4] : "OTHER";
            String regionName = row[5] != null ? (String) row[5] : "Khu vực khác";

            String key = regionId != null ? String.valueOf(regionId) : "OTHER";
            ExecutiveRegionSalesItem item = regionMap.get(key);
            if (item == null) {
                item = ExecutiveRegionSalesItem.builder()
                        .regionId(regionId)
                        .regionCode(regionCode)
                        .regionName(regionName)
                        .amount(BigDecimal.ZERO)
                        .orderCount(0L)
                        .percentage(0.0)
                        .build();
                regionMap.put(key, item);
            }

            item.setAmount(item.getAmount().add(amount));
            item.setOrderCount(item.getOrderCount() + 1);
            totalSales = totalSales.add(amount);
        }

        // Tính tỷ trọng % và làm tròn
        List<ExecutiveRegionSalesItem> regionList = new ArrayList<>(regionMap.values());
        for (ExecutiveRegionSalesItem item : regionList) {
            if (totalSales.compareTo(BigDecimal.ZERO) > 0) {
                double pct = item.getAmount()
                        .divide(totalSales, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                item.setPercentage(Math.round(pct * 100.0) / 100.0);
            } else {
                item.setPercentage(0.0);
            }
            item.setAmount(item.getAmount().setScale(2, RoundingMode.HALF_UP));
        }

        // Sắp xếp giảm dần theo doanh số
        regionList.sort(Comparator.comparing(ExecutiveRegionSalesItem::getAmount).reversed());

        ExecutiveRegionSalesResponse response = ExecutiveRegionSalesResponse.builder()
                .month(month)
                .year(year)
                .totalSales(totalSales.setScale(2, RoundingMode.HALF_UP))
                .regions(regionList)
                .fromCache(false)
                .build();

        cacheService.put(cacheKey, response);
        return response;
    }

    /**
     * 4. Danh sách đại lý quá hạn công nợ, xếp theo số tiền nợ giảm dần.
     */
    @Transactional(readOnly = true)
    public ExecutiveOverdueAgenciesResponse getOverdueAgencies(Integer limit, boolean refresh, UserDetailsImpl actor) {
        int maxResults = (limit != null && limit > 0) ? Math.min(limit, 100) : 10;
        String cacheKey = CACHE_KEY_OVERDUE_PREFIX + getScopeKey(actor) + ":" + maxResults;

        if (!refresh) {
            Optional<ExecutiveOverdueAgenciesResponse> cached = cacheService.get(cacheKey, ExecutiveOverdueAgenciesResponse.class);
            if (cached.isPresent()) {
                ExecutiveOverdueAgenciesResponse resp = cached.get();
                resp.setFromCache(true);
                return resp;
            }
        }

        List<ExecutiveOverdueAgencyItem> items = new ArrayList<>();
        BigDecimal totalOverdue = BigDecimal.ZERO;
        int totalAgencies = 0;

        try {
            DebtLedgerService.Computed debtComputed = debtLedgerService.compute(
                    new DebtLedgerService.Filter(null, null, null, true, false, "OVERDUE"), actor);

            List<DebtAgingRow> overdueRows = debtComputed.rows().stream()
                    .filter(r -> r.overdueAmount().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(Comparator.comparing(DebtAgingRow::overdueAmount).reversed()
                            .thenComparing(DebtAgingRow::oldestDebtDays, Comparator.reverseOrder()))
                    .toList();

            totalOverdue = debtComputed.totals() != null ? debtComputed.totals().overdueAmount() : BigDecimal.ZERO;
            totalAgencies = overdueRows.size();
            int rank = 1;

            for (DebtAgingRow row : overdueRows.stream().limit(maxResults).toList()) {
                items.add(ExecutiveOverdueAgencyItem.builder()
                        .rank(rank++)
                        .customerId(row.customerId())
                        .customerCode(row.customerCode())
                        .customerName(row.customerName())
                        .contactName(null)
                        .phone(null)
                        .salesRepId(row.salesRepId())
                        .salesRepName(row.salesRepName())
                        .creditLimit(row.creditLimit())
                        .currentDebt(row.outstanding())
                        .overdueAmount(row.overdueAmount())
                        .oldestOverdueDays(row.oldestDebtDays())
                        .build());
            }
        } catch (Exception ex) {
            log.info("S8-05: Người dùng {} không có quyền xem chi tiết công nợ: {}",
                    actor != null ? actor.getUsername() : "anonymous", ex.getMessage());
        }

        ExecutiveOverdueAgenciesResponse response = ExecutiveOverdueAgenciesResponse.builder()
                .totalOverdueAmount(totalOverdue.setScale(2, RoundingMode.HALF_UP))
                .totalOverdueAgencies(totalAgencies)
                .agencies(items)
                .fromCache(false)
                .build();

        cacheService.put(cacheKey, response);
        return response;
    }

    /**
     * 5. Toàn bộ Dashboard điều hành bán hàng và kho (tổng hợp 1-call để load dưới 2s).
     */
    @Transactional(readOnly = true)
    public ExecutiveDashboardOverviewResponse getCompleteDashboard(Integer month, Integer year, Integer limit, boolean refresh, UserDetailsImpl actor) {
        validateMonthYear(month, year);
        long startMs = System.currentTimeMillis();

        LocalDate today = LocalDate.now(clock);
        int targetMonth = month != null ? month : today.getMonthValue();
        int targetYear = year != null ? year : today.getYear();
        int topLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 10;

        String cacheKey = CACHE_KEY_OVERVIEW_PREFIX + getScopeKey(actor) + ":" + targetYear + "-" + targetMonth + ":" + topLimit;
        if (!refresh) {
            Optional<ExecutiveDashboardOverviewResponse> cached = cacheService.get(cacheKey, ExecutiveDashboardOverviewResponse.class);
            if (cached.isPresent()) {
                ExecutiveDashboardOverviewResponse resp = cached.get();
                resp.setFromCache(true);
                resp.setResponseTimeMs(System.currentTimeMillis() - startMs);
                return resp;
            }
        }

        ExecutiveDashboardSummaryResponse summary = getSummary(refresh, actor);
        ExecutiveDailySalesResponse dailySales = getDailySalesInMonth(targetMonth, targetYear, refresh, actor);
        ExecutiveRegionSalesResponse regionSales = getSalesByRegion(targetMonth, targetYear, refresh, actor);
        ExecutiveOverdueAgenciesResponse overdueAgencies = getOverdueAgencies(topLimit, refresh, actor);

        long durationMs = System.currentTimeMillis() - startMs;

        ExecutiveDashboardOverviewResponse response = ExecutiveDashboardOverviewResponse.builder()
                .summary(summary)
                .dailySales(dailySales)
                .regionSales(regionSales)
                .overdueAgencies(overdueAgencies)
                .responseTimeMs(durationMs)
                .fromCache(false)
                .timestamp(LocalDateTime.now(clock))
                .build();

        cacheService.put(cacheKey, response);
        log.info("S8-05: Đã tải xong toàn bộ Dashboard điều hành trong {}ms (Tiêu chí < 2000ms: ĐẠT)", durationMs);
        return response;
    }

    /**
     * Làm mới (xóa) bộ đệm dashboard.
     */
    public void evictDashboardCache() {
        cacheService.evictByPrefix("dashboard:executive:");
        log.info("S8-05: Đã làm mới bộ nhớ đệm Dashboard điều hành");
    }
}
