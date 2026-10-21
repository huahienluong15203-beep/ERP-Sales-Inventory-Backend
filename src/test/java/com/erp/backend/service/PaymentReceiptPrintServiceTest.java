package com.erp.backend.service;

import com.erp.backend.dto.payment.PaymentAllocationResponse;
import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.entity.PaymentReceipt;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** S7-09: In phiếu thu. */
@ExtendWith(MockitoExtension.class)
class PaymentReceiptPrintServiceTest {

    @Mock private PaymentService paymentService;

    @InjectMocks private PaymentReceiptPrintService service;

    private static PaymentReceiptResponse receipt(String status, String customerName, String amount,
                                                  List<PaymentAllocationResponse> allocations) {
        return new PaymentReceiptResponse(5L, "PT2026-000007", 6L, "DL-6", customerName, "12 Lê Lợi", "0901234567",
                new BigDecimal(amount), PaymentReceipt.METHOD_BANK_TRANSFER, LocalDate.of(2026, 10, 16),
                "Nguyễn Thu", "accountant", 3L, "Trả tiền tháng 9", status, null,
                LocalDateTime.of(2026, 10, 16, 9, 30), "accountant", null, null,
                PaymentReceipt.STATUS_REJECTED.equals(status) ? "Sai số tiền" : null,
                PaymentReceipt.ALLOCATION_FIFO, allocations, LocalDateTime.of(2026, 10, 16, 9, 30), "accountant");
    }

    private static PaymentAllocationResponse alloc(String invoice, String allocated) {
        return new PaymentAllocationResponse(1L, invoice, "DH001", new BigDecimal("2000000"),
                new BigDecimal("2000000"), new BigDecimal(allocated),
                new BigDecimal("2000000").subtract(new BigDecimal(allocated)));
    }

    @Test
    @DisplayName("S7-09: Phiếu thu có mã, đại lý, số tiền bằng số và chữ, hình thức, người thu, hoá đơn đối trừ, 2 liên")
    void render_confirmed() {
        String html = PaymentReceiptPrintService.render(receipt(PaymentReceipt.STATUS_CONFIRMED, "Đại lý Minh An",
                "1500000", List.of(alloc("HD2026-000001", "1500000"))), false);

        assertThat(html).contains("PHIẾU THU").contains("PT2026-000007").contains("16/10/2026")
                .contains("Đại lý Minh An").contains("DL-6").contains("12 Lê Lợi").contains("0901234567")
                .contains("1.500.000 ₫").contains("Một triệu năm trăm nghìn đồng")
                .contains("Chuyển khoản").contains("Nguyễn Thu")
                .contains("HD2026-000001").contains("500.000 ₫")
                .contains("Liên 1: Lưu").contains("Liên 2: Giao đại lý")
                .doesNotContain("KHÔNG CÓ GIÁ TRỊ").doesNotContain("CHƯA CÓ GIÁ TRỊ")
                .doesNotContain("window.print()");
    }

    @Test
    @DisplayName("S7-09: Phiếu chờ nộp quỹ / bị từ chối in ra có dấu không có giá trị và lý do từ chối")
    void render_notConfirmed_watermarked() {
        String pending = PaymentReceiptPrintService.render(
                receipt(PaymentReceipt.STATUS_PENDING_SUBMISSION, "A", "100000", List.of()), false);
        String rejected = PaymentReceiptPrintService.render(
                receipt(PaymentReceipt.STATUS_REJECTED, "A", "100000", List.of()), false);

        assertThat(pending).contains("CHỜ NỘP QUỸ – CHƯA CÓ GIÁ TRỊ").contains("Chưa đối trừ vào hoá đơn nào");
        assertThat(rejected).contains("ĐÃ TỪ CHỐI – KHÔNG CÓ GIÁ TRỊ").contains("Sai số tiền");
    }

    @Test
    @DisplayName("S7-09: Dữ liệu người nhập được escape HTML, autoprint mở hộp thoại in")
    void render_escapesAndAutoPrint() {
        String html = PaymentReceiptPrintService.render(receipt(PaymentReceipt.STATUS_CONFIRMED,
                "<script>alert(1)</script>", "100000", List.of()), true);

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;")
                .contains("window.print()");
    }

    @Test
    @DisplayName("S7-09: In phiếu kiểm quyền như xem phiếu thu (đại lý khác -> 404)")
    void printHtml_usesReceiptAccessCheck() {
        UserDetailsImpl other = actor(99, "ROLE_CUSTOMER");
        when(paymentService.getReceipt(5L, other)).thenThrow(BusinessException.notFound("Không tìm thấy phiếu thu"));

        assertThatThrownBy(() -> service.printHtml(5L, false, other)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("S7-09: In phiếu được phép trả HTML của đúng phiếu")
    void printHtml_ok() {
        UserDetailsImpl acc = actor(3, "ROLE_ACCOUNTANT");
        when(paymentService.getReceipt(5L, acc)).thenReturn(receipt(PaymentReceipt.STATUS_CONFIRMED, "A", "100000", List.of()));

        assertThat(service.printHtml(5L, false, acc)).contains("PT2026-000007").contains("Một trăm nghìn đồng");
    }

    @Test
    @DisplayName("S7-09: Đọc số tiền thành chữ")
    void moneyWords() {
        assertThat(MoneyWords.vnd(BigDecimal.ZERO)).isEqualTo("Không đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("5"))).isEqualTo("Năm đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("15"))).isEqualTo("Mười lăm đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("21"))).isEqualTo("Hai mươi mốt đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("105"))).isEqualTo("Một trăm linh năm đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("1050000"))).isEqualTo("Một triệu không trăm năm mươi nghìn đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("12000000"))).isEqualTo("Mười hai triệu đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("1000005"))).isEqualTo("Một triệu không trăm linh năm đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("2000000000"))).isEqualTo("Hai tỷ đồng");
        assertThat(MoneyWords.vnd(new BigDecimal("1234567.50"))).isEqualTo(
                "Một triệu hai trăm ba mươi bốn nghìn năm trăm sáu mươi tám đồng");
    }
}
