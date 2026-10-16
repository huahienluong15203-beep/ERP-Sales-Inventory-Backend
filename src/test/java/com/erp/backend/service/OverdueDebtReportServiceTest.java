package com.erp.backend.service;

import com.erp.backend.dto.debt.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** S8-09: Báo cáo công nợ quá hạn. */
@ExtendWith(MockitoExtension.class)
class OverdueDebtReportServiceTest {

    @Mock private DebtLedgerService debtLedgerService;

    @InjectMocks private OverdueDebtReportService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private final UserDetailsImpl accountant = actor(3, "ROLE_ACCOUNTANT");

    private static BigDecimal m(String v) {
        return new BigDecimal(v).setScale(2);
    }

    private static DebtAgingRow row(long id, Long repId, String repName, String limit, String outstanding,
                                    String d0, String d31, String d61, String over90, String overdue, long oldest) {
        return new DebtAgingRow(id, "DL-" + id, "Đại lý " + id, repId, repName, m(limit), 30, m(outstanding),
                m("0"), m(outstanding), m("0"), m(d0), m(d31), m(d61), m(over90), m(overdue), oldest);
    }

    /** A: quá hạn 2tr (trung bình) · B: vượt hạn mức (cao) · C: nợ trên 90 ngày (cao) · D: chưa phân công, quá hạn 4tr · E: không quá hạn. */
    private static List<DebtAgingRow> rows() {
        return List.of(
                row(1, 7L, "NV 7", "10000000", "3000000", "1000000", "2000000", "0", "0", "2000000", 50),
                row(2, 7L, "NV 7", "5000000", "6000000", "5000000", "0", "1000000", "0", "1000000", 70),
                row(3, 9L, "NV 9", "0", "1500000", "0", "0", "0", "1500000", "1500000", 120),
                row(4, null, null, "0", "4000000", "0", "4000000", "0", "0", "4000000", 45),
                row(5, 9L, "NV 9", "10000000", "800000", "800000", "0", "0", "0", "0", 2));
    }

    private void data() {
        when(debtLedgerService.compute(any(), any())).thenReturn(new DebtLedgerService.Computed(TODAY, rows(),
                DebtLedgerService.totals(rows())));
    }

