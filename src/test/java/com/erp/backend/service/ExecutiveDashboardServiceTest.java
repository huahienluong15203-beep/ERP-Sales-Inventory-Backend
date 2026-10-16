package com.erp.backend.service;

import com.erp.backend.dto.dashboard.*;
import com.erp.backend.dto.debt.DebtAgingRow;
import com.erp.backend.dto.debt.DebtAgingTotals;
import com.erp.backend.dto.debt.DebtLedgerResponse;
import com.erp.backend.dto.inventory.alert.MinStockSummaryResponse;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S8-05: Kiểm thử Dashboard điều hành bán hàng và kho.
 * - Thẻ chỉ số: doanh số hôm nay/tháng này, đơn chờ duyệt/chờ xuất, công nợ quá hạn, SKU dưới định mức.
 * - Biểu đồ: doanh số theo ngày và theo khu vực.
 * - Danh sách đại lý quá hạn công nợ, xếp theo số tiền.
 * - Bộ đệm Cache đảm bảo tốc độ phản hồi < 2 giây.
 */
@ExtendWith(MockitoExtension.class)
class ExecutiveDashboardServiceTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private DebtLedgerService debtLedgerService;

    @Mock
    private MinStockAlertService minStockAlertService;

    @Spy
    private DashboardCacheService cacheService = new DashboardCacheService();

    @InjectMocks
    private ExecutiveDashboardService dashboardService;

    private UserDetailsImpl salesManagerActor;
    private final ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
    private final LocalDate testDate = LocalDate.of(2026, 10, 16);
    private final Clock fixedClock = Clock.fixed(testDate.atTime(10, 0).atZone(zone).toInstant(), zone);

    @BeforeEach
    void setUp() {
        dashboardService.clock = fixedClock;
        cacheService.clearAll();

        salesManagerActor = new UserDetailsImpl(
                1L, "sales_mgr", "Quản lý kinh doanh", "mgr@erp.com", "secret",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER"))
        );
    }

    @Test
    @DisplayName("S8-05: Thẻ chỉ số điều hành tổng hợp chuẩn xác các tiêu chí")
    void testSummary_ComputesCorrectMetrics() {
        // Mock dữ liệu doanh số tháng 10/2026: 2 đơn
        // Đơn 1: hôm nay (16/10/2026), 5,000,000 đ
        // Đơn 2: hôm trước (10/10/2026), 15,000,000 đ
        LocalDateTime todayTime = LocalDateTime.of(2026, 10, 16, 9, 30);
        LocalDateTime pastTime = LocalDateTime.of(2026, 10, 10, 14, 0);

        List<Object[]> salesRows = List.of(
                new Object[]{todayTime, new BigDecimal("5000000"), 101L, 1L, "MB", "Miền Bắc"},
                new Object[]{pastTime, new BigDecimal("15000000"), 102L, 2L, "MN", "Miền Nam"}
        );

        when(salesOrderRepository.findSalesDataForPeriod(eq(ExecutiveDashboardService.REVENUE_STATUSES), any(), any()))
                .thenReturn(salesRows);

        when(salesOrderRepository.countByStatus(ExecutiveDashboardService.STATUS_PENDING_APPROVAL))
                .thenReturn(4L);

        when(salesOrderRepository.countByStatusIn(ExecutiveDashboardService.PENDING_DISPATCH_STATUSES))
                .thenReturn(7L);

        // Mock nợ quá hạn
        DebtAgingTotals totals = new DebtAgingTotals(
                1,
                new BigDecimal("50000000"),
                new BigDecimal("20000000"),
                new BigDecimal("30000000"),
                new BigDecimal("10000000"),
                new BigDecimal("10000000"),
                new BigDecimal("5000000"),
                new BigDecimal("5000000"),
                BigDecimal.ZERO,
                new BigDecimal("12000000") // Quá hạn 12tr
        );
        DebtLedgerService.Computed debtComputed = new DebtLedgerService.Computed(
                testDate, List.of(), totals
        );
        when(debtLedgerService.compute(any(), any())).thenReturn(debtComputed);

        // Mock SKU dưới định mức
        MinStockSummaryResponse minStockSummary = MinStockSummaryResponse.builder()
                .totalAlerts(6)
                .criticalCount(2)
                .warningCount(4)
                .build();
        when(minStockAlertService.getSummary(isNull(), any())).thenReturn(minStockSummary);

        ExecutiveDashboardSummaryResponse summary = dashboardService.getSummary(false, salesManagerActor);

        assertThat(summary.getRevenueToday()).isEqualByComparingTo("5000000");
        assertThat(summary.getRevenueThisMonth()).isEqualByComparingTo("20000000"); // 5tr + 15tr
        assertThat(summary.getPendingApprovalOrders()).isEqualTo(4L);
        assertThat(summary.getPendingDispatchOrders()).isEqualTo(7L);
        assertThat(summary.getOverdueDebtAmount()).isEqualByComparingTo("12000000");
        assertThat(summary.getSkusBelowMinStock()).isEqualTo(6L);
        assertThat(summary.isFromCache()).isFalse();

        // Kiểm tra lần gọi thứ 2 lấy từ Cache
        ExecutiveDashboardSummaryResponse cachedSummary = dashboardService.getSummary(false, salesManagerActor);
        assertThat(cachedSummary.isFromCache()).isTrue();
        assertThat(cachedSummary.getRevenueThisMonth()).isEqualByComparingTo("20000000");
    }

    @Test
    @DisplayName("S8-05: Biểu đồ doanh số theo ngày trả về đầy đủ các ngày trong tháng")
    void testDailySalesInMonth_PopulatesAllDaysOfMonth() {
        LocalDateTime orderTimeDay5 = LocalDateTime.of(2026, 10, 5, 8, 0);
        LocalDateTime orderTimeDay16 = LocalDateTime.of(2026, 10, 16, 11, 0);

        List<Object[]> salesRows = List.of(
                new Object[]{orderTimeDay5, new BigDecimal("2000000"), 1L, 1L, "MB", "Miền Bắc"},
                new Object[]{orderTimeDay16, new BigDecimal("8000000"), 2L, 1L, "MB", "Miền Bắc"}
        );

        when(salesOrderRepository.findSalesDataForPeriod(eq(ExecutiveDashboardService.REVENUE_STATUSES), any(), any()))
                .thenReturn(salesRows);

        ExecutiveDailySalesResponse response = dashboardService.getDailySalesInMonth(10, 2026, false, salesManagerActor);

        assertThat(response.getMonth()).isEqualTo(10);
        assertThat(response.getYear()).isEqualTo(2026);
        assertThat(response.getTotalMonthSales()).isEqualByComparingTo("10000000");
        assertThat(response.getTotalMonthOrders()).isEqualTo(2L);
        assertThat(response.getPoints()).hasSize(31); // Tháng 10 có 31 ngày

        // Kiểm tra ngày 5
        ExecutiveDailySalesPoint day5 = response.getPoints().get(4);
        assertThat(day5.getDay()).isEqualTo(5);
        assertThat(day5.getAmount()).isEqualByComparingTo("2000000");
        assertThat(day5.getOrderCount()).isEqualTo(1L);

        // Kiểm tra ngày 16
        ExecutiveDailySalesPoint day16 = response.getPoints().get(15);
        assertThat(day16.getDay()).isEqualTo(16);
        assertThat(day16.getAmount()).isEqualByComparingTo("8000000");
        assertThat(day16.getOrderCount()).isEqualTo(1L);

        // Kiểm tra ngày không có đơn (ví dụ ngày 1)
        ExecutiveDailySalesPoint day1 = response.getPoints().get(0);
        assertThat(day1.getDay()).isEqualTo(1);
        assertThat(day1.getAmount()).isEqualByComparingTo("0");
        assertThat(day1.getOrderCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("S8-05: Biểu đồ doanh số theo khu vực gom nhóm và tính tỷ trọng % chính xác")
    void testSalesByRegion_AggregatesAndCalculatesPercentages() {
        List<Object[]> salesRows = List.of(
                new Object[]{LocalDateTime.of(2026, 10, 2, 10, 0), new BigDecimal("60000000"), 1L, 1L, "MB", "Miền Bắc"},
                new Object[]{LocalDateTime.of(2026, 10, 5, 11, 0), new BigDecimal("40000000"), 2L, 1L, "MB", "Miền Bắc"},
                new Object[]{LocalDateTime.of(2026, 10, 10, 9, 0), new BigDecimal("100000000"), 3L, 2L, "MN", "Miền Nam"}
        );

        when(salesOrderRepository.findSalesDataForPeriod(eq(ExecutiveDashboardService.REVENUE_STATUSES), any(), any()))
                .thenReturn(salesRows);

        ExecutiveRegionSalesResponse response = dashboardService.getSalesByRegion(10, 2026, false, salesManagerActor);

        // Tổng doanh số: 60 + 40 + 100 = 200tr
        assertThat(response.getTotalSales()).isEqualByComparingTo("200000000");
        assertThat(response.getRegions()).hasSize(2);

        // Xếp giảm dần: Miền Nam (100tr = 50%) trước, Miền Bắc (100tr = 50%) sau
        ExecutiveRegionSalesItem topRegion = response.getRegions().get(0);
        assertThat(topRegion.getAmount()).isEqualByComparingTo("100000000");
        assertThat(topRegion.getPercentage()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("S8-05: Danh sách đại lý quá hạn công nợ sắp xếp theo số tiền nợ giảm dần")
    void testOverdueAgencies_SortedByOverdueAmountDescending() {
        // Mock 2 đại lý có nợ quá hạn
        DebtAgingRow agencyA = new DebtAgingRow(
                10L, "DL-01", "Đại lý An Phát", 100L, "sales_rep_1",
                new BigDecimal("50000000"), 30,
                new BigDecimal("30000000"), new BigDecimal("10000000"),
                new BigDecimal("20000000"), BigDecimal.ZERO,
                new BigDecimal("5000000"), new BigDecimal("5000000"),
                new BigDecimal("5000000"), new BigDecimal("5000000"),
                new BigDecimal("15000000"), 45L
        );

        DebtAgingRow agencyB = new DebtAgingRow(
                20L, "DL-02", "Đại lý Bình Minh", 100L, "sales_rep_1",
                new BigDecimal("100000000"), 30,
                new BigDecimal("80000000"), new BigDecimal("20000000"),
                new BigDecimal("60000000"), BigDecimal.ZERO,
                new BigDecimal("10000000"), new BigDecimal("10000000"),
                new BigDecimal("10000000"), new BigDecimal("30000000"),
                new BigDecimal("40000000"), 65L
        );

        DebtAgingTotals totals = new DebtAgingTotals(
                2,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("55000000")
        );

        DebtLedgerService.Computed computed = new DebtLedgerService.Computed(
                testDate, List.of(agencyA, agencyB), totals
        );

        when(debtLedgerService.compute(any(), any())).thenReturn(computed);

        ExecutiveOverdueAgenciesResponse response = dashboardService.getOverdueAgencies(10, false, salesManagerActor);

        assertThat(response.getTotalOverdueAmount()).isEqualByComparingTo("55000000");
        assertThat(response.getTotalOverdueAgencies()).isEqualTo(2);
        assertThat(response.getAgencies()).hasSize(2);

        // Rank 1: agencyB (40tr)
        ExecutiveOverdueAgencyItem rank1 = response.getAgencies().get(0);
        assertThat(rank1.getRank()).isEqualTo(1);
        assertThat(rank1.getCustomerCode()).isEqualTo("DL-02");
        assertThat(rank1.getOverdueAmount()).isEqualByComparingTo("40000000");

        // Rank 2: agencyA (15tr)
        ExecutiveOverdueAgencyItem rank2 = response.getAgencies().get(1);
        assertThat(rank2.getRank()).isEqualTo(2);
        assertThat(rank2.getCustomerCode()).isEqualTo("DL-01");
        assertThat(rank2.getOverdueAmount()).isEqualByComparingTo("15000000");
    }

    @Test
    @DisplayName("S8-05: Toàn bộ Dashboard điều hành tải trong 1 API call và sử dụng bộ đệm phản hồi < 2 giây")
    void testCompleteDashboard_LoadsUnderTwoSecondsAndUsesCache() {
        when(salesOrderRepository.findSalesDataForPeriod(any(), any(), any())).thenReturn(List.of());
        when(salesOrderRepository.countByStatus(any())).thenReturn(2L);
        when(salesOrderRepository.countByStatusIn(any())).thenReturn(3L);

        DebtAgingTotals totals = new DebtAgingTotals(
                0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
        when(debtLedgerService.compute(any(), any())).thenReturn(
                new DebtLedgerService.Computed(testDate, List.of(), totals)
        );
        when(minStockAlertService.getSummary(any(), any())).thenReturn(
                MinStockSummaryResponse.builder().totalAlerts(1).build()
        );

        long start = System.currentTimeMillis();
        ExecutiveDashboardOverviewResponse overview = dashboardService.getCompleteDashboard(
                10, 2026, 10, false, salesManagerActor
        );
        long duration = System.currentTimeMillis() - start;

        // Tiêu chí chấp nhận: Toàn bộ dữ liệu tải xong dưới 2 giây (< 2000ms)
        assertThat(duration).isLessThan(2000L);
        assertThat(overview.isFromCache()).isFalse();
        assertThat(overview.getSummary()).isNotNull();
        assertThat(overview.getDailySales()).isNotNull();
        assertThat(overview.getRegionSales()).isNotNull();
        assertThat(overview.getOverdueAgencies()).isNotNull();

        // Lần 2 từ Cache
        ExecutiveDashboardOverviewResponse cachedOverview = dashboardService.getCompleteDashboard(
                10, 2026, 10, false, salesManagerActor
        );
        assertThat(cachedOverview.isFromCache()).isTrue();

        // Gọi refresh = true bỏ qua cache
        ExecutiveDashboardOverviewResponse refreshedOverview = dashboardService.getCompleteDashboard(
                10, 2026, 10, true, salesManagerActor
        );
        assertThat(refreshedOverview.isFromCache()).isFalse();
    }
}
