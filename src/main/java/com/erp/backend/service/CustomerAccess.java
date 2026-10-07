package com.erp.backend.service;

import com.erp.backend.entity.Customer;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * S3-06: Phạm vi xem / thao tác đại lý theo vai trò (kiểm ở server).
 * - ADMIN, SALES_MANAGER, ACCOUNTANT: thấy toàn bộ đại lý.
 * - SALES_REP: chỉ thấy đại lý mình phụ trách chính.
 * - Vai trò khác: từ chối.
 */
public final class CustomerAccess {

    static final Set<String> FULL_ACCESS_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_ACCOUNTANT");
    static final String SALES_REP_ROLE = "ROLE_SALES_REP";

    private CustomerAccess() {
    }

    public static boolean hasFullAccess(UserDetailsImpl actor) {
        return hasAnyRole(actor, FULL_ACCESS_ROLES);
    }

    /**
     * Trả về id nhân viên kinh doanh mà người dùng bị giới hạn theo.
     * null = không giới hạn (thấy tất cả).
     */
    public static Long restrictedSalesRepId(UserDetailsImpl actor) {
        if (hasFullAccess(actor)) {
            return null;
        }
        if (hasAnyRole(actor, Set.of(SALES_REP_ROLE))) {
            return actor.getId();
        }
        throw forbidden("Bạn không có quyền xem danh sách đại lý");
    }

    /**
     * Chặn nhân viên kinh doanh xem / sửa đại lý không do mình phụ trách.
     * Trả 404 (thay vì 403) để không lộ thông tin đại lý của người khác,
     * và vì Frontend đang tự đăng xuất khi gặp 403.
     */
    public static void checkCanAccess(UserDetailsImpl actor, Customer customer) {
        Long restrictedId = restrictedSalesRepId(actor);
        if (restrictedId == null) {
            return;
        }
        boolean isOwner = customer.getSalesRep() != null && restrictedId.equals(customer.getSalesRep().getId());
        if (!isOwner) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND",
                    "Không tìm thấy đại lý hoặc bạn không phụ trách đại lý này", null);
        }
    }

    private static boolean hasAnyRole(UserDetailsImpl actor, Set<String> roles) {
        if (actor == null || actor.getAuthorities() == null) {
            return false;
        }
        return actor.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(roles::contains);
    }

    private static BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, "CUSTOMER_ACCESS_DENIED", message, null);
    }
}
