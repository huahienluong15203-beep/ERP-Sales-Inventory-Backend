package com.erp.backend.service;

import com.erp.backend.entity.Customer;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * S3-06 & S5-01: Phạm vi xem / thao tác đại lý & đơn hàng theo vai trò (kiểm ở server).
 * - ADMIN, SALES_MANAGER, ACCOUNTANT: thấy toàn bộ đại lý / đơn hàng.
 * - SALES_REP: chỉ thấy đại lý / đơn hàng mình phụ trách chính.
 * - ROLE_CUSTOMER: đại lý chỉ thấy dữ liệu và đơn hàng của chính mình (S5-01).
 * - Vai trò khác: từ chối.
 */
public final class CustomerAccess {

    static final Set<String> FULL_ACCESS_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_ACCOUNTANT");
    public static final String SALES_REP_ROLE = "ROLE_SALES_REP";
    public static final String CUSTOMER_ROLE = "ROLE_CUSTOMER";

    private CustomerAccess() {
    }

    public static boolean hasFullAccess(UserDetailsImpl actor) {
        return hasAnyRole(actor, FULL_ACCESS_ROLES);
    }

    public static boolean isCustomer(UserDetailsImpl actor) {
        return hasAnyRole(actor, Set.of(CUSTOMER_ROLE));
    }

    public static boolean isSalesRep(UserDetailsImpl actor) {
        return hasAnyRole(actor, Set.of(SALES_REP_ROLE));
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
        if (isCustomer(actor)) {
            return null; // Đại lý lọc theo customerId riêng chứ không lọc theo salesRepId
        }
        throw forbidden("Bạn không có quyền xem danh sách đại lý");
    }

    /**
     * S5-01: Tìm đối tượng Customer tương ứng với tài khoản đại lý đăng nhập.
     */
    public static Customer resolveCustomerForActor(UserDetailsImpl actor, CustomerRepository customerRepository) {
        if (actor == null || customerRepository == null) {
            return null;
        }
        return customerRepository.findByUser_Id(actor.getId())
                .or(() -> {
                    if (actor.getEmail() != null) {
                        return customerRepository.findByEmailIgnoreCase(actor.getEmail());
                    }
                    return java.util.Optional.empty();
                })
                .or(() -> {
                    if (actor.getUsername() != null) {
                        return customerRepository.findByCodeIgnoreCase(actor.getUsername());
                    }
                    return java.util.Optional.empty();
                })
                .orElse(null);
    }

    /**
     * Chặn nhân viên kinh doanh xem / sửa đại lý không do mình phụ trách,
     * và chặn đại lý xem đơn / thông tin của đại lý khác (S5-01).
     * Trả 404 (thay vì 403) để không lộ thông tin đại lý của người khác.
     */
    public static void checkCanAccess(UserDetailsImpl actor, Customer customer) {
        if (hasFullAccess(actor)) {
            return;
        }
        if (isCustomer(actor)) {
            boolean isSelf = (customer.getUser() != null && actor.getId().equals(customer.getUser().getId()))
                    || (customer.getEmail() != null && customer.getEmail().equalsIgnoreCase(actor.getEmail()))
                    || (customer.getCode() != null && customer.getCode().equalsIgnoreCase(actor.getUsername()));
            if (!isSelf) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND",
                        "Không tìm thấy đại lý hoặc bạn không có quyền truy cập", null);
            }
            return;
        }
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

    public static void checkCanAccess(UserDetailsImpl actor, Customer customer, CustomerRepository customerRepository) {
        if (hasFullAccess(actor)) {
            return;
        }
        if (isCustomer(actor)) {
            Customer current = resolveCustomerForActor(actor, customerRepository);
            if (current == null || !current.getId().equals(customer.getId())) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND",
                        "Không tìm thấy đại lý hoặc bạn không có quyền truy cập", null);
            }
            return;
        }
        checkCanAccess(actor, customer);
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
