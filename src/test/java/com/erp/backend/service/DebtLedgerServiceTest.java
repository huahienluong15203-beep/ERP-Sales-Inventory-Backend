package com.erp.backend.service;

import com.erp.backend.dto.debt.DebtAgingRow;
import com.erp.backend.dto.debt.DebtLedgerResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.DebtAgingRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** S7-03: Sổ công nợ & tuổi nợ. */
@ExtendWith(MockitoExtension.class)
class DebtLedgerServiceTest {

    @Mock private DebtAgingRepository agingRepository;
    @Mock private CustomerRepository customerRepository;

    @InjectMocks private DebtLedgerService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private final UserDetailsImpl accountant = actor(3, "ROLE_ACCOUNTANT");
    private Customer c6;
    private Customer c8;
    private Customer c10;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atStartOfDay(DebtLedgerService.VN_ZONE).plusHours(10).toInstant(),
                DebtLedgerService.VN_ZONE);
        c6 = customer(6, salesRep(7));
        c6.setName("Đại lý Minh An");
        c6.setCreditLimit(new BigDecimal("10000000"));
        c6.setMaxDebtDays(30);
        c8 = customer(8, salesRep(9));
        c8.setMaxDebtDays(30);
        c10 = customer(10, salesRep(9));
        c10.setMaxDebtDays(30);
    }

    private static Object[] inv(long cid, LocalDate date, String total, String paid, String remaining) {
        return new Object[]{cid, date, new BigDecimal(total), paid == null ? null : new BigDecimal(paid),
                new BigDecimal(remaining)};
    }

    /** Dữ liệu mẫu (hôm nay 16/10/2026). */
    private void data() {
        when(agingRepository.invoiceItems()).thenReturn(List.of(
                inv(6, LocalDate.of(2026, 10, 6), "1000000", "400000", "600000"),   // 10 ngày -> 0–30
                inv(6, LocalDate.of(2026, 8, 27), "2000000", "0", "2000000"),       // 50 ngày -> 31–60, quá hạn
                inv(6, LocalDate.of(2026, 6, 1), "500000", "500000", "0"),          // đã thu đủ -> không tính tuổi
                inv(6, LocalDate.of(2026, 7, 1), "300000", null, "300000"),         // 107 ngày -> trên 90, quá hạn
                inv(8, LocalDate.of(2026, 10, 14), "800000", "0", "800000"),        // 2 ngày
                inv(10, LocalDate.of(2026, 9, 1), "100000", "100000", "0")));       // đã thu đủ
        when(agingRepository.uninvoicedOrderItems(SalesOrder.OUTSTANDING_DEBT_STATUSES)).thenReturn(List.<Object[]>of(
                new Object[]{6L, LocalDateTime.of(2026, 8, 1, 10, 0), new BigDecimal("700000")})); // 76 ngày -> 61–90
        when(customerRepository.findAllById(any())).thenReturn(List.of(c6, c8, c10));
    }

    private DebtLedgerService.Filter filter(String keyword, String bucket, boolean overdueOnly, boolean includeSettled) {
        return new DebtLedgerService.Filter(keyword, null, bucket, overdueOnly, includeSettled, null);
    }

    @Test
    @DisplayName("S7-03: Phải thu / đã thu / còn nợ, chia tuổi nợ 0–30, 31–60, 61–90, >90; nợ lâu nhất lên đầu")
    void ledger_columnsAndAging() {
        data();

        DebtLedgerResponse res = service.ledger(filter(null, null, false, false), 0, 50, accountant);

        assertThat(res.asOf()).isEqualTo(TODAY);
        assertThat(res.rows()).extracting(DebtAgingRow::customerId).containsExactly(6L, 8L);
        DebtAgingRow r = res.rows().get(0);
        assertThat(r.customerName()).isEqualTo("Đại lý Minh An");
        assertThat(r.salesRepId()).isEqualTo(7L);
        assertThat(r.receivable()).isEqualByComparingTo("4500000");
        assertThat(r.collected()).isEqualByComparingTo("900000");
        assertThat(r.outstanding()).isEqualByComparingTo("3600000");
        assertThat(r.uninvoiced()).isEqualByComparingTo("700000");
        assertThat(r.d0To30()).isEqualByComparingTo("600000");
        assertThat(r.d31To60()).isEqualByComparingTo("2000000");
        assertThat(r.d61To90()).isEqualByComparingTo("700000");
        assertThat(r.over90()).isEqualByComparingTo("300000");
        assertThat(r.overdueAmount()).isEqualByComparingTo("3000000");
        assertThat(r.oldestDebtDays()).isEqualTo(107);
        // tổng 4 nhóm tuổi = còn nợ
        assertThat(r.d0To30().add(r.d31To60()).add(r.d61To90()).add(r.over90())).isEqualByComparingTo(r.outstanding());
    }

    @Test
    @DisplayName("S7-03: Dòng tổng cộng ở chân bảng cộng đúng mọi dòng")
    void ledger_totals() {
        data();

        DebtLedgerResponse res = service.ledger(filter(null, null, false, false), 0, 50, accountant);

        assertThat(res.totals().customerCount()).isEqualTo(2);
        assertThat(res.totals().receivable()).isEqualByComparingTo("5300000");
        assertThat(res.totals().collected()).isEqualByComparingTo("900000");
        assertThat(res.totals().outstanding()).isEqualByComparingTo("4400000");
        assertThat(res.totals().d0To30()).isEqualByComparingTo("1400000");
        assertThat(res.totals().over90()).isEqualByComparingTo("300000");
        assertThat(res.totals().overdueAmount()).isEqualByComparingTo("3000000");
    }

    @Test
    @DisplayName("S7-03: Lọc riêng nhóm quá hạn -> chỉ đại lý có nợ quá số ngày cho phép")
    void ledger_overdueOnly() {
        data();

        DebtLedgerResponse res = service.ledger(filter(null, null, true, false), 0, 50, accountant);

        assertThat(res.rows()).extracting(DebtAgingRow::customerId).containsExactly(6L);
        assertThat(res.totals().outstanding()).isEqualByComparingTo("3600000");
    }

    @Test
    @DisplayName("S7-03: Lọc theo 1 nhóm tuổi nợ")
    void ledger_bucketFilter() {
        data();

        assertThat(service.ledger(filter(null, "d90_plus", false, false), 0, 50, accountant).rows())
                .extracting(DebtAgingRow::customerId).containsExactly(6L);
        assertThat(service.ledger(filter(null, "D0_30", false, false), 0, 50, accountant).rows())
                .extracting(DebtAgingRow::customerId).containsExactly(6L, 8L);
    }

    @Test
    @DisplayName("S7-03: Mặc định ẩn đại lý đã trả hết; includeSettled = true thì hiện")
    void ledger_includeSettled() {
        data();

        DebtLedgerResponse res = service.ledger(filter(null, null, false, true), 0, 50, accountant);

        assertThat(res.rows()).extracting(DebtAgingRow::customerId).contains(10L);
        assertThat(res.rows().stream().filter(x -> x.customerId() == 10L).findFirst().orElseThrow().outstanding())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("S7-03: Tìm theo tên / mã đại lý")
    void ledger_keyword() {
        data();

        assertThat(service.ledger(filter("minh an", null, false, false), 0, 50, accountant).rows())
                .extracting(DebtAgingRow::customerId).containsExactly(6L);
        assertThat(service.ledger(filter("DL-8", null, false, false), 0, 50, accountant).rows())
                .extracting(DebtAgingRow::customerId).containsExactly(8L);
    }

    @Test
    @DisplayName("S7-03: Phân trang nhưng dòng tổng vẫn tính trên mọi trang")
    void ledger_paging() {
        data();

        DebtLedgerResponse res = service.ledger(filter(null, null, false, false), 1, 1, accountant);

        assertThat(res.rows()).extracting(DebtAgingRow::customerId).containsExactly(8L);
        assertThat(res.totalElements()).isEqualTo(2);
        assertThat(res.totalPages()).isEqualTo(2);
        assertThat(res.totals().outstanding()).isEqualByComparingTo("4400000");
    }

    @Test
    @DisplayName("S7-03: Sắp theo còn nợ giảm dần")
    void ledger_sortOutstanding() {
        data();

        DebtLedgerResponse res = service.ledger(
                new DebtLedgerService.Filter(null, null, null, false, false, "outstanding"), 0, 50, accountant);

        assertThat(res.rows()).extracting(DebtAgingRow::customerId).containsExactly(6L, 8L);
    }

    @Test
    @DisplayName("S7-03: NV kinh doanh chỉ thấy đại lý mình phụ trách, gửi salesRepId người khác cũng không được")
    void salesRep_scoped() {
        UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");
        when(agingRepository.invoiceItemsBySalesRep(7L)).thenReturn(List.<Object[]>of(
                inv(6, LocalDate.of(2026, 10, 6), "1000000", "0", "1000000")));
        when(agingRepository.uninvoicedOrderItemsBySalesRep(SalesOrder.OUTSTANDING_DEBT_STATUSES, 7L)).thenReturn(List.of());
        when(customerRepository.findAllById(any())).thenReturn(List.of(c6));

        DebtLedgerResponse res = service.ledger(new DebtLedgerService.Filter(null, 9L, null, false, false, null),
                0, 50, rep);

        assertThat(res.rows()).extracting(DebtAgingRow::customerId).containsExactly(6L);
        verify(agingRepository, never()).invoiceItems();
        verify(agingRepository, never()).invoiceItemsBySalesRep(9L);
    }

    @Test
    @DisplayName("S7-03: QL kinh doanh lọc theo 1 NV kinh doanh")
    void manager_filterBySalesRep() {
        UserDetailsImpl manager = actor(2, "ROLE_SALES_MANAGER");
        when(agingRepository.invoiceItemsBySalesRep(9L)).thenReturn(List.<Object[]>of(
                inv(8, LocalDate.of(2026, 10, 14), "800000", "0", "800000")));
        when(agingRepository.uninvoicedOrderItemsBySalesRep(SalesOrder.OUTSTANDING_DEBT_STATUSES, 9L)).thenReturn(List.of());
        when(customerRepository.findAllById(any())).thenReturn(List.of(c8));

        DebtLedgerResponse res = service.ledger(new DebtLedgerService.Filter(null, 9L, null, false, false, null),
                0, 50, manager);

        assertThat(res.rows()).extracting(DebtAgingRow::customerId).containsExactly(8L);
    }

    @Test
    @DisplayName("S7-03: NV kho / đại lý không được xem sổ công nợ")
    void otherRoles_forbidden() {
        assertThatThrownBy(() -> service.ledger(null, 0, 50, actor(4, "ROLE_WAREHOUSE")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.ledger(null, 0, 50, actor(5, "ROLE_CUSTOMER")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        verifyNoInteractions(agingRepository);
    }

    @Test
    @DisplayName("S7-03: Nhóm tuổi nợ / kiểu sắp xếp không hợp lệ -> 400")
    void invalidParams() {
        assertThatThrownBy(() -> service.ledger(filter(null, "D100", false, false), 0, 50, accountant))
                .hasFieldOrPropertyWithValue("code", "INVALID_AGING_BUCKET");
        assertThatThrownBy(() -> service.ledger(new DebtLedgerService.Filter(null, null, null, false, false, "abc"),
                0, 50, accountant))
                .hasFieldOrPropertyWithValue("code", "INVALID_SORT");
    }

    @Test
    @DisplayName("S7-03: Ranh giới nhóm tuổi nợ")
    void bucketBoundaries() {
        assertThat(DebtLedgerService.bucketOf(0)).isEqualTo("D0_30");
        assertThat(DebtLedgerService.bucketOf(30)).isEqualTo("D0_30");
        assertThat(DebtLedgerService.bucketOf(31)).isEqualTo("D31_60");
        assertThat(DebtLedgerService.bucketOf(60)).isEqualTo("D31_60");
        assertThat(DebtLedgerService.bucketOf(61)).isEqualTo("D61_90");
        assertThat(DebtLedgerService.bucketOf(90)).isEqualTo("D61_90");
        assertThat(DebtLedgerService.bucketOf(91)).isEqualTo("D90_PLUS");
    }

    @Test
    @DisplayName("S7-03: Xuất Excel có tiêu đề, từng đại lý và dòng tổng cộng")
    void export_excel() throws Exception {
        data();

        DebtLedgerService.ExportFile file = service.export(filter(null, null, false, false), accountant);

        assertThat(file.fileName()).isEqualTo("so-cong-no-20261016.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).contains("SỔ CÔNG NỢ");
            assertThat(s.getRow(3).getCell(8).getStringCellValue()).isEqualTo("Còn nợ (₫)");
            assertThat(s.getRow(4).getCell(1).getStringCellValue()).isEqualTo("DL-6");
            assertThat(s.getRow(4).getCell(8).getNumericCellValue()).isEqualTo(3600000d);
            assertThat(s.getRow(4).getCell(13).getNumericCellValue()).isEqualTo(300000d);
            assertThat(s.getRow(5).getCell(1).getStringCellValue()).isEqualTo("DL-8");
            assertThat(s.getRow(6).getCell(2).getStringCellValue()).contains("TỔNG CỘNG");
            assertThat(s.getRow(6).getCell(8).getNumericCellValue()).isEqualTo(4400000d);
        }
    }
}
