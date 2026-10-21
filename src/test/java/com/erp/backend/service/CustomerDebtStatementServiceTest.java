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
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static com.erp.backend.service.CustomerTestData.salesRep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** S8-10: Sổ chi tiết công nợ đại lý (số dư đầu kỳ, phát sinh, số dư luỹ kế, xuất Excel / PDF). */
@ExtendWith(MockitoExtension.class)
class CustomerDebtStatementServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private CreditNoteRepository creditNoteRepository;

    @InjectMocks private CustomerDebtStatementService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private final UserDetailsImpl accountant = actor(3, "ROLE_ACCOUNTANT");
    private Customer c6;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atTime(9, 0).atZone(VN).toInstant(), VN);
        c6 = customer(6, salesRep(7));
        c6.setName("Đại lý Minh An");
        c6.setAddress("12 Lê Lợi");
    }

    private static Invoice inv(long id, String code, LocalDate date, String total) {
        Invoice i = new Invoice();
        i.setId(id);
        i.setCode(code);
        i.setInvoiceDate(date);
        i.setTotalAmount(new BigDecimal(total));
        i.setStatus(Invoice.STATUS_ISSUED);
        return i;
    }

    private static PaymentReceipt pay(long id, String code, LocalDate date, String amount) {
        PaymentReceipt p = new PaymentReceipt();
        p.setId(id);
        p.setCode(code);
        p.setPaymentDate(date);
        p.setAmount(new BigDecimal(amount));
        p.setPaymentMethod(PaymentReceipt.METHOD_BANK_TRANSFER);
        p.setStatus(PaymentReceipt.STATUS_CONFIRMED);
        return p;
    }

    private static CreditNote note(long id, String code, LocalDate date, String amount) {
        CreditNote n = new CreditNote();
        n.setId(id);
        n.setCode(code);
        n.setNoteDate(date);
        n.setAmount(new BigDecimal(amount));
        n.setSalesReturnCode("PTH-1");
        n.setInvoiceCode("HD-2");
        n.setStatus(CreditNote.STATUS_CONFIRMED);
        return n;
    }

    /**
     * Trước kỳ: HĐ-1 5tr (20/08), thu 2tr (25/08) -> đầu kỳ 3tr.
     * Trong kỳ: HĐ-2 4tr (05/09), thu 1tr (05/09), giảm trừ 500k (10/09), HĐ-3 1tr (30/09).
     */
    private void data() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(c6));
        when(invoiceRepository.findByCustomer_IdAndInvoiceDateLessThanEqual(6L, TO)).thenReturn(List.of(
                inv(3, "HD-3", TO, "1000000"),
                inv(1, "HD-1", LocalDate.of(2026, 8, 20), "5000000"),
                inv(2, "HD-2", LocalDate.of(2026, 9, 5), "4000000")));
        when(paymentReceiptRepository.findByCustomer_IdAndStatusAndPaymentDateLessThanEqual(6L,
                PaymentReceipt.STATUS_CONFIRMED, TO)).thenReturn(List.of(
                pay(11, "PT-1", LocalDate.of(2026, 8, 25), "2000000"),
                pay(12, "PT-2", LocalDate.of(2026, 9, 5), "1000000")));
        when(creditNoteRepository.findByCustomer_IdAndNoteDateLessThanEqual(6L, TO)).thenReturn(List.of(
                note(21, "GT-1", LocalDate.of(2026, 9, 10), "500000")));
    }

    @Test
    @DisplayName("S8-10: Số dư đầu kỳ = phát sinh trước kỳ; cuối kỳ = đầu kỳ + tăng - giảm")
    void openingAndClosing() {
        data();

        CustomerDebtStatementResponse s = service.statement(6L, FROM, TO, accountant);

        assertThat(s.openingBalance()).isEqualByComparingTo("3000000");
        assertThat(s.totalDebit()).isEqualByComparingTo("5000000");
        assertThat(s.totalCredit()).isEqualByComparingTo("1500000");
        assertThat(s.closingBalance()).isEqualByComparingTo("6500000");
        assertThat(s.customerName()).isEqualTo("Đại lý Minh An");
    }

    @Test
    @DisplayName("S8-10: Liệt kê phát sinh theo thời gian (cùng ngày: hoá đơn trước phiếu thu), số dư luỹ kế từng dòng")
    void entriesOrderedWithRunningBalance() {
        data();

        List<DebtStatementEntry> e = service.statement(6L, FROM, TO, accountant).entries();

        assertThat(e).extracting(DebtStatementEntry::code).containsExactly("HD-2", "PT-2", "GT-1", "HD-3");
        assertThat(e).extracting(DebtStatementEntry::type).containsExactly(
                CustomerDebtStatementService.TYPE_INVOICE, CustomerDebtStatementService.TYPE_PAYMENT,
                CustomerDebtStatementService.TYPE_ADJUSTMENT, CustomerDebtStatementService.TYPE_INVOICE);
        assertThat(e.get(0).balance()).isEqualByComparingTo("7000000");
        assertThat(e.get(1).balance()).isEqualByComparingTo("6000000");
        assertThat(e.get(2).balance()).isEqualByComparingTo("5500000");
        assertThat(e.get(3).balance()).isEqualByComparingTo("6500000");
        assertThat(e.get(1).description()).contains("chuyển khoản");
        assertThat(e.get(2).description()).contains("PTH-1").contains("HD-2");
    }

    @Test
    @DisplayName("S8-10: Kỳ không có phát sinh -> chỉ có số dư đầu kỳ = cuối kỳ")
    void noEntriesInPeriod() {
        LocalDate to = LocalDate.of(2026, 10, 10);
        when(customerRepository.findById(6L)).thenReturn(Optional.of(c6));
        when(invoiceRepository.findByCustomer_IdAndInvoiceDateLessThanEqual(6L, to)).thenReturn(List.of(
                inv(1, "HD-1", LocalDate.of(2026, 8, 20), "5000000")));
        when(paymentReceiptRepository.findByCustomer_IdAndStatusAndPaymentDateLessThanEqual(6L,
                PaymentReceipt.STATUS_CONFIRMED, to)).thenReturn(List.of(pay(11, "PT-1", LocalDate.of(2026, 8, 25), "2000000")));
        when(creditNoteRepository.findByCustomer_IdAndNoteDateLessThanEqual(6L, to)).thenReturn(List.of());

        CustomerDebtStatementResponse s = service.statement(6L, LocalDate.of(2026, 10, 1), to, accountant);

        assertThat(s.entries()).isEmpty();
        assertThat(s.openingBalance()).isEqualByComparingTo("3000000");
        assertThat(s.closingBalance()).isEqualByComparingTo("3000000");
    }

    @Test
    @DisplayName("S8-10: Không chọn kỳ -> từ đầu tháng đến hôm nay")
    void defaultPeriod() {
        CustomerDebtStatementService.Period p = service.period(null, null);

        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.to()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("S8-10: Từ ngày sau đến ngày -> 400")
    void invalidPeriod() {
        assertThatThrownBy(() -> service.period(TO, FROM))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_PERIOD"));
    }

    @Test
    @DisplayName("S8-10: NV kinh doanh xem sổ đại lý người khác phụ trách -> 404")
    void otherRep_notFound() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(c6));

        assertThatThrownBy(() -> service.statement(6L, FROM, TO, actor(9, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(invoiceRepository);
    }

    @Test
    @DisplayName("S8-10: Xuất Excel có số dư đầu kỳ, từng dòng phát sinh, cộng phát sinh và số dư cuối kỳ")
    void exportExcel() throws Exception {
        data();

        CustomerDebtStatementService.ExportFile f = service.export(6L, FROM, TO, "xlsx", false, accountant);

        assertThat(f.fileName()).isEqualTo("so-chi-tiet-cong-no-DL-6-20260901-20260930.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(f.content()))) {
            Sheet sh = wb.getSheet("SoChiTietCongNo");
            // tiêu đề, đại lý, địa chỉ, kỳ, dòng trống, header ở dòng 5
            assertThat(sh.getRow(5).getCell(6).getStringCellValue()).isEqualTo("Số dư luỹ kế");
            assertThat(sh.getRow(6).getCell(3).getStringCellValue()).isEqualTo("Số dư đầu kỳ");
            assertThat(sh.getRow(6).getCell(6).getNumericCellValue()).isEqualTo(3000000d);
            assertThat(sh.getRow(7).getCell(2).getStringCellValue()).isEqualTo("HD-2");
            assertThat(sh.getRow(10).getCell(6).getNumericCellValue()).isEqualTo(6500000d);
            assertThat(sh.getRow(11).getCell(4).getNumericCellValue()).isEqualTo(5000000d);
            assertThat(sh.getRow(11).getCell(5).getNumericCellValue()).isEqualTo(1500000d);
            assertThat(sh.getRow(12).getCell(6).getNumericCellValue()).isEqualTo(6500000d);
        }
    }

    @Test
    @DisplayName("S8-10: Xuất PDF -> trang in A4 có số liệu, số dư bằng chữ, escape HTML")
    void exportPdfHtml() {
        data();
        c6.setName("<b>Minh An</b>");

        CustomerDebtStatementService.ExportFile f = service.export(6L, FROM, TO, "pdf", true, accountant);
        String html = new String(f.content(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(f.contentType()).startsWith("text/html");
        assertThat(html).contains("SỔ CHI TIẾT CÔNG NỢ").contains("01/09/2026").contains("3.000.000 ₫")
                .contains("6.500.000 ₫").contains("Sáu triệu năm trăm nghìn đồng").contains("window.print()")
                .contains("&lt;b&gt;Minh An").doesNotContain("<b>Minh An</b>");
    }

    @Test
    @DisplayName("S8-10: Định dạng xuất không hỗ trợ -> 400")
    void exportInvalidFormat() {
        data();

        assertThatThrownBy(() -> service.export(6L, FROM, TO, "docx", false, accountant))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_EXPORT_FORMAT"));
    }

    @Test
    @DisplayName("S8-10: Số dư âm (đại lý trả thừa) đọc bằng chữ có chữ Âm")
    void negativeWords() {
        assertThat(CustomerDebtStatementService.words(new BigDecimal("-1000000"))).isEqualTo("Âm một triệu đồng");
    }
}
