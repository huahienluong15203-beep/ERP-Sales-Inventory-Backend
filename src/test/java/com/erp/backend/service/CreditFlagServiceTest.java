package com.erp.backend.service;

import com.erp.backend.dto.credit.CreditFlagRunResponse;
import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.order.ApprovalReason;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S7-04: Tự gắn / gỡ cờ cảnh báo công nợ, quét hằng ngày, đơn của đại lý bị cờ phải duyệt. */
@ExtendWith(MockitoExtension.class)
class CreditFlagServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerCreditService creditService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private CreditFlagService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 0, 15);

    private Customer c;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
        c = customer(6L, null);
    }

    /** Trạng thái công nợ giả: hạn mức, nợ hiện tại, có quá hạn không. */
    private static CreditStatusResponse status(String limit, String debt, boolean overdue) {
        BigDecimal l = new BigDecimal(limit);
        BigDecimal d = new BigDecimal(debt);
        return new CreditStatusResponse(6L, "DL-6", "Đại lý 6", l, d, l.subtract(d), BigDecimal.ZERO, d,
                d.compareTo(l) > 0, d.subtract(l).max(BigDecimal.ZERO), 30,
                overdue, overdue ? 2 : 0, overdue ? new BigDecimal("5000000") : BigDecimal.ZERO, overdue ? 12 : 0,
                overdue, null);
    }

    private void stub(CreditStatusResponse s) {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(c));
        when(creditService.evaluate(eq(c), any())).thenReturn(s);
    }

    @Test
    @DisplayName("S7-04: Vượt hạn mức -> tự gắn cờ, ghi lý do, thời điểm, nhật ký CREDIT_FLAG_ON")
    void overLimit_flagged() {
        stub(status("10000000", "12000000", false));

        CreditFlagService.Result r = service.reevaluate(6L, "DAILY_JOB");

        assertThat(r.flagged()).isTrue();
        assertThat(r.changed()).isTrue();
        assertThat(c.isCreditFlaggedNow()).isTrue();
        assertThat(c.getCreditFlaggedAt()).isEqualTo(NOW);
        assertThat(c.getCreditCheckedAt()).isEqualTo(NOW);
        assertThat(c.getCreditFlagReason()).contains("vượt hạn mức");
        verify(customerRepository).save(c);
        verify(auditLogService).record(eq(AuditModule.DEBT_LIMIT), eq("CREDIT_FLAG_ON"), eq("CUSTOMER"), eq(6L),
                eq(c.getCode()), eq("NORMAL"), eq("FLAGGED"), contains("DAILY_JOB"), isNull());
    }

    @Test
    @DisplayName("S7-04: Có khoản nợ quá số ngày cho phép -> gắn cờ với lý do quá hạn")
    void overdue_flagged() {
        stub(status("100000000", "5000000", true));

        CreditFlagService.Result r = service.reevaluate(6L, "DAILY_JOB");

        assertThat(r.flagged()).isTrue();
        assertThat(c.getCreditFlagReason()).contains("quá hạn 12 ngày");
    }

    @Test
    @DisplayName("S7-04: Hết vượt hạn mức và hết quá hạn -> tự gỡ cờ, ghi nhật ký CREDIT_FLAG_OFF")
    void recovered_unflagged() {
        c.setCreditFlagged(true);
        c.setCreditFlaggedAt(NOW.minusDays(3));
        c.setCreditFlagReason("cũ");
        stub(status("10000000", "1000000", false));

        CreditFlagService.Result r = service.reevaluate(6L, "PAYMENT");

        assertThat(r.flagged()).isFalse();
        assertThat(r.changed()).isTrue();
        assertThat(c.isCreditFlaggedNow()).isFalse();
        assertThat(c.getCreditFlaggedAt()).isNull();
        assertThat(c.getCreditFlagReason()).isNull();
        verify(auditLogService).record(eq(AuditModule.DEBT_LIMIT), eq("CREDIT_FLAG_OFF"), any(), any(), any(),
                eq("FLAGGED"), eq("NORMAL"), contains("PAYMENT"), any());
    }

    @Test
    @DisplayName("S7-04: Vẫn bị cờ như hôm qua -> không ghi nhật ký lặp lại, giữ thời điểm gắn cờ, cập nhật lý do mới")
    void stillFlagged_noDuplicateAudit() {
        LocalDateTime since = NOW.minusDays(2);
        c.setCreditFlagged(true);
        c.setCreditFlaggedAt(since);
        c.setCreditFlagReason("lý do cũ");
        stub(status("10000000", "15000000", false));

        CreditFlagService.Result r = service.reevaluate(6L, "DAILY_JOB");

        assertThat(r.flagged()).isTrue();
        assertThat(r.changed()).isFalse();
        assertThat(c.getCreditFlaggedAt()).isEqualTo(since);
        assertThat(c.getCreditFlagReason()).contains("15.000.000");
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("S7-04: Đại lý không còn -> bỏ qua, không lỗi")
    void missingCustomer_skipped() {
        when(customerRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        CreditFlagService.Result r = service.reevaluate(99L, "DAILY_JOB");

        assertThat(r.flagged()).isFalse();
        verifyNoInteractions(creditService, auditLogService);
    }

    @Test
    @DisplayName("S7-04: Cấu hình chặn: chỉ BLOCK mới chặn, giá trị lạ / trống coi như cần duyệt")
    void blocks_config() {
        assertThat(CreditFlagService.blocks("BLOCK")).isTrue();
        assertThat(CreditFlagService.blocks(" block ")).isTrue();
        assertThat(CreditFlagService.blocks("REQUIRE_APPROVAL")).isFalse();
        assertThat(CreditFlagService.blocks(null)).isFalse();
        assertThat(CreditFlagService.blocks("abc")).isFalse();
    }

    // ======================= QUÉT HẰNG NGÀY =======================

    @Test
    @DisplayName("S7-04: Quét mọi đại lý đang hoạt động; 1 đại lý lỗi không làm dừng cả lần quét")
    void runner_countsAndSurvivesErrors() {
        CreditFlagService flagService = mock(CreditFlagService.class);
        CreditFlagRunner runner = new CreditFlagRunner(customerRepository, flagService);
        when(customerRepository.findIdsByStatus("ACTIVE")).thenReturn(List.of(1L, 2L, 3L, 4L));
        when(flagService.reevaluate(1L, "DAILY_JOB")).thenReturn(new CreditFlagService.Result(1L, true, true, "x"));
        when(flagService.reevaluate(2L, "DAILY_JOB")).thenReturn(new CreditFlagService.Result(2L, true, false, "x"));
        when(flagService.reevaluate(3L, "DAILY_JOB")).thenReturn(new CreditFlagService.Result(3L, false, true, null));
        when(flagService.reevaluate(4L, "DAILY_JOB")).thenThrow(BusinessException.notFound("lỗi giả"));

        CreditFlagRunResponse res = runner.runAll("DAILY_JOB");

        assertThat(res.checked()).isEqualTo(4);
        assertThat(res.flagged()).isEqualTo(2);
        assertThat(res.newlyFlagged()).isEqualTo(1);
        assertThat(res.unflagged()).isEqualTo(1);
        assertThat(res.errors()).isEqualTo(1);
    }

    // ======================= ĐƠN CỦA ĐẠI LÝ BỊ CỜ =======================

    @Test
    @DisplayName("S7-04: Chốt đơn khi đại lý đang bị cờ -> có lý do cần duyệt CREDIT_FLAGGED; hết cờ thì xoá lý do")
    void submit_flaggedCustomer_needsApproval() {
        c.setCreditFlagged(true);
        c.setCreditFlagReason("Có 2 khoản nợ quá hạn 12 ngày");
        SalesOrder o = SalesOrder.builder().id(1L).code("DH1").customer(c).build();
        // applyViolations không dùng tới các repository -> dùng bản thật của hàm, không cần dựng đủ phụ thuộc
        OrderApprovalService approval = mock(OrderApprovalService.class, CALLS_REAL_METHODS);

        approval.applyViolations(o, null);

        assertThat(o.getCreditFlaggedAtSubmit()).isTrue();
        assertThat(OrderApprovalReasons.of(o)).extracting(ApprovalReason::code).contains(ApprovalReason.CREDIT_FLAGGED);

        c.setCreditFlagged(false);
        approval.applyViolations(o, null);
        assertThat(o.getCreditFlaggedAtSubmit()).isNull();
        assertThat(OrderApprovalReasons.of(o)).extracting(ApprovalReason::code).doesNotContain(ApprovalReason.CREDIT_FLAGGED);
    }
}
