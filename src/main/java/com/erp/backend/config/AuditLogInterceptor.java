package com.erp.backend.config;

import com.erp.backend.dto.audit.CreateAuditLogEntry;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Middleware (HandlerInterceptor) ghi lại mọi thao tác trên:
 * - Tồn kho (inventory, stock, warehouses)
 * - Giá (pricing, prices, cost-price)
 * - Hạn mức công nợ (debt-limit, transaction-lock, debts)
 * - Hóa đơn & Đơn hàng (invoices, orders)
 *
 * Chỉ ghi nhận các phương thức thay đổi dữ liệu: POST, PUT, PATCH, DELETE.
 * Tự động bỏ qua nếu tầng Service đã ghi nhận chi tiết (flag ERP_AUDIT_LOGGED).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogInterceptor implements HandlerInterceptor {

    public static final String AUDIT_LOGGED_ATTR = "ERP_AUDIT_LOGGED";

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditLogService auditLogService;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String method = request.getMethod().toUpperCase();
        if (!MUTATING_METHODS.contains(method)) {
            return;
        }

        // Bỏ qua nếu thao tác thất bại (chỉ ghi log thao tác thành công, trừ khi cần)
        int status = response.getStatus();
        if (status < 200 || status >= 400) {
            return;
        }

        // Nếu service đã ghi log chi tiết (có oldValue, newValue, reason) thì không ghi đúp
        if (Boolean.TRUE.equals(request.getAttribute(AUDIT_LOGGED_ATTR))) {
            return;
        }

        String uri = request.getRequestURI();
        // POST .../preview chỉ tính thử (vd /api/orders/preview gọi mỗi lần gõ đơn), không thay đổi dữ liệu -> không ghi nhật ký
        if (isReadOnlyPost(uri)) {
            return;
        }
        AuditModule module = resolveModule(uri);
        if (module == null) {
            return;
        }

        try {
            UserDetailsImpl actor = getCurrentActor();
            String action = resolveActionName(method, uri, module);

            CreateAuditLogEntry entry = CreateAuditLogEntry.builder()
                    .module(module)
                    .action(action)
                    .targetType(module.name())
                    .httpMethod(method)
                    .requestUri(uri)
                    .ipAddress(AuditLogService.extractClientIp(request))
                    .userAgent(request.getHeader("User-Agent"))
                    .actorId(actor != null ? actor.getId() : null)
                    .actorUsername(actor != null ? actor.getUsername() : "anonymous")
                    .actorFullName(actor != null ? actor.getFullName() : "Anonymous User")
                    .reason("Thao tác HTTP " + method + " qua API")
                    .build();

            auditLogService.record(entry);
        } catch (Exception e) {
            log.error("Lỗi khi ghi middleware audit log cho URI {}: {}", uri, e.getMessage());
        }
    }

    static boolean isReadOnlyPost(String uri) {
        String lower = uri.toLowerCase();
        return lower.endsWith("/preview") || lower.endsWith("/preview/");
    }

    private AuditModule resolveModule(String uri) {
        String lower = uri.toLowerCase();
        if (lower.contains("/debt-limit") || lower.contains("/transaction-lock") || lower.contains("/debt")) {
            return AuditModule.DEBT_LIMIT;
        }
        if (lower.contains("/inventory") || lower.contains("/stock") || lower.contains("/warehouses")) {
            return AuditModule.INVENTORY;
        }
        if (lower.contains("/pricing") || lower.contains("/prices") || lower.contains("/cost-price")) {
            return AuditModule.PRICING;
        }
        if (lower.contains("/invoices") || lower.contains("/orders")) {
            return AuditModule.INVOICE;
        }
        return null;
    }

    private String resolveActionName(String method, String uri, AuditModule module) {
        String base = switch (method) {
            case "POST" -> "CREATE_";
            case "PUT" -> "UPDATE_";
            case "PATCH" -> "MODIFY_";
            case "DELETE" -> "DELETE_";
            default -> "EXECUTE_";
        };
        return base + module.name();
    }

    private UserDetailsImpl getCurrentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl userDetails) {
            return userDetails;
        }
        return null;
    }
}
