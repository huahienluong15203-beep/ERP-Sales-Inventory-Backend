package com.erp.backend.service;

import com.erp.backend.dto.kpi.MySalesProgressResponse;
import com.erp.backend.dto.kpi.SalesTargetCreateRequest;
import com.erp.backend.dto.kpi.SalesTargetResponse;
import com.erp.backend.dto.kpi.SalesTargetUpdateRequest;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.RegionRepository;
import com.erp.backend.repository.SalesTargetRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.region;
import static com.erp.backend.service.CustomerTestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S8-04: Chỉ tiêu doanh số theo NV / khu vực, khoá kỳ đã qua, sửa phải có lý do, NV xem tiến độ của mình. */
@ExtendWith(MockitoExtension.class)
class SalesTargetServiceTest {

    @Mock private SalesTargetRepository targetRepository;
    @Mock private UserRepository userRepository;
    @Mock private RegionRepository regionRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private SalesTargetService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final UserDetailsImpl manager = actor(3, "ROLE_SALES_MANAGER");
    private final UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");
    private final User repUser = user(7, "ACTIVE", RoleName.ROLE_SALES_REP);

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atTime(10, 0).atZone(VN).toInstant(), VN);
    }

    private SalesTarget target(long id, String period, String amount) {
        return SalesTarget.builder().id(id).periodMonth(LocalDate.parse(period + "-01"))
                .targetType(SalesTarget.TYPE_REP).targetKey("REP:7").salesRep(repUser)
                .targetAmount(new BigDecimal(amount)).build();
    }

    private static SalesTargetCreateRequest repRequest(String period, String amount) {
        return SalesTargetCreateRequest.builder().period(period).targetType("rep").salesRepId(7L)
                .targetAmount(new BigDecimal(amount)).build();
    }

    // ======================= ĐẶT CHỈ TIÊU =======================

    @Test
    @DisplayName("S8-04: Đặt chỉ tiêu tháng hiện tại cho NV kinh doanh -> lưu khoá REP:7, ghi nhật ký")
    void create_rep_success() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(repUser));
        when(targetRepository.save(any(SalesTarget.class))).thenAnswer(i -> {
            SalesTarget t = i.getArgument(0);
            t.setId(50L);
            return t;
        });

        SalesTargetResponse res = service.create(repRequest("2026-10", "500000000"), manager);

        assertThat(res.period()).isEqualTo("2026-10");
        assertThat(res.targetType()).isEqualTo(SalesTarget.TYPE_REP);
        assertThat(res.salesRepId()).isEqualTo(7L);
        assertThat(res.targetAmount()).isEqualByComparingTo("500000000");
        ArgumentCaptor<SalesTarget> c = ArgumentCaptor.forClass(SalesTarget.class);
        verify(targetRepository).save(c.capture());
        assertThat(c.getValue().getTargetKey()).isEqualTo("REP:7");
        assertThat(c.getValue().getPeriodMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        verify(auditLogService).record(eq(AuditModule.SALES_TARGET), eq("CREATE_SALES_TARGET"), eq("SALES_TARGET"),
                eq(50L), eq("REP:7@2026-10"), isNull(), eq("500000000"), anyString(), eq(manager));
    }

    @Test
    @DisplayName("S8-04: Đặt chỉ tiêu khu vực cho tháng sau")
    void create_region_futureMonth() {
        Region north = region(2, "ACTIVE");
        when(regionRepository.findById(2L)).thenReturn(Optional.of(north));
        when(targetRepository.save(any(SalesTarget.class))).thenAnswer(i -> i.getArgument(0));

        SalesTargetResponse res = service.create(SalesTargetCreateRequest.builder().period("2026-11").targetType("REGION")
                .regionId(2L).targetAmount(new BigDecimal("2000000000")).build(), manager);

        assertThat(res.regionId()).isEqualTo(2L);
        verify(targetRepository).existsByPeriodMonthAndTargetKey(LocalDate.of(2026, 11, 1), "REGION:2");
    }

    @Test
    @DisplayName("S8-04: Kỳ đã qua bị khoá: không đặt / xoá được (409), sửa bắt buộc có lý do và ghi nhật ký")
    void pastPeriod_locked() {
        assertThatThrownBy(() -> service.create(repRequest("2026-09", "1000"), manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "TARGET_PERIOD_LOCKED");

        when(targetRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(target(9, "2026-09", "1000")));
        assertThatThrownBy(() -> service.delete(9L, "Nhầm", manager))
                .hasFieldOrPropertyWithValue("code", "TARGET_PERIOD_LOCKED");

        // Sửa kỳ đã qua: không có lý do -> 400
        assertThatThrownBy(() -> service.update(9L, SalesTargetUpdateRequest.builder()
                .targetAmount(new BigDecimal("2000")).reason("   ").build(), manager))
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");

        // Sửa kỳ đã qua: có lý do -> thành công, ghi nhật ký có đánh dấu kỳ đã khoá
        SalesTarget t = target(9, "2026-09", "1000");
        when(targetRepository.save(any(SalesTarget.class))).thenAnswer(i -> i.getArgument(0));
        SalesTargetResponse res = service.update(9L, SalesTargetUpdateRequest.builder()
                .targetAmount(new BigDecimal("2000")).reason("Điều chỉnh bù trừ chỉ tiêu").build(), manager);
        assertThat(res.targetAmount()).isEqualByComparingTo("2000");
        assertThat(res.locked()).isTrue();
        verify(auditLogService).record(eq(AuditModule.SALES_TARGET), eq("UPDATE_SALES_TARGET"), eq("SALES_TARGET"),
                eq(9L), eq("REP:7@2026-09"), eq("1000"), eq("2000"), contains("[Điều chỉnh kỳ đã khoá 2026-09]"), eq(manager));
    }

    @Test
    @DisplayName("S8-04: Đã có chỉ tiêu cùng tháng cùng đối tượng -> 409")
    void create_duplicate_conflict() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(repUser));
        when(targetRepository.existsByPeriodMonthAndTargetKey(LocalDate.of(2026, 10, 1), "REP:7")).thenReturn(true);

        assertThatThrownBy(() -> service.create(repRequest("2026-10", "1000"), manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "TARGET_EXISTS");
        verify(targetRepository, never()).save(any());
    }

    @Test
    @DisplayName("S8-04: Người được chọn không phải NV kinh doanh / bị khoá -> 400")
    void create_invalidRep() {
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8, "ACTIVE", RoleName.ROLE_WAREHOUSE)));
        assertThatThrownBy(() -> service.create(SalesTargetCreateRequest.builder().period("2026-10").targetType("REP")
                .salesRepId(8L).targetAmount(BigDecimal.TEN).build(), manager))
                .hasFieldOrPropertyWithValue("code", "NOT_SALES_REP");

        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9, "LOCKED", RoleName.ROLE_SALES_REP)));
        assertThatThrownBy(() -> service.create(SalesTargetCreateRequest.builder().period("2026-10").targetType("REP")
                .salesRepId(9L).targetAmount(BigDecimal.TEN).build(), manager))
                .hasFieldOrPropertyWithValue("code", "SALES_REP_INACTIVE");
    }

    @Test
    @DisplayName("S8-04: Tháng sai định dạng / loại lạ / số tiền <= 0 hoặc lẻ quá 2 số -> 400")
    void create_invalidInput() {
        assertThatThrownBy(() -> service.create(repRequest("10/2026", "1000"), manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_PERIOD");
        assertThatThrownBy(() -> service.create(repRequest("2026-13", "1000"), manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_PERIOD");
        assertThatThrownBy(() -> service.create(SalesTargetCreateRequest.builder().period("2026-10").targetType("TEAM")
                .targetAmount(BigDecimal.TEN).build(), manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_TARGET_TYPE");
        assertThatThrownBy(() -> service.create(repRequest("2026-10", "0"), manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_TARGET_AMOUNT");
        assertThatThrownBy(() -> service.create(repRequest("2026-10", "100.555"), manager))
                .hasFieldOrPropertyWithValue("code", "INVALID_TARGET_AMOUNT");
        verifyNoInteractions(targetRepository, userRepository);
    }

    @Test
    @DisplayName("S8-04: NV kinh doanh / kế toán không quản lý chỉ tiêu được -> 403")
    void manage_wrongRole_forbidden() {
        assertThatThrownBy(() -> service.create(repRequest("2026-10", "1000"), rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "SALES_TARGET_NOT_ALLOWED");
        assertThatThrownBy(() -> service.list("2026-10", null, actor(40, "ROLE_ACCOUNTANT")))
                .hasFieldOrPropertyWithValue("code", "SALES_TARGET_NOT_ALLOWED");
        verifyNoInteractions(targetRepository);
    }

    // ======================= SỬA / XOÁ =======================

    @Test
    @DisplayName("S8-04: Sửa chỉ tiêu bắt buộc lý do; ghi nhật ký giá trị trước / sau")
    void update_requiresReason_andAudits() {
        assertThatThrownBy(() -> service.update(5L, SalesTargetUpdateRequest.builder()
                .targetAmount(new BigDecimal("600")).reason("  ").build(), manager))
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");
        verifyNoInteractions(targetRepository);

        SalesTarget t = target(5, "2026-10", "500");
        when(targetRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(t));
        when(targetRepository.save(t)).thenReturn(t);

        service.update(5L, SalesTargetUpdateRequest.builder().targetAmount(new BigDecimal("600"))
                .reason(" Mở thêm 3 đại lý ").build(), manager);

        assertThat(t.getTargetAmount()).isEqualByComparingTo("600");
        assertThat(t.getUpdatedByUsername()).isEqualTo(manager.getUsername());
        verify(auditLogService).record(eq(AuditModule.SALES_TARGET), eq("UPDATE_SALES_TARGET"), eq("SALES_TARGET"),
                eq(5L), eq("REP:7@2026-10"), eq("500"), eq("600"), eq("Mở thêm 3 đại lý"), eq(manager));
    }

    @Test
    @DisplayName("S8-04: Xoá chỉ tiêu tháng hiện tại có lý do -> xoá và ghi nhật ký")
    void delete_currentMonth() {
        SalesTarget t = target(5, "2026-10", "500");
        when(targetRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(t));

        service.delete(5L, "Nhập nhầm người", manager);

        verify(targetRepository).delete(t);
        verify(auditLogService).record(eq(AuditModule.SALES_TARGET), eq("DELETE_SALES_TARGET"), eq("SALES_TARGET"),
                eq(5L), anyString(), eq("500"), isNull(), contains("Nhập nhầm người"), eq(manager));
    }

    // ======================= TIẾN ĐỘ =======================

    @Test
    @DisplayName("S8-04: QL xem tiến độ: doanh số thực tế, % hoàn thành, còn thiếu theo từng chỉ tiêu")
    void list_withProgress() {
        SalesTarget t = target(5, "2026-10", "1000000");
        Region north = region(2, "ACTIVE");
        SalesTarget r = SalesTarget.builder().id(6L).periodMonth(LocalDate.of(2026, 10, 1))
                .targetType(SalesTarget.TYPE_REGION).targetKey("REGION:2").region(north)
                .targetAmount(new BigDecimal("4000000")).build();
        when(targetRepository.findByPeriod(LocalDate.of(2026, 10, 1))).thenReturn(List.of(t, r));
        when(targetRepository.sumSalesByRep(any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{7L, new BigDecimal("1250000.00")}));
        when(targetRepository.sumSalesByRegion(anyList(), any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, new BigDecimal("1000000.00")}));

        List<SalesTargetResponse> res = service.list("2026-10", null, manager);

        assertThat(res).hasSize(2);
        assertThat(res.get(0).actualAmount()).isEqualByComparingTo("1250000");
        assertThat(res.get(0).achievementPercent()).isEqualByComparingTo("125.0");
        assertThat(res.get(0).remainingAmount()).isEqualByComparingTo("0");
        assertThat(res.get(0).locked()).isFalse();
        assertThat(res.get(1).achievementPercent()).isEqualByComparingTo("25.0");
        assertThat(res.get(1).remainingAmount()).isEqualByComparingTo("3000000");
    }

    @Test
    @DisplayName("S8-04: Lọc theo loại; tháng đã qua đánh dấu đã khoá; không có chỉ tiêu thì không truy vấn doanh số")
    void list_filterTypeAndLocked() {
        when(targetRepository.findByPeriod(LocalDate.of(2026, 9, 1))).thenReturn(List.of(target(5, "2026-09", "100")));

        assertThat(service.list("2026-09", "REGION", manager)).isEmpty();
        List<SalesTargetResponse> reps = service.list("2026-09", "REP", manager);
        assertThat(reps).hasSize(1);
        assertThat(reps.get(0).locked()).isTrue();
        assertThat(reps.get(0).actualAmount()).isEqualByComparingTo("0");
        verify(targetRepository, never()).sumSalesByRegion(any(), any(), any(), any());
    }

    @Test
    @DisplayName("S8-04: NV kinh doanh xem tiến độ của mình: giữa tháng, đạt 40%, mức nên đạt đến hôm nay theo ngày")
    void myProgress_midMonth() {
        when(targetRepository.findByPeriodMonthAndTargetKey(LocalDate.of(2026, 10, 1), "REP:7"))
                .thenReturn(Optional.of(target(5, "2026-10", "3100000")));
        when(targetRepository.sumSalesOfRep(anyList(), eq(LocalDate.of(2026, 10, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 11, 1).atStartOfDay()), eq(7L))).thenReturn(new BigDecimal("1240000"));

        MySalesProgressResponse res = service.myProgress(null, rep);

        assertThat(res.period()).isEqualTo("2026-10");
        assertThat(res.actualAmount()).isEqualByComparingTo("1240000");
        assertThat(res.achievementPercent()).isEqualByComparingTo("40.0");
        assertThat(res.remainingAmount()).isEqualByComparingTo("1860000");
        assertThat(res.daysInMonth()).isEqualTo(31);
        assertThat(res.daysElapsed()).isEqualTo(15);
        // 3.100.000 x 15 / 31 = 1.500.000
        assertThat(res.expectedToDateAmount()).isEqualByComparingTo("1500000");
    }

    @Test
    @DisplayName("S8-04: Tháng chưa được giao chỉ tiêu -> vẫn xem được doanh số, chỉ tiêu / % để trống")
    void myProgress_noTarget() {
        when(targetRepository.findByPeriodMonthAndTargetKey(LocalDate.of(2026, 11, 1), "REP:7")).thenReturn(Optional.empty());
        when(targetRepository.sumSalesOfRep(anyList(), any(), any(), eq(7L))).thenReturn(null);

        MySalesProgressResponse res = service.myProgress("2026-11", rep);

        assertThat(res.targetAmount()).isNull();
        assertThat(res.achievementPercent()).isNull();
        assertThat(res.actualAmount()).isEqualByComparingTo("0");
        assertThat(res.daysElapsed()).isZero();
    }
}
