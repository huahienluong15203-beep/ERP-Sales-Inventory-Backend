package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * S8-04: Chỉ tiêu doanh số tháng theo NV kinh doanh và theo khu vực.
 * - QL kinh doanh / Admin: đặt, sửa, xoá chỉ tiêu tháng hiện tại / tương lai; xem tiến độ mọi chỉ tiêu.
 * - Kỳ đã qua bị khoá. Sửa / xoá bắt buộc lý do; mọi thay đổi ghi nhật ký trước / sau.
 * - NV kinh doanh xem tiến độ của chính mình, tính theo thời gian thực (mỗi lần xem tính lại từ đơn hàng).
 * - Doanh số = tổng tiền các đơn từ Đã duyệt trở đi theo tháng duyệt (cùng cách tính công nợ hiện tại).
 */
@Service
@RequiredArgsConstructor
public class SalesTargetService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Set<String> MANAGE_ROLES = Set.of("ROLE_SALES_MANAGER", "ROLE_ADMIN");
    static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
    static final int MAX_TEXT = 500;
    /** Đơn được tính doanh số: đã duyệt trở đi (giống trạng thái tính công nợ). */
    static final List<String> SALES_STATUSES = SalesOrder.OUTSTANDING_DEBT_STATUSES;

    private final SalesTargetRepository targetRepository;
    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= XEM =======================

    /** QL kinh doanh / Admin: chỉ tiêu tháng kèm doanh số thực tế. type = REP | REGION | null (tất cả). */
    @Transactional(readOnly = true)
    public List<SalesTargetResponse> list(String period, String type, UserDetailsImpl actor) {
        requireManage(actor);
        YearMonth ym = parsePeriod(period != null ? period : currentMonth().format(PERIOD_FORMAT));
        String cleanType = StringUtils.hasText(type) ? normalizeType(type) : null;
        List<SalesTarget> targets = targetRepository.findByPeriod(ym.atDay(1)).stream()
                .filter(t -> cleanType == null || cleanType.equals(t.getTargetType()))
                .toList();

        Set<Long> repIds = new HashSet<>();
        Set<Long> regionIds = new HashSet<>();
        for (SalesTarget t : targets) {
            if (SalesTarget.TYPE_REP.equals(t.getTargetType()) && t.getSalesRep() != null) {
                repIds.add(t.getSalesRep().getId());
            } else if (SalesTarget.TYPE_REGION.equals(t.getTargetType()) && t.getRegion() != null) {
                regionIds.add(t.getRegion().getId());
            }
        }
        Map<Long, BigDecimal> byRep = repIds.isEmpty() ? new HashMap<>() : toMap(targetRepository.sumSalesByRep(
                SALES_STATUSES, ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay(), repIds));
        Map<Long, BigDecimal> byRegion = regionIds.isEmpty() ? new HashMap<>() : toMap(targetRepository.sumSalesByRegion(
                SALES_STATUSES, ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay(), regionIds));

        boolean locked = isLocked(ym);
        List<SalesTargetResponse> result = new ArrayList<>();
        for (SalesTarget t : targets) {
            BigDecimal actual = SalesTarget.TYPE_REP.equals(t.getTargetType())
                    ? byRep.getOrDefault(t.getSalesRep() != null ? t.getSalesRep().getId() : null, BigDecimal.ZERO)
                    : byRegion.getOrDefault(t.getRegion() != null ? t.getRegion().getId() : null, BigDecimal.ZERO);
            result.add(toResponse(t, actual, locked));
        }
        return result;
    }

    /** NV kinh doanh: tiến độ của chính mình trong tháng (mặc định tháng hiện tại). */
    @Transactional(readOnly = true)
    public MySalesProgressResponse myProgress(String period, UserDetailsImpl actor) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Bạn chưa đăng nhập", null);
        }
        YearMonth ym = parsePeriod(period != null ? period : currentMonth().format(PERIOD_FORMAT));
        BigDecimal target = targetRepository.findByPeriodMonthAndTargetKey(ym.atDay(1),
                        SalesTarget.keyOf(SalesTarget.TYPE_REP, actor.getId()))
                .map(SalesTarget::getTargetAmount)
                .orElse(null);
        BigDecimal actual = nz(targetRepository.sumSalesOfRep(SALES_STATUSES, ym.atDay(1).atStartOfDay(),
                ym.plusMonths(1).atDay(1).atStartOfDay(), actor.getId()));

        LocalDate today = LocalDate.now(clock);
        int days = ym.lengthOfMonth();
        int elapsed;
        if (ym.isBefore(YearMonth.from(today))) {
            elapsed = days;
        } else if (ym.isAfter(YearMonth.from(today))) {
            elapsed = 0;
        } else {
            elapsed = today.getDayOfMonth();
        }
        BigDecimal expected = target == null ? null
                : target.multiply(BigDecimal.valueOf(elapsed)).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        return new MySalesProgressResponse(ym.format(PERIOD_FORMAT), target, money(actual), percent(actual, target),
                remaining(actual, target), days, elapsed, expected);
    }

    // ======================= ĐẶT / SỬA / XOÁ =======================

    @Transactional
    public SalesTargetResponse create(SalesTargetCreateRequest req, UserDetailsImpl actor) {
        requireManage(actor);
        if (req == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dữ liệu không hợp lệ", null);
        }
        YearMonth ym = parsePeriod(req.getPeriod());
        assertOpen(ym);
        String type = normalizeType(req.getTargetType());
        BigDecimal amount = validAmount(req.getTargetAmount());
        String note = cleanText(req.getNote(), "note");

        User rep = null;
        Region region = null;
        Long refId;
        if (SalesTarget.TYPE_REP.equals(type)) {
            if (req.getSalesRepId() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SALES_REP_REQUIRED",
                        "Vui lòng chọn nhân viên kinh doanh", "salesRepId");
            }
            rep = userRepository.findById(req.getSalesRepId())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy nhân viên kinh doanh"));
            boolean isRep = rep.getRoles() != null
                    && rep.getRoles().stream().anyMatch(r -> r.getName() == RoleName.ROLE_SALES_REP);
            if (!isRep) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "NOT_SALES_REP",
                        "Người được chọn không phải nhân viên kinh doanh", "salesRepId");
            }
            if (!"ACTIVE".equalsIgnoreCase(rep.getStatus())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SALES_REP_INACTIVE",
                        "Tài khoản nhân viên kinh doanh đang bị khoá / ngừng", "salesRepId");
            }
            refId = rep.getId();
        } else {
            if (req.getRegionId() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "REGION_REQUIRED", "Vui lòng chọn khu vực", "regionId");
            }
            region = regionRepository.findById(req.getRegionId())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy khu vực"));
            if (!"ACTIVE".equalsIgnoreCase(region.getStatus())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "REGION_INACTIVE", "Khu vực đang ngừng hoạt động", "regionId");
            }
            refId = region.getId();
        }
        String key = SalesTarget.keyOf(type, refId);
        if (targetRepository.existsByPeriodMonthAndTargetKey(ym.atDay(1), key)) {
            throw BusinessException.conflict("TARGET_EXISTS",
                    "Tháng " + ym.format(PERIOD_FORMAT) + " đã có chỉ tiêu cho đối tượng này, hãy sửa chỉ tiêu cũ", "period");
        }
        SalesTarget saved = targetRepository.save(SalesTarget.builder()
                .periodMonth(ym.atDay(1))
                .targetType(type)
                .targetKey(key)
                .salesRep(rep)
                .region(region)
                .targetAmount(amount)
                .note(note)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .updatedByUsername(actor != null ? actor.getUsername() : null)
                .build());
        audit("CREATE_SALES_TARGET", saved, null, plain(amount), "Đặt chỉ tiêu " + describe(saved), actor);
        return toResponse(saved, BigDecimal.ZERO, false);
    }

    @Transactional
    public SalesTargetResponse update(Long id, SalesTargetUpdateRequest req, UserDetailsImpl actor) {
        requireManage(actor);
        if (req == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dữ liệu không hợp lệ", null);
        }
        String reason = requireReason(req.getReason());
        BigDecimal amount = validAmount(req.getTargetAmount());
        String note = cleanText(req.getNote(), "note");
        SalesTarget t = targetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chỉ tiêu"));
        assertOpen(YearMonth.from(t.getPeriodMonth()));

        String before = plain(t.getTargetAmount()) + (t.getNote() != null ? " | " + t.getNote() : "");
        t.setTargetAmount(amount);
        t.setNote(note);
        t.setUpdatedByUsername(actor != null ? actor.getUsername() : null);
        SalesTarget saved = targetRepository.save(t);
        audit("UPDATE_SALES_TARGET", saved, before, plain(amount) + (note != null ? " | " + note : ""), reason, actor);
        return toResponse(saved, null, false);
    }

    @Transactional
    public void delete(Long id, String reason, UserDetailsImpl actor) {
        requireManage(actor);
        String why = requireReason(reason);
        SalesTarget t = targetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chỉ tiêu"));
        assertOpen(YearMonth.from(t.getPeriodMonth()));
        audit("DELETE_SALES_TARGET", t, plain(t.getTargetAmount()), null, why + " (" + describe(t) + ")", actor);
        targetRepository.delete(t);
    }

    // ======================= TIỆN ÍCH =======================

    static YearMonth parsePeriod(String period) {
        if (!StringUtils.hasText(period)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PERIOD_REQUIRED", "Vui lòng chọn tháng (yyyy-MM)", "period");
        }
        try {
            YearMonth ym = YearMonth.parse(period.trim(), PERIOD_FORMAT);
            if (ym.getYear() < 2000 || ym.getYear() > 2100) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "Tháng không hợp lệ", "period");
            }
            return ym;
        } catch (DateTimeParseException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD",
                    "Tháng không hợp lệ, định dạng yyyy-MM (vd 2026-10)", "period");
        }
    }

    private YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    private boolean isLocked(YearMonth ym) {
        return ym.isBefore(currentMonth());
    }

    /** Kỳ đã qua bị khoá: không thêm / sửa / xoá chỉ tiêu. */
    private void assertOpen(YearMonth ym) {
        if (isLocked(ym)) {
            throw BusinessException.conflict("TARGET_PERIOD_LOCKED",
                    "Tháng " + ym.format(PERIOD_FORMAT) + " đã qua, chỉ tiêu đã khoá không sửa được", "period");
        }
    }

    private static String normalizeType(String type) {
        String t = type != null ? type.trim().toUpperCase(Locale.ROOT) : "";
        if (!SalesTarget.TYPE_REP.equals(t) && !SalesTarget.TYPE_REGION.equals(t)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_TYPE",
                    "Loại chỉ tiêu phải là REP (nhân viên) hoặc REGION (khu vực)", "targetType");
        }
        return t;
    }

    private static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_AMOUNT",
                    "Chỉ tiêu doanh số phải lớn hơn 0", "targetAmount");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_AMOUNT",
                    "Chỉ tiêu doanh số quá lớn", "targetAmount");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_AMOUNT",
                    "Chỉ tiêu doanh số tối đa 2 số lẻ", "targetAmount");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String requireReason(String reason) {
        String r = reason != null ? reason.trim() : "";
        if (!StringUtils.hasText(r)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Bắt buộc nhập lý do", "reason");
        }
        if (r.length() > MAX_TEXT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG", "Lý do tối đa " + MAX_TEXT + " ký tự", "reason");
        }
        return r;
    }

    private static String cleanText(String s, String field) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        if (t.length() > MAX_TEXT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TEXT_TOO_LONG", "Tối đa " + MAX_TEXT + " ký tự", field);
        }
        return t;
    }

    private static void requireManage(UserDetailsImpl actor) {
        boolean ok = actor != null && actor.getAuthorities() != null
                && actor.getAuthorities().stream().anyMatch(a -> MANAGE_ROLES.contains(a.getAuthority()));
        if (!ok) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SALES_TARGET_NOT_ALLOWED",
                    "Chỉ Quản lý kinh doanh hoặc Quản trị được quản lý chỉ tiêu", null);
        }
    }

    private static Map<Long, BigDecimal> toMap(List<Object[]> rows) {
        Map<Long, BigDecimal> m = new HashMap<>();
        if (rows == null) {
            return m;
        }
        for (Object[] r : rows) {
            if (r == null || r.length < 2 || r[0] == null) {
                continue;
            }
            Long key = ((Number) r[0]).longValue();
            BigDecimal value = r[1] instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(r[1]));
            m.put(key, value);
        }
        return m;
    }

    private SalesTargetResponse toResponse(SalesTarget t, BigDecimal actual, boolean locked) {
        return new SalesTargetResponse(t.getId(), t.getPeriodMonth().format(PERIOD_FORMAT), t.getTargetType(),
                t.getSalesRep() != null ? t.getSalesRep().getId() : null,
                t.getSalesRep() != null ? t.getSalesRep().getFullName() : null,
                t.getRegion() != null ? t.getRegion().getId() : null,
                t.getRegion() != null ? t.getRegion().getName() : null,
                t.getTargetAmount(),
                actual != null ? money(actual) : null,
                actual != null ? percent(actual, t.getTargetAmount()) : null,
                actual != null ? remaining(actual, t.getTargetAmount()) : null,
                locked, t.getNote(), t.getUpdatedAt(), t.getUpdatedByUsername());
    }

    /** % hoàn thành 1 số lẻ; chưa có chỉ tiêu -> null. */
    static BigDecimal percent(BigDecimal actual, BigDecimal target) {
        if (target == null || target.signum() <= 0) {
            return null;
        }
        return nz(actual).multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal remaining(BigDecimal actual, BigDecimal target) {
        if (target == null) {
            return null;
        }
        return target.subtract(nz(actual)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String describe(SalesTarget t) {
        String who = SalesTarget.TYPE_REP.equals(t.getTargetType())
                ? "NV " + (t.getSalesRep() != null ? t.getSalesRep().getUsername() : "?")
                : "khu vực " + (t.getRegion() != null ? t.getRegion().getCode() : "?");
        return who + " tháng " + t.getPeriodMonth().format(PERIOD_FORMAT);
    }

    private void audit(String action, SalesTarget t, String before, String after, String reason, UserDetailsImpl actor) {
        String r = reason != null && reason.length() > MAX_TEXT ? reason.substring(0, MAX_TEXT) : reason;
        auditLogService.record(AuditModule.SALES_TARGET, action, "SALES_TARGET", t.getId(), t.getTargetKey()
                + "@" + t.getPeriodMonth().format(PERIOD_FORMAT), before, after, r, actor);
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getRequest() != null) {
                attributes.getRequest().setAttribute(AuditLogInterceptor.AUDIT_LOGGED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) {
            // ngoài HTTP request thì bỏ qua
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
