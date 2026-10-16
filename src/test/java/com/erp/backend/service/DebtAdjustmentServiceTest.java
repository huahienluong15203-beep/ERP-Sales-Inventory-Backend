package com.erp.backend.service;

import com.erp.backend.dto.creditnote.CreditNoteCreateRequest;
import com.erp.backend.dto.debtadjustment.DebtAdjustmentResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S8-02: API màn hình "Chứng từ giảm trừ công nợ" (/api/debt-adjustments) nối với chứng từ giảm trừ. */
@ExtendWith(MockitoExtension.class)
class DebtAdjustmentServiceTest {

    @Mock private DebtAdjustmentRejectionRepository rejectionRepository;
    @Mock private CreditNoteRepository creditNoteRepository;
    @Mock private SalesReturnRepository salesReturnRepository;
    @Mock private PriceListItemRepository priceItemRepository;
    @Mock private CreditNoteService creditNoteService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private DebtAdjustmentService service;

    private final UserDetailsImpl accountant = actor(3, "ROLE_ACCOUNTANT");
    private Customer c;
    private Invoice hd;
    private InvoiceLine il;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(LocalDate.of(2026, 10, 16).atStartOfDay(DebtAdjustmentService.VN_ZONE).plusHours(9)
                .toInstant(), DebtAdjustmentService.VN_ZONE);
        c = customer(6, null);
        c.setPhone("0901234567");
        hd = Invoice.builder().id(1L).code("HD2026-000001").customer(c).invoiceDate(LocalDate.of(2026, 10, 2))
                .totalAmount(new BigDecimal("1000000")).build();
        // 10 thùng (x24 lon), thành tiền sau chiết khấu 900.000 -> giá đã bán 90.000 / thùng
        il = InvoiceLine.builder().id(11L).invoice(hd).productSku("SP-X").productName("Bia X").unitName("Thùng")
                .conversionFactor(new BigDecimal("24")).quantity(new BigDecimal("10"))
                .pricePerUnit(new BigDecimal("100000")).netAmount(new BigDecimal("900000")).build();
    }

    private SalesReturn ret(long id, String code) {
        SalesReturn r = SalesReturn.builder().id(id).code(code).status(SalesReturn.STATUS_RECEIVED).invoice(hd)
                .invoiceCode(hd.getCode()).customer(c).returnReason(SalesReturn.REASON_DEFECTIVE_TRANSPORT)
                .reasonDescription("Móp vỏ lon").receivedAt(LocalDateTime.of(2026, 10, 15, 10, 30))
                .receivedByName("wh_manager").build();
        r.addLine(SalesReturnLine.builder().id(id * 10).invoiceLine(il).returnQuantity(new BigDecimal("3")).build());
        return r;
    }

    private CreditNote approvedNote(SalesReturn r) {
        CreditNote cn = CreditNote.builder().id(70L).code("GG2026-000005").salesReturn(r).amount(new BigDecimal("270000.00"))
                .note("Đồng ý").createdAt(LocalDateTime.of(2026, 10, 16, 8, 0)).createdByUsername("accountant").build();
        cn.addLine(CreditNoteLine.builder().salesReturnLineId(r.getLines().get(0).getId()).invoiceLineId(11L)
                .amount(new BigDecimal("270000.00")).build());
        return cn;
    }

    @Test
    @DisplayName("S8-02: Danh sách: phiếu trả đã nhập kho -> Chờ duyệt / Đã duyệt / Từ chối, đúng tên trường của FE")
    void list_statuses() {
        SalesReturn pending = ret(1, "TH-01");
        SalesReturn approved = ret(2, "TH-02");
        SalesReturn rejected = ret(3, "TH-03");
        when(rejectionRepository.findReceivedReturns()).thenReturn(List.of(pending, approved, rejected));
        when(creditNoteRepository.findBySalesReturn_IdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(approvedNote(approved)));
        when(rejectionRepository.findBySalesReturn_IdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(
                DebtAdjustmentRejection.builder().salesReturn(rejected).reason("Hàng không phải của mình")
                        .rejectedAt(LocalDateTime.of(2026, 10, 16, 8, 30)).rejectedByUsername("accountant").build()));
        when(creditNoteRepository.sumCreditedQuantityByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);
        when(creditNoteRepository.sumCreditedAmountByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);

        List<DebtAdjustmentResponse> list = service.list(null, null, null, accountant);

        assertThat(list).extracting(DebtAdjustmentResponse::status).containsExactly("PENDING_APPROVAL", "APPROVED", "REJECTED");
        DebtAdjustmentResponse p = list.get(0);
        assertThat(p.id()).isEqualTo(1L);
        assertThat(p.adjustmentCode()).isNull();
        assertThat(p.returnOrderCode()).isEqualTo("TH-01");
        assertThat(p.originalInvoiceCode()).isEqualTo("HD2026-000001");
        assertThat(p.originalInvoiceDate()).isEqualTo("2026-10-02");
        assertThat(p.customerPhone()).isEqualTo("0901234567");
        assertThat(p.returnReason()).isEqualTo("Hỏng/vỡ do vận chuyển: Móp vỏ lon");
        assertThat(p.createdAt()).isEqualTo("2026-10-15 10:30");
        assertThat(p.createdBy()).isEqualTo("wh_manager");
        // Chờ duyệt: số sẽ giảm trừ = 3/10 x 900.000
        assertThat(p.totalAdjustmentAmount()).isEqualByComparingTo("270000");
        assertThat(p.items().get(0).originalSoldPrice()).isEqualByComparingTo("90000");
        assertThat(p.items().get(0).baseUnit()).isEqualTo("Thùng");

        DebtAdjustmentResponse a = list.get(1);
        assertThat(a.adjustmentCode()).isEqualTo("GG2026-000005");
        assertThat(a.approvedBy()).isEqualTo("accountant");
        assertThat(a.approvalNote()).isEqualTo("Đồng ý");
        assertThat(a.totalAdjustmentAmount()).isEqualByComparingTo("270000");

        DebtAdjustmentResponse r = list.get(2);
        assertThat(r.approvalNote()).isEqualTo("Từ chối: Hàng không phải của mình");
        assertThat(r.totalAdjustmentAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("S8-02: Lọc theo trạng thái và từ khoá")
    void list_filters() {
        SalesReturn pending = ret(1, "TH-01");
        SalesReturn approved = ret(2, "TH-02");
        when(rejectionRepository.findReceivedReturns()).thenReturn(List.of(pending, approved));
        when(creditNoteRepository.findBySalesReturn_IdIn(any())).thenReturn(List.of(approvedNote(approved)));
        when(rejectionRepository.findBySalesReturn_IdIn(any())).thenReturn(List.of());

        assertThat(service.list("approved", null, null, accountant)).extracting(DebtAdjustmentResponse::id).containsExactly(2L);
        assertThat(service.list("ALL", null, "gg2026", accountant)).extracting(DebtAdjustmentResponse::id).containsExactly(2L);
        assertThatThrownBy(() -> service.list("ABC", null, null, accountant))
                .hasFieldOrPropertyWithValue("code", "INVALID_STATUS");
    }

    @Test
    @DisplayName("S8-02: Giá hiện hành = giá bảng giá hiện hành theo ĐVT cơ sở x hệ số (chỉ để đối chiếu)")
    void catalogPrice_perInvoiceUnit() {
        SalesReturn pending = ret(1, "TH-01");
        when(rejectionRepository.findReceivedReturns()).thenReturn(List.of(pending));
        when(creditNoteRepository.findBySalesReturn_IdIn(any())).thenReturn(List.of());
        when(rejectionRepository.findBySalesReturn_IdIn(any())).thenReturn(List.of());
        when(creditNoteRepository.sumCreditedQuantityByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);
        when(creditNoteRepository.sumCreditedAmountByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);
        when(priceItemRepository.findEffective(eq(c.getCustomerGroup()), eq("SP-X"), any(), any()))
                .thenReturn(List.of(PriceListItem.builder().productSku("SP-X").price(new BigDecimal("4200")).build()));

        DebtAdjustmentResponse r = service.list(null, null, null, accountant).get(0);

        assertThat(r.items().get(0).currentCatalogPrice()).isEqualByComparingTo("100800");
        assertThat(r.items().get(0).originalSoldPrice()).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("S8-02: Duyệt = lập chứng từ giảm trừ (giảm công nợ ngay) và trả lại chứng từ Đã duyệt")
    void approve_createsCreditNote() {
        SalesReturn r = ret(1, "TH-01");
        when(salesReturnRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(r));
        when(rejectionRepository.existsBySalesReturn_Id(1L)).thenReturn(false);
        when(creditNoteRepository.findBySalesReturn_Id(1L)).thenReturn(Optional.of(approvedNote(r)));

        DebtAdjustmentResponse res = service.approve(1L, "Đồng ý", accountant);

        ArgumentCaptor<CreditNoteCreateRequest> req = ArgumentCaptor.forClass(CreditNoteCreateRequest.class);
        verify(creditNoteService).createFromReturn(req.capture(), eq(accountant));
        assertThat(req.getValue().getSalesReturnId()).isEqualTo(1L);
        assertThat(req.getValue().getNote()).isEqualTo("Đồng ý");
        assertThat(res.status()).isEqualTo("APPROVED");
        assertThat(res.adjustmentCode()).isEqualTo("GG2026-000005");
    }

    @Test
    @DisplayName("S8-02: Đã từ chối thì không duyệt được")
    void approve_rejected_conflict() {
        when(salesReturnRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ret(1, "TH-01")));
        when(rejectionRepository.existsBySalesReturn_Id(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.approve(1L, null, accountant))
                .hasFieldOrPropertyWithValue("code", "DEBT_ADJUSTMENT_REJECTED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        verifyNoInteractions(creditNoteService);
    }

    @Test
    @DisplayName("S8-02: Từ chối bắt buộc lý do, ghi nhật ký, không giảm công nợ")
    void reject_ok() {
        SalesReturn r = ret(1, "TH-01");
        when(salesReturnRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(r));
        when(creditNoteRepository.existsBySalesReturn_Id(1L)).thenReturn(false);
        when(rejectionRepository.existsBySalesReturn_Id(1L)).thenReturn(false);
        when(rejectionRepository.save(any(DebtAdjustmentRejection.class))).thenAnswer(i -> i.getArgument(0));

        DebtAdjustmentResponse res = service.reject(1L, "Hàng không đúng hoá đơn", accountant);

        assertThat(res.status()).isEqualTo("REJECTED");
        assertThat(res.approvalNote()).isEqualTo("Từ chối: Hàng không đúng hoá đơn");
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("REJECT_DEBT_ADJUSTMENT"), eq("SALES_RETURN"), eq(1L),
                eq("TH-01"), eq("PENDING_APPROVAL"), eq("REJECTED"), contains("Hàng không đúng hoá đơn"), eq(accountant));
        verifyNoInteractions(creditNoteService);
    }

    @Test
    @DisplayName("S8-02: Từ chối không lý do -> 400; đã duyệt rồi -> 409")
    void reject_invalid() {
        assertThatThrownBy(() -> service.reject(1L, "  ", accountant))
                .hasFieldOrPropertyWithValue("code", "REJECT_REASON_REQUIRED");

        when(salesReturnRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(ret(2, "TH-02")));
        when(creditNoteRepository.existsBySalesReturn_Id(2L)).thenReturn(true);
        assertThatThrownBy(() -> service.reject(2L, "Lý do hợp lệ", accountant))
                .hasFieldOrPropertyWithValue("code", "CREDIT_NOTE_EXISTS");
        verify(rejectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("S8-02: QL kinh doanh xem được nhưng không duyệt được; NV kinh doanh không xem được")
    void permissions() {
        UserDetailsImpl manager = actor(2, "ROLE_SALES_MANAGER");
        when(rejectionRepository.findReceivedReturns()).thenReturn(List.of());
        assertThat(service.list(null, null, null, manager)).isEmpty();

        assertThatThrownBy(() -> service.approve(1L, null, manager))
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.list(null, null, null, actor(7, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        verifyNoInteractions(creditNoteService);
    }
}
