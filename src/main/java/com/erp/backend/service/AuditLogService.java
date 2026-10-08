package com.erp.backend.service;

import com.erp.backend.dto.audit.AuditLogResponse;
import com.erp.backend.dto.audit.CreateAuditLogEntry;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.AuditLog;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.AuditLogRepository;
import com.erp.backend.repository.AuditLogSpecifications;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * S2-04: Quản lý và ghi nhận nhật ký thao tác hệ thống.
 * Hỗ trợ ghi nhật ký độc lập (REQUIRES_NEW) để đảm bảo log không bị mất nếu có sự cố.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    /**
     * Ghi nhận 1 bản ghi nhật ký kiểm toán.
     * Sử dụng Propagation.REQUIRES_NEW để việc ghi audit log diễn ra độc lập,
     * tự động trích xuất IP và User-Agent nếu đang trong HTTP request context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(CreateAuditLogEntry entry) {
        if (entry.getModule() == null) {
            entry.setModule(AuditModule.CUSTOMER);
        }
        if (!StringUtils.hasText(entry.getAction())) {
            entry.setAction("UNKNOWN_ACTION");
        }

        // Tự động bổ sung IP & User Agent nếu chưa có
        HttpServletRequest currentReq = getCurrentHttpRequest();
        if (currentReq != null) {
            if (!StringUtils.hasText(entry.getIpAddress())) {
                entry.setIpAddress(extractClientIp(currentReq));
            }
            if (!StringUtils.hasText(entry.getUserAgent())) {
                entry.setUserAgent(currentReq.getHeader("User-Agent"));
            }
            if (!StringUtils.hasText(entry.getHttpMethod())) {
                entry.setHttpMethod(currentReq.getMethod());
            }
            if (!StringUtils.hasText(entry.getRequestUri())) {
                entry.setRequestUri(currentReq.getRequestURI());
            }
        }

        String actorAvatar = entry.getActorAvatarUrl();
        if (!StringUtils.hasText(actorAvatar) && userRepository != null) {
            if (entry.getActorId() != null) {
                actorAvatar = userRepository.findById(entry.getActorId())
                        .map(u -> StringUtils.hasText(u.getAvatarThumbnailUrl()) ? u.getAvatarThumbnailUrl() : u.getAvatarUrl())
                        .orElse(null);
            } else if (StringUtils.hasText(entry.getActorUsername())) {
                actorAvatar = userRepository.findByUsername(entry.getActorUsername())
                        .map(u -> StringUtils.hasText(u.getAvatarThumbnailUrl()) ? u.getAvatarThumbnailUrl() : u.getAvatarUrl())
                        .orElse(null);
            }
        }

        AuditLog auditLog = AuditLog.builder()
                .module(entry.getModule())
                .action(entry.getAction())
                .targetType(entry.getTargetType())
                .targetId(entry.getTargetId())
                .targetCode(entry.getTargetCode())
                .actorId(entry.getActorId())
                .actorUsername(entry.getActorUsername())
                .actorFullName(entry.getActorFullName())
                .actorAvatarUrl(actorAvatar)
                .oldValue(entry.getOldValue())
                .newValue(entry.getNewValue())
                .reason(entry.getReason())
                .ipAddress(entry.getIpAddress())
                .userAgent(entry.getUserAgent())
                .httpMethod(entry.getHttpMethod())
                .requestUri(entry.getRequestUri())
                .build();

        AuditLog saved = auditLogRepository.save(auditLog);
        log.info("AUDIT LOG [{}] Action={} Target={}:{} Actor={} Reason={}",
                saved.getModule(), saved.getAction(), saved.getTargetType(),
                saved.getTargetCode(), saved.getActorUsername(), saved.getReason());
        return saved;
    }

    /**
     * Tiện ích ghi log nhanh từ Service nghiệp vụ kèm thông tin User hiện tại.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(AuditModule module, String action, String targetType, Long targetId,
                           String targetCode, String oldValue, String newValue,
                           String reason, UserDetailsImpl actor) {
        CreateAuditLogEntry entry = CreateAuditLogEntry.builder()
                .module(module)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .targetCode(targetCode)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .actorId(actor != null ? actor.getId() : null)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorFullName(actor != null ? actor.getFullName() : null)
                .build();
        return record(entry);
    }

    /**
     * S2-04: Tra cứu và lọc nhật ký hệ thống:
     * - Từ khóa (keyword)
     * - Phân hệ (module)
     * - Loại đối tượng (targetType)
     * - Người thực hiện (actorId)
     * - Khoảng thời gian (startDate, endDate)
     * - Phân trang (page, size)
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(
            String keyword,
            AuditModule module,
            String targetType,
            Long actorId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            String action,
            int page,
            int size) {
        // Tự động hoán đổi nếu ngày bắt đầu lớn hơn ngày kết thúc để tránh lỗi truy vấn rỗng sai logic
        LocalDateTime effectiveStart = startDate;
        LocalDateTime effectiveEnd = endDate;
        if (effectiveStart != null && effectiveEnd != null && effectiveStart.isAfter(effectiveEnd)) {
            LocalDateTime temp = effectiveStart;
            effectiveStart = effectiveEnd;
            effectiveEnd = temp;
        }

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Page<AuditLog> result = auditLogRepository.findAll(
                AuditLogSpecifications.filter(keyword, module, targetType, actorId, effectiveStart, effectiveEnd, action),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id").descending())));

        return PageResponse.of(result.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public AuditLogResponse getById(Long id) {
        AuditLog log = auditLogRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy bản ghi nhật ký hệ thống"));
        return toResponse(log);
    }

    public AuditLogResponse toResponse(AuditLog a) {
        String avatarUrl = a.getActorAvatarUrl();
        if (!StringUtils.hasText(avatarUrl) && userRepository != null) {
            if (a.getActorId() != null) {
                avatarUrl = userRepository.findById(a.getActorId())
                        .map(u -> StringUtils.hasText(u.getAvatarThumbnailUrl()) ? u.getAvatarThumbnailUrl() : u.getAvatarUrl())
                        .orElse(null);
            } else if (StringUtils.hasText(a.getActorUsername())) {
                avatarUrl = userRepository.findByUsername(a.getActorUsername())
                        .map(u -> StringUtils.hasText(u.getAvatarThumbnailUrl()) ? u.getAvatarThumbnailUrl() : u.getAvatarUrl())
                        .orElse(null);
            }
        }

        return new AuditLogResponse(
                a.getId(),
                a.getModule().name(),
                a.getModule().getLabel(),
                a.getAction(),
                a.getTargetType(),
                a.getTargetId(),
                a.getTargetCode(),
                a.getActorId(),
                a.getActorUsername(),
                a.getActorFullName(),
                avatarUrl,
                a.getOldValue(),
                a.getNewValue(),
                a.getReason(),
                a.getIpAddress(),
                a.getUserAgent(),
                a.getHttpMethod(),
                a.getRequestUri(),
                a.getCreatedAt());
    }

    private static HttpServletRequest getCurrentHttpRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attributes != null ? attributes.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String extractClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xf)) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
