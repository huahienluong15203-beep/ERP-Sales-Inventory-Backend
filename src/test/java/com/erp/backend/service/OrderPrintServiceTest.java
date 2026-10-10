package com.erp.backend.service;

import com.erp.backend.dto.order.OrderLineResponse;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** S4-08: Mẫu in đơn hàng có đủ thông tin, mã vạch Code 128 đúng chuẩn, chống chèn mã HTML. */
@ExtendWith(MockitoExtension.class)
class OrderPrintServiceTest {

    @Mock private OrderDraftService orderDraftService;

    @InjectMocks private OrderPrintService service;

    private final UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");

    private static OrderResponse order(String customerName, String note) {
        OrderLineResponse line = mock(OrderLineResponse.class);
        when(line.productSku()).thenReturn("SP-COCA");
        when(line.productName()).thenReturn("Coca lon");
        when(line.unitName()).thenReturn("Thùng");
        when(line.quantity()).thenReturn(new BigDecimal("5.0000"));
        when(line.pricePerUnit()).thenReturn(new BigDecimal("240000"));
        when(line.discountAmount()).thenReturn(new BigDecimal("60000"));
        when(line.netAmount()).thenReturn(new BigDecimal("1140000"));
        OrderResponse o = mock(OrderResponse.class);
        when(o.code()).thenReturn("DH261010-AB12");
        when(o.status()).thenReturn("APPROVED");
        when(o.customerName()).thenReturn(customerName);
        when(o.customerCode()).thenReturn("DL-6");
        when(o.customerGroupLabel()).thenReturn("Đại lý cấp 1");
        when(o.deliveryAddress()).thenReturn(new OrderResponse.DeliveryAddressInfo(3L, "Kho chính", "12 Trần Phú",
                "Chị Lan", "0912345678"));
        when(o.desiredDeliveryDate()).thenReturn(LocalDate.of(2026, 10, 12));
        when(o.lines()).thenReturn(List.of(line));
        when(o.subtotal()).thenReturn(new BigDecimal("1200000"));
        when(o.discountTotal()).thenReturn(new BigDecimal("60000"));
        when(o.totalAmount()).thenReturn(new BigDecimal("1140000"));
        when(o.createdAt()).thenReturn(LocalDateTime.of(2026, 10, 10, 9, 30));
        when(o.createdByUsername()).thenReturn("sales_rep");
        when(o.note()).thenReturn(note);
        return o;
    }

    @Test
    @DisplayName("S4-08: Mẫu in có đại lý, điểm giao, dòng hàng, chiết khấu, tổng tiền, mã đơn và mã vạch")
    void print_containsAllSections() {
        OrderResponse o = order("Đại lý Minh Phát", "Giao buổi sáng");
        when(orderDraftService.getById(100L, rep)).thenReturn(o);

        String html = service.printHtml(100L, false, rep);

        assertThat(html).contains("ĐƠN ĐẶT HÀNG", "DH261010-AB12", "Đại lý Minh Phát", "DL-6", "12 Trần Phú", "Chị Lan",
                "12/10/2026", "SP-COCA", "Thùng", "240.000 ₫", "60.000 ₫", "1.140.000 ₫", "1.200.000 ₫", "Đã xác nhận",
                "Giao buổi sáng", "<svg");
        assertThat(html).doesNotContain("window.print");
        assertThat(html).contains(">5<");
    }

    @Test
    @DisplayName("S4-08: Dữ liệu người nhập có thẻ <script> bị escape, không chạy được")
    void print_escapesHtml() {
        OrderResponse o = order("<script>alert(1)</script>", "<img src=x onerror=alert(2)>");
        when(orderDraftService.getById(100L, rep)).thenReturn(o);

        String html = service.printHtml(100L, true, rep);

        assertThat(html).doesNotContain("<script>alert(1)</script>").doesNotContain("<img src=x");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("window.print");
    }

    @Test
    @DisplayName("S4-08: NV kinh doanh in đơn của đại lý người khác -> 404 (kiểm quyền như xem đơn)")
    void print_otherRep_hidden() {
        when(orderDraftService.getById(100L, rep)).thenThrow(new BusinessException(HttpStatus.NOT_FOUND,
                "CUSTOMER_NOT_FOUND", "Không tìm thấy", null));

        assertThatThrownBy(() -> service.printHtml(100L, false, rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("S4-08: Bảng Code 128 đủ 107 ký hiệu, mỗi ký hiệu rộng 11 mô-đun (Stop 13)")
    void code128_tableIsValid() {
        assertThat(Code128Svg.PATTERNS).hasSize(107);
        for (int i = 0; i < 106; i++) {
            assertThat(Code128Svg.PATTERNS[i].chars().map(c -> c - '0').sum()).as("ký hiệu %d", i).isEqualTo(11);
        }
        assertThat(Code128Svg.PATTERNS[106].chars().map(c -> c - '0').sum()).isEqualTo(13);
    }

    @Test
    @DisplayName("S4-08: Checksum Code 128B: 'A' -> (104 + 33) mod 103 = 34; có Start B và Stop")
    void code128_checksum() {
        assertThat(Code128Svg.symbols("A")).containsExactly(104, 33, 34, 106);
        // "AB": 104 + 33*1 + 34*2 = 205 -> 205 mod 103 = 102
        assertThat(Code128Svg.symbols("AB")).containsExactly(104, 33, 34, 102, 106);
    }

    @Test
    @DisplayName("S4-08: Ký tự ngoài ASCII không tạo được mã vạch")
    void code128_rejectsNonAscii() {
        assertThatThrownBy(() -> Code128Svg.symbols("ĐH01")).isInstanceOf(IllegalArgumentException.class);
    }
}