    @Test
    @DisplayName("S8-09: Đại lý rủi ro cao nhất lên đầu: nợ trên 90 ngày > vượt hạn mức > nợ quá hạn nhiều")
    void ranking_highestRiskFirst() {
        data();

        OverdueReportResponse r = service.report(null, null, accountant);

        assertThat(r.customers()).extracting(OverdueRiskRow::customerId).containsExactly(3L, 2L, 4L, 1L);
        assertThat(r.customers()).extracting(OverdueRiskRow::rank).containsExactly(1, 2, 3, 4);
        assertThat(r.customers()).extracting(OverdueRiskRow::riskLevel)
                .containsExactly("HIGH", "HIGH", "MEDIUM", "MEDIUM");
        assertThat(r.customers().get(0).reasons().get(0)).contains("Nợ trên 90 ngày").contains("1.500.000 ₫");
        assertThat(r.customers().get(1).reasons().get(0)).contains("vượt hạn mức").contains("5.000.000 ₫");
        assertThat(r.overdueCustomerCount()).isEqualTo(4);
        assertThat(r.highRiskCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("S8-09: Đại lý không còn nợ quá hạn không vào báo cáo")
    void notOverdue_excluded() {
        data();

        assertThat(service.report(null, null, accountant).customers())
                .extracting(OverdueRiskRow::customerId).doesNotContain(5L);
    }

    @Test
    @DisplayName("S8-09: Tổng hợp theo nhóm tuổi nợ")
    void byBucket() {
        data();

        List<OverdueBucketSummary> b = service.report(null, null, accountant).byBucket();

        assertThat(b).extracting(OverdueBucketSummary::bucket).containsExactly("D0_30", "D31_60", "D61_90", "D90_PLUS");
        assertThat(b).extracting(OverdueBucketSummary::customerCount).containsExactly(2, 2, 1, 1);
        assertThat(b.get(0).amount()).isEqualByComparingTo("6000000");
        assertThat(b.get(1).amount()).isEqualByComparingTo("6000000");
        assertThat(b.get(2).amount()).isEqualByComparingTo("1000000");
        assertThat(b.get(3).amount()).isEqualByComparingTo("1500000");
    }

    @Test
    @DisplayName("S8-09: Tổng hợp theo NV phụ trách, nợ quá hạn nhiều nhất trước, có dòng chưa phân công")
    void byRep() {
        data();

        OverdueReportResponse r = service.report(null, null, accountant);

        assertThat(r.byRep()).extracting(OverdueRepSummary::salesRepName)
                .containsExactly(OverdueDebtReportService.UNASSIGNED_REP, "NV 7", "NV 9");
        OverdueRepSummary rep7 = r.byRep().get(1);
        assertThat(rep7.salesRepId()).isEqualTo(7L);
        assertThat(rep7.customerCount()).isEqualTo(2);
        assertThat(rep7.highRiskCount()).isEqualTo(1);
        assertThat(rep7.outstanding()).isEqualByComparingTo("9000000");
        assertThat(rep7.overdueAmount()).isEqualByComparingTo("3000000");
        assertThat(r.byRep().get(0).salesRepId()).isNull();
        assertThat(r.totalOutstanding()).isEqualByComparingTo("14500000");
        assertThat(r.totalOverdue()).isEqualByComparingTo("8500000");
    }

    @Test
    @DisplayName("S8-09: top giới hạn số đại lý rủi ro trả về (mặc định 10, tối đa 100)")
    void topLimit() {
        data();

        OverdueReportResponse r = service.report(null, 2, accountant);

        assertThat(r.topRisks()).extracting(OverdueRiskRow::customerId).containsExactly(3L, 2L);
        assertThat(r.customers()).hasSize(4);
    }

    @Test
    @DisplayName("S8-09: Lấy số liệu từ sổ công nợ chỉ đại lý quá hạn, truyền đúng NV và người xem (để giữ phân quyền)")
    void usesLedgerWithOverdueFilter() {
        data();
        UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");

        service.report(9L, null, rep);

        ArgumentCaptor<DebtLedgerService.Filter> f = ArgumentCaptor.forClass(DebtLedgerService.Filter.class);
        verify(debtLedgerService).compute(f.capture(), eq(rep));
        assertThat(f.getValue().overdueOnly()).isTrue();
        assertThat(f.getValue().salesRepId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("S8-09: Không có quyền xem sổ công nợ thì cũng không xem được báo cáo")
    void forbiddenPropagates() {
        UserDetailsImpl wh = actor(4, "ROLE_WAREHOUSE");
        when(debtLedgerService.compute(any(), eq(wh))).thenThrow(BusinessException.forbidden("DEBT_LEDGER_FORBIDDEN", "x"));

        assertThatThrownBy(() -> service.report(null, null, wh)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("S8-09: Không có đại lý nợ quá hạn -> báo cáo rỗng, các nhóm = 0")
    void empty() {
        when(debtLedgerService.compute(any(), any())).thenReturn(new DebtLedgerService.Computed(TODAY, List.of(),
                DebtLedgerService.totals(List.of())));

        OverdueReportResponse r = service.report(null, null, accountant);

        assertThat(r.customers()).isEmpty();
        assertThat(r.byRep()).isEmpty();
        assertThat(r.byBucket()).allSatisfy(b -> assertThat(b.amount()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("S8-09: Xuất Excel 3 sheet: xếp hạng rủi ro, theo nhóm tuổi nợ, theo NV")
    void export_excel() throws Exception {
        data();

        OverdueDebtReportService.ExportFile file = service.export(null, accountant);

        assertThat(file.fileName()).isEqualTo("bao-cao-no-qua-han-20261016.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            Sheet s1 = wb.getSheet("XepHangRuiRo");
            assertThat(s1.getRow(4).getCell(1).getStringCellValue()).isEqualTo("DL-3");
            assertThat(s1.getRow(4).getCell(4).getStringCellValue()).isEqualTo("Cao");
            assertThat(s1.getRow(8).getCell(8).getNumericCellValue()).isEqualTo(8500000d);
            Sheet s2 = wb.getSheet("TheoNhomTuoiNo");
            assertThat(s2.getRow(7).getCell(0).getStringCellValue()).isEqualTo("Trên 90 ngày");
            assertThat(s2.getRow(7).getCell(2).getNumericCellValue()).isEqualTo(1500000d);
            Sheet s3 = wb.getSheet("TheoNhanVien");
            assertThat(s3.getRow(4).getCell(0).getStringCellValue()).isEqualTo(OverdueDebtReportService.UNASSIGNED_REP);
        }
    }
}
