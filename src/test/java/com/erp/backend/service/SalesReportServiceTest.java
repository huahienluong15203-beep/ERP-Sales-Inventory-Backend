package com.erp.backend.service;

import com.erp.backend.dto.report.SalesReportMarginResponse;
import com.erp.backend.dto.report.SalesReportMarginRow;
import com.erp.backend.dto.report.SalesReportResponse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SalesReportRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S8-06: Báo cáo doanh số đa chiều, so sánh kỳ trước, biên lợi nhuận chỉ QL kinh doanh, xuất Excel. */
@ExtendWith(MockitoExtension.class)
class SalesReportServiceTest {

    @Mock private SalesReportRepository reportRepository;

    @InjectMocks private SalesReportService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    private final UserDetailsImpl manager = actor(3, "ROLE_SALES_MANAGER");
    private final UserDetailsImpl admin = actor(1, "ROLE_ADMIN");

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atTime(9, 0).atZone(VN).toInstant(), VN);
    }

    private static Object[] r(Long id, String code, String name, String revenue, String qty, String cost, long orders) {
        return new Object[]{id, code, name, new BigDecimal(revenue), new BigDecimal(qty), new BigDecimal(cost), orders};
    }

    private static List<Object[]> rows(Object[]... items) {
        return new ArrayList<>(List.of(items));
    }

    private void stubRegion() {
        // Kỳ hiện tại 01/10 - 15/10 (15 ngày) -> kỳ trước liền kề 16/09 - 30/09
        when(reportRepository.byRegion(anyList(), eq(FROM.atStartOfDay()), eq(TODAY.plusDays(1).atStartOfDay())))
                .thenReturn(rows(r(1L, "MB", "Miền Bắc", "1000000", "240", "600000", 3),
                        r(null, null, null, "200000", "10", "50000", 1)));
        when(reportRepository.byRegion(anyList(), eq(LocalDate.of(2026, 9, 16).atStartOfDay()),
                eq(LocalDate.of(2026, 10, 1).atStartOfDay())))
                .thenReturn(rows(r(1L, "MB", "Miền Bắc", "800000", "200", "480000", 2),
                        r(2L, "MN", "Miền Nam", "100000", "5", "60000", 1)));
    }

    @Test
    @DisplayName("S8-06: QL kinh doanh xem theo khu vực: doanh số, sản lượng, biên LN gộp, % so với kỳ trước liền kề")
    void manager_byRegion_withMarginAndCompare() {
        stubRegion();

        Object res = service.report("region", null, null, null, manager);

        assertThat(res).isInstanceOf(SalesReportMarginResponse.class);
        SalesReportMarginResponse m = (SalesReportMarginResponse) res;
        assertThat(m.groupBy()).isEqualTo("REGION");
        assertThat(m.fromDate()).isEqualTo(FROM);
        assertThat(m.toDate()).isEqualTo(TODAY);
        assertThat(m.compareFromDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(m.compareToDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(m.marginVisible()).isTrue();
        // Sắp theo doanh số giảm dần: Miền Bắc, Chưa gán khu vực, Miền Nam (chỉ có ở kỳ trước)
        assertThat(m.rows()).extracting(SalesReportMarginRow::name)
                .containsExactly("Miền Bắc", "Chưa gán khu vực", "Miền Nam");
        SalesReportMarginRow mb = m.rows().get(0);
        assertThat(mb.revenue()).isEqualByComparingTo("1000000");
        assertThat(mb.quantity()).isEqualByComparingTo("240");
        assertThat(mb.orderCount()).isEqualTo(3);
        assertThat(mb.costOfGoods()).isEqualByComparingTo("600000");
        assertThat(mb.grossMargin()).isEqualByComparingTo("400000");
        assertThat(mb.grossMarginPercent()).isEqualByComparingTo("40.0");
        assertThat(mb.previousRevenue()).isEqualByComparingTo("800000");
        assertThat(mb.revenueChangePercent()).isEqualByComparingTo("25.0");
        assertThat(mb.previousGrossMargin()).isEqualByComparingTo("320000");
        SalesReportMarginRow mn = m.rows().get(2);
        assertThat(mn.revenue()).isEqualByComparingTo("0");
        assertThat(mn.revenueChangePercent()).isEqualByComparingTo("-100.0");
        assertThat(mn.grossMarginPercent()).isNull();
        SalesReportMarginRow none = m.rows().get(1);
        assertThat(none.key()).isEqualTo("REGION:NONE");
        assertThat(none.previousRevenue()).isEqualByComparingTo("0");
        assertThat(none.revenueChangePercent()).isNull();
        assertThat(m.total().revenue()).isEqualByComparingTo("1200000");
        assertThat(m.total().grossMargin()).isEqualByComparingTo("550000");
        assertThat(m.total().previousRevenue()).isEqualByComparingTo("900000");
        assertThat(m.total().revenueChangePercent()).isEqualByComparingTo("33.3");
    }

    @Test
    @DisplayName("S8-06: Admin xem báo cáo nhưng KHÔNG có giá vốn / biên LN (DTO riêng)")
    void admin_noMargin() {
        stubRegion();

        Object res = service.report("REGION", null, null, null, admin);

        assertThat(res).isInstanceOf(SalesReportResponse.class);
        SalesReportResponse r = (SalesReportResponse) res;
        assertThat(r.marginVisible()).isFalse();
        assertThat(r.rows()).hasSize(3);
        assertThat(r.rows().get(0).revenue()).isEqualByComparingTo("1000000");
        assertThat(r.total().revenueChangePercent()).isEqualByComparingTo("33.3");
    }

    @Test
    @DisplayName("S8-06: So sánh cùng kỳ năm trước và không so sánh")
    void compareModes() {
        when(reportRepository.bySku(anyList(), any(), any())).thenReturn(rows());

        SalesReportMarginResponse ly = (SalesReportMarginResponse) service.report("SKU", FROM, TODAY, "last_year", manager);
        assertThat(ly.compareFromDate()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(ly.compareToDate()).isEqualTo(LocalDate.of(2025, 10, 15));
        verify(reportRepository).bySku(anyList(), eq(LocalDate.of(2025, 10, 1).atStartOfDay()),
                eq(LocalDate.of(2025, 10, 16).atStartOfDay()));

        clearInvocations(reportRepository);
        SalesReportMarginResponse none = (SalesReportMarginResponse) service.report("SKU", FROM, TODAY, "NONE", manager);
        assertThat(none.compareFromDate()).isNull();
        assertThat(none.total().previousRevenue()).isNull();
        verify(reportRepository, times(1)).bySku(anyList(), any(), any());
    }

    @Test
    @DisplayName("S8-06: Mỗi chiều gọi đúng truy vấn nhóm (NV, đại lý, nhóm hàng, SKU)")
    void groupByDimensions() {
        service.report("REP", FROM, TODAY, "NONE", manager);
        service.report("CUSTOMER", FROM, TODAY, "NONE", manager);
        service.report("CATEGORY", FROM, TODAY, "NONE", manager);
        service.report("SKU", FROM, TODAY, "NONE", manager);
        verify(reportRepository).byRep(anyList(), any(), any());
        verify(reportRepository).byCustomer(anyList(), any(), any());
        verify(reportRepository).byCategory(anyList(), any(), any());
        verify(reportRepository).bySku(anyList(), any(), any());
        verify(reportRepository, never()).byRegion(anyList(), any(), any());
    }

    @Test
    @DisplayName("S8-06: Nhóm / so sánh / khoảng ngày sai -> 400; NV kinh doanh, kế toán -> 403")
    void invalidInputAndRoles() {
        assertThatThrownBy(() -> service.report("PRODUCT_LINE", null, null, null, manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_GROUP_BY");
        assertThatThrownBy(() -> service.report("REP", null, null, "YESTERDAY", manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_COMPARE");
        assertThatThrownBy(() -> service.report("REP", TODAY, FROM, null, manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_DATE_RANGE");
        assertThatThrownBy(() -> service.report("REP", LocalDate.of(2025, 1, 1), TODAY, null, manager))
                .hasFieldOrPropertyWithValue("code", "DATE_RANGE_TOO_LONG");
        assertThatThrownBy(() -> service.report("REP", null, null, null, actor(7, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "REPORT_NOT_ALLOWED");
        assertThatThrownBy(() -> service.export("REP", null, null, null, actor(40, "ROLE_ACCOUNTANT")))
                .hasFieldOrPropertyWithValue("code", "REPORT_NOT_ALLOWED");
        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("S8-06: Công thức % tăng giảm và % biên lợi nhuận")
    void formulas() {
        assertThat(SalesReportService.changePercent(new BigDecimal("150"), new BigDecimal("100"))).isEqualByComparingTo("50.0");
        assertThat(SalesReportService.changePercent(new BigDecimal("50"), new BigDecimal("100"))).isEqualByComparingTo("-50.0");
        assertThat(SalesReportService.changePercent(new BigDecimal("50"), BigDecimal.ZERO)).isNull();
        assertThat(SalesReportService.percentOf(new BigDecimal("1"), new BigDecimal("3"))).isEqualByComparingTo("33.3");
        assertThat(SalesReportService.percentOf(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
    }

    @Test
    @DisplayName("S8-06: Excel của QL kinh doanh có cột biên LN; của Admin thì không. Tên tệp có nhóm và kỳ")
    void export_marginColumnsByRole() throws IOException {
        stubRegion();

        SalesReportService.ExportFile mgr = service.export("REGION", null, null, null, manager);
        SalesReportService.ExportFile adm = service.export("REGION", null, null, null, admin);

        assertThat(mgr.fileName()).isEqualTo("BaoCaoDoanhSo_REGION_20261001_20261015.xlsx");
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(mgr.content()))) {
            Row h = wb.getSheetAt(0).getRow(3);
            assertThat(h.getCell(10).getStringCellValue()).isEqualTo("Biên LN gộp (%)");
            Row first = wb.getSheetAt(0).getRow(4);
            assertThat(first.getCell(2).getStringCellValue()).isEqualTo("Miền Bắc");
            assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(1000000.0);
            assertThat(first.getCell(9).getNumericCellValue()).isEqualTo(400000.0);
        }
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(adm.content()))) {
            Row h = wb.getSheetAt(0).getRow(3);
            assertThat(h.getLastCellNum()).isEqualTo((short) 8);
            for (int i = 0; i < h.getLastCellNum(); i++) {
                assertThat(h.getCell(i).getStringCellValue()).doesNotContain("Giá vốn").doesNotContain("Lãi gộp");
            }
        }
    }
}
