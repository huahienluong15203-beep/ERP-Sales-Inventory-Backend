package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.dto.portal.PortalAccountRequest;
import com.erp.backend.dto.portal.PortalMeResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.User;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * S4-10: Admin gắn / gỡ tài khoản cổng đại lý (vai trò CUSTOMER) với hồ sơ đại lý. Ghi nhật ký trước / sau.
 */
@Service
@RequiredArgsConstructor
public class PortalAccountService {

    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public PortalMeResponse link(Long customerId, PortalAccountRequest req, UserDetailsImpl actor) {
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        User before = customer.getPortalUser();
        Long userId = req != null ? req.getUserId() : null;
        User after = null;
        if (userId != null) {
            after = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                            "Không tìm thấy tài khoản", "userId"));
            boolean isCustomerRole = after.getRoles().stream().anyMatch(r -> r.getName() == RoleName.ROLE_CUSTOMER);
            if (!isCustomerRole) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "NOT_CUSTOMER_ACCOUNT",
                        "Tài khoản " + after.getUsername() + " không có vai trò Đại lý", "userId");
            }
            if (!"ACTIVE".equalsIgnoreCase(after.getStatus())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_ACTIVE",
                        "Tài khoản " + after.getUsername() + " đang bị khoá / ngừng", "userId");
            }
            final User target = after;
            customerRepository.findByPortalUser_Id(userId)
                    .filter(other -> !other.getId().equals(customer.getId()))
                    .ifPresent(other -> {
                        throw BusinessException.conflict("PORTAL_ACCOUNT_IN_USE", "Tài khoản " + target.getUsername()
                                + " đã gắn với đại lý " + other.getName() + " (" + other.getCode() + ")", "userId");
                    });
        }
        customer.setPortalUser(after);
        customerRepository.save(customer);

        String reason = req != null && StringUtils.hasText(req.getReason()) ? req.getReason().trim()
                : (after != null ? "Gắn tài khoản cổng đại lý" : "Gỡ tài khoản cổng đại lý");
        auditLogService.record(AuditModule.CUSTOMER, after != null ? "LINK_PORTAL_ACCOUNT" : "UNLINK_PORTAL_ACCOUNT",
                "CUSTOMER", customer.getId(), customer.getCode(),
                before != null ? before.getUsername() : null, after != null ? after.getUsername() : null, reason, actor);
        markAuditLogged();

        return new PortalMeResponse(customer.getId(), customer.getCode(), customer.getName(),
                customer.getCustomerGroup() != null ? customer.getCustomerGroup().name() : null,
                customer.getCustomerGroup() != null ? customer.getCustomerGroup().getLabel() : null,
                customer.getSalesRep() != null ? customer.getSalesRep().getFullName() : null,
                customer.getRegion() != null ? customer.getRegion().getName() : null,
                customer.getPhone(), customer.getAddress(), customer.isTransactionLocked(), customer.getStatus());
    }

    private static void markAuditLogged() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getRequest() != null) {
                attributes.getRequest().setAttribute(AuditLogInterceptor.AUDIT_LOGGED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) {
            // ngoài HTTP request (test) thì bỏ qua
        }
    }
}
