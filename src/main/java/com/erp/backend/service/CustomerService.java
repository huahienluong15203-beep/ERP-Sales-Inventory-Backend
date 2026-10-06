package com.erp.backend.service;

import com.erp.backend.dto.customer.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.dto.user.RefItem;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.entity.AuditModule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * S3-03: Quản lý hồ sơ đại lý.
 * S3-05: Khai báo hạn mức công nợ và số ngày nợ tối đa cho phép.
 * S3-06: Phân công nhân viên phụ trách, chuyển giao hàng loạt, lịch sử phân công.
 * S3-07: Khóa / mở giao dịch đại lý để kiểm soát rủi ro công nợ.
 * S3-08: Tìm kiếm và lọc danh sách đại lý.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";

    private final CustomerRepository customerRepository;
    private final CustomerAssignmentHistoryRepository historyRepository;
    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    // ======================= S3-08: TÌM KIẾM / XEM =======================

    /**
     * Nhân viên kinh doanh luôn chỉ thấy đại lý của mình (bỏ qua bộ lọc người phụ trách gửi lên).
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> search(String keyword, Long regionId, CustomerGroup customerGroup,
                                                 Long salesRepId, String status, int page, int size,
                                                 UserDetailsImpl actor) {
        return search(keyword, regionId, customerGroup, salesRepId, status, null, page, size, actor);
    }

    /**
     * S3-07: thêm bộ lọc khoá giao dịch (true/false/null) để lọc trên toàn bộ DB thay vì chỉ trang đang xem.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> search(String keyword, Long regionId, CustomerGroup customerGroup,
                                                 Long salesRepId, String status, Boolean transactionLocked,
                                                 int page, int size, UserDetailsImpl actor) {
        Long restrictedId = CustomerAccess.restrictedSalesRepId(actor);
        Long effectiveSalesRepId = restrictedId != null ? restrictedId : salesRepId;

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Page<Customer> result = customerRepository.findAll(
                CustomerSpecifications.search(keyword, regionId, customerGroup, effectiveSalesRepId, status,
                        transactionLocked),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id").descending())));

        return PageResponse.of(result.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public CustomerResponse getById(Long id, UserDetailsImpl actor) {
        Customer customer = findCustomer(id);
        CustomerAccess.checkCanAccess(actor, customer);
        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public CustomerFormOptionsResponse getFormOptions() {
        List<OptionItem> groups = Arrays.stream(CustomerGroup.values())
                .map(g -> new OptionItem(g.name(), g.getLabel()))
                .toList();
        List<OptionItem> statuses = List.of(
                new OptionItem(ACTIVE, "Đang giao dịch"),
                new OptionItem(INACTIVE, "Ngừng giao dịch"));
        List<RefItem> regions = regionRepository.findByStatusOrderByNameAsc(ACTIVE).stream()
                .map(r -> new RefItem(r.getId(), r.getCode(), r.getName()))
                .toList();
        List<RefItem> salesReps = userRepository
                .findDistinctByRoles_NameAndStatusOrderByFullNameAsc(RoleName.ROLE_SALES_REP, ACTIVE).stream()
                .map(this::toUserRef)
                .toList();
        return new CustomerFormOptionsResponse(groups, statuses, regions, salesReps);
    }

    // ======================= S3-03: TẠO / SỬA HỒ SƠ =======================

    @Transactional
    public CustomerResponse create(CreateCustomerRequest req, UserDetailsImpl actor) {
        String code = req.getCode().trim().toUpperCase();
        String taxCode = blankToNull(req.getTaxCode());

        if (customerRepository.existsByCodeIgnoreCase(code)) {
            throw BusinessException.conflict("CUSTOMER_CODE_EXISTS", "Mã đại lý '" + code + "' đã tồn tại", "code");
        }
        if (taxCode != null && customerRepository.existsByTaxCode(taxCode)) {
            throw BusinessException.conflict("TAX_CODE_EXISTS", "Mã số thuế '" + taxCode + "' đã được dùng cho đại lý khác", "taxCode");
        }

        Customer customer = Customer.builder()
                .code(code)
                .status(ACTIVE)
                .build();
        applyProfile(customer, req, taxCode);

        User salesRep = req.getSalesRepId() == null ? null : loadActiveSalesRep(req.getSalesRepId());
        customer.setSalesRep(salesRep);

        Customer saved = customerRepository.save(customer);

        if (salesRep != null) {
            recordHistory(saved, null, salesRep, CustomerAssignmentHistory.TYPE_CREATE, "Gán khi tạo đại lý", actor);
        }
        return toResponse(saved);
    }

    /** Sửa hồ sơ. Không đổi được mã đại lý và người phụ trách ở đây. */
    @Transactional
    public CustomerResponse update(Long id, CustomerProfileRequest req, UserDetailsImpl actor) {
        Customer customer = findCustomer(id);
        CustomerAccess.checkCanAccess(actor, customer);

        String taxCode = blankToNull(req.getTaxCode());
        if (taxCode != null && customerRepository.existsByTaxCodeAndIdNot(taxCode, id)) {
            throw BusinessException.conflict("TAX_CODE_EXISTS", "Mã số thuế '" + taxCode + "' đã được dùng cho đại lý khác", "taxCode");
        }

        applyProfile(customer, req, taxCode);
        return toResponse(customerRepository.save(customer));
    }

    /**
     * Đại lý không xoá cứng: chỉ chuyển "Ngừng giao dịch" (bắt buộc lý do) hoặc "Đang giao dịch" trở lại.
     */
    @Transactional
    public CustomerResponse changeStatus(Long id, ChangeCustomerStatusRequest req, UserDetailsImpl actor) {
        Customer customer = findCustomer(id);
        CustomerAccess.checkCanAccess(actor, customer);

        String newStatus = req.getStatus().trim().toUpperCase();
        String reason = blankToNull(req.getReason());

        if (INACTIVE.equals(newStatus) && reason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Vui lòng nhập lý do ngừng giao dịch", "reason");
        }
        if (newStatus.equals(customer.getStatus())) {
            throw BusinessException.badRequest("STATUS_UNCHANGED",
                    INACTIVE.equals(newStatus) ? "Đại lý đã ở trạng thái ngừng giao dịch" : "Đại lý đang giao dịch");
        }

        customer.setStatus(newStatus);
        customer.setStatusReason(INACTIVE.equals(newStatus) ? reason : null);
        return toResponse(customerRepository.save(customer));
    }

    // ======================= S3-05: HẠN MỨC CÔNG NỢ & SỐ NGÀY NỢ =======================

    /**
     * S3-05: Khai báo hạn mức tiền tối đa và số ngày nợ tối đa.
     * Bắt buộc nhập lý do khi thay đổi để ghi nhật ký hệ thống.
     * Chỉ Kế toán công nợ, Quản lý kinh doanh và Admin mới có quyền thực hiện.
     */
    @Transactional
    public CustomerResponse updateDebtLimit(Long id, UpdateDebtLimitRequest req, UserDetailsImpl actor) {
        checkDebtManagementAccess(actor);

        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));

        String reason = blankToNull(req.getReason());
        if (reason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Bắt buộc nhập lý do khi thay đổi hạn mức công nợ", "reason");
        }

        BigDecimal oldCreditLimit = customer.getCreditLimit() != null ? customer.getCreditLimit() : BigDecimal.ZERO;
        Integer oldMaxDebtDays = customer.getMaxDebtDays() != null ? customer.getMaxDebtDays() : 30;

        if (oldCreditLimit.compareTo(req.getCreditLimit()) == 0 && oldMaxDebtDays.equals(req.getMaxDebtDays())) {
            throw BusinessException.badRequest("LIMIT_UNCHANGED",
                    "Hạn mức tiền và số ngày nợ không có thay đổi so với hiện tại");
        }

        customer.setCreditLimit(req.getCreditLimit());
        customer.setMaxDebtDays(req.getMaxDebtDays());
        Customer saved = customerRepository.save(customer);

        // Ghi nhật ký kiểm toán hệ thống (S2-04, S3-05)
        String oldValue = String.format("{\"creditLimit\":%s,\"maxDebtDays\":%d}",
                oldCreditLimit.toPlainString(), oldMaxDebtDays);
        String newValue = String.format("{\"creditLimit\":%s,\"maxDebtDays\":%d}",
                req.getCreditLimit().toPlainString(), req.getMaxDebtDays());
        auditLogService.record(
                AuditModule.DEBT_LIMIT,
                "UPDATE_DEBT_LIMIT",
                "CUSTOMER",
                saved.getId(),
                saved.getCode(),
                oldValue,
                newValue,
                reason,
                actor);
        markAuditLogged();

        return toResponse(saved);
    }

    // ======================= S3-07: KHÓA / MỞ GIAO DỊCH ĐẠI LÝ =======================

    /**
     * S3-07: Khóa hoặc mở giao dịch với một đại lý.
     * - Bắt buộc nhập lý do khi khóa hoặc mở.
     * - Đại lý bị khóa không tạo được đơn mới trên mọi nền tảng.
     * - Ghi lại nhật ký kiểm toán hệ thống (S2-04).
     */
    @Transactional
    public CustomerResponse setTransactionLock(Long id, CustomerTransactionLockRequest req, UserDetailsImpl actor) {
        checkDebtManagementAccess(actor);

        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));

        String reason = blankToNull(req.getReason());
        if (reason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Bắt buộc nhập lý do khi khóa hoặc mở giao dịch đại lý", "reason");
        }

        boolean targetLocked = Boolean.TRUE.equals(req.getLocked());
        if (customer.isTransactionLocked() == targetLocked) {
            throw BusinessException.badRequest("LOCK_STATUS_UNCHANGED",
                    targetLocked ? "Đại lý đã ở trạng thái bị khóa giao dịch" : "Đại lý đang ở trạng thái mở giao dịch");
        }

        customer.setTransactionLocked(targetLocked);
        customer.setTransactionLockReason(reason);
        customer.setTransactionLockedAt(targetLocked ? LocalDateTime.now() : null);
        Customer saved = customerRepository.save(customer);

        // Ghi nhật ký kiểm toán hệ thống (S2-04, S3-07)
        String action = targetLocked ? "LOCK_TRANSACTION" : "UNLOCK_TRANSACTION";
        String oldValue = String.format("{\"transactionLocked\":%b}", !targetLocked);
        String newValue = String.format("{\"transactionLocked\":%b}", targetLocked);
        auditLogService.record(
                AuditModule.DEBT_LIMIT,
                action,
                "CUSTOMER",
                saved.getId(),
                saved.getCode(),
                oldValue,
                newValue,
                reason,
                actor);
        markAuditLogged();

        return toResponse(saved);
    }

    /**
     * S3-07 & S4-02: Kiểm tra đại lý có đủ điều kiện tạo đơn hàng mới hay không.
     * Chặn tạo đơn mới trên mọi nền tảng nếu bị khóa giao dịch hoặc ngừng hoạt động.
     */
    @Transactional(readOnly = true)
    public OrderCreationCheckResponse checkOrderCreation(Long id) {
        Customer customer = findCustomer(id);
        if (customer.isTransactionLocked()) {
            return new OrderCreationCheckResponse(
                    customer.getId(),
                    customer.getCode(),
                    customer.getName(),
                    false,
                    "Đại lý đang bị khóa giao dịch: " + customer.getTransactionLockReason() + ". Chặn tạo đơn mới trên mọi nền tảng.",
                    customer.getCreditLimit(),
                    customer.getMaxDebtDays(),
                    true);
        }
        if (!ACTIVE.equalsIgnoreCase(customer.getStatus())) {
            return new OrderCreationCheckResponse(
                    customer.getId(),
                    customer.getCode(),
                    customer.getName(),
                    false,
                    "Đại lý đã ngừng giao dịch (" + (customer.getStatusReason() != null ? customer.getStatusReason() : "INACTIVE") + ").",
                    customer.getCreditLimit(),
                    customer.getMaxDebtDays(),
                    false);
        }
        return new OrderCreationCheckResponse(
                customer.getId(),
                customer.getCode(),
                customer.getName(),
                true,
                null,
                customer.getCreditLimit(),
                customer.getMaxDebtDays(),
                false);
    }

    /**
     * Nghiệp vụ kiểm tra bắt buộc ném lỗi nếu bị chặn tạo đơn (dùng cho các service tạo đơn hàng).
     */
    public void assertCanCreateOrder(Customer customer) {
        if (customer.isTransactionLocked()) {
            throw BusinessException.forbidden("CUSTOMER_TRANSACTION_LOCKED",
                    "Đại lý " + customer.getName() + " (" + customer.getCode() + ") đang bị khóa giao dịch: "
                            + customer.getTransactionLockReason() + ". Chặn tạo đơn mới trên mọi nền tảng.");
        }
        if (!ACTIVE.equalsIgnoreCase(customer.getStatus())) {
            throw BusinessException.badRequest("CUSTOMER_INACTIVE",
                    "Đại lý " + customer.getName() + " (" + customer.getCode() + ") đã ngừng giao dịch.");
        }
    }

    // ======================= S3-06: PHÂN CÔNG NGƯỜI PHỤ TRÁCH =======================

    @Transactional
    public CustomerResponse assignSalesRep(Long id, AssignSalesRepRequest req, UserDetailsImpl actor) {
        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));

        User newRep = loadActiveSalesRep(req.getSalesRepId());
        User oldRep = customer.getSalesRep();
        if (oldRep != null && oldRep.getId().equals(newRep.getId())) {
            throw BusinessException.badRequest("SAME_SALES_REP", "Nhân viên này đang phụ trách đại lý rồi");
        }

        customer.setSalesRep(newRep);
        Customer saved = customerRepository.save(customer);
        recordHistory(saved, oldRep, newRep, CustomerAssignmentHistory.TYPE_ASSIGN, blankToNull(req.getReason()), actor);
        return toResponse(saved);
    }

    /**
     * Chuyển giao hàng loạt (vd: nhân viên nghỉ việc). Mỗi đại lý được chuyển đều ghi một dòng lịch sử.
     * Người bàn giao có thể đã bị khoá tài khoản, nên chỉ kiểm tra người nhận phải đang hoạt động.
     */
    @Transactional
    public TransferCustomersResponse transfer(TransferCustomersRequest req, UserDetailsImpl actor) {
        if (req.getFromSalesRepId().equals(req.getToSalesRepId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SAME_SALES_REP",
                    "Nhân viên nhận bàn giao phải khác nhân viên bàn giao", "toSalesRepId");
        }

        User fromRep = userRepository.findById(req.getFromSalesRepId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND",
                        "Không tìm thấy nhân viên bàn giao", "fromSalesRepId"));
        User toRep = loadActiveSalesRep(req.getToSalesRepId());

        List<Customer> customers = req.getRegionId() == null
                ? customerRepository.findBySalesRepIdForUpdate(fromRep.getId())
                : customerRepository.findBySalesRepIdAndRegionIdForUpdate(fromRep.getId(), req.getRegionId());

        if (customers.isEmpty()) {
            throw BusinessException.badRequest("NO_CUSTOMERS_TO_TRANSFER",
                    "Nhân viên " + fromRep.getFullName() + " không phụ trách đại lý nào" +
                            (req.getRegionId() == null ? "" : " trong khu vực đã chọn"));
        }

        String reason = req.getReason().trim();
        List<CustomerAssignmentHistory> histories = new ArrayList<>();
        User changedBy = currentUserRef(actor);
        for (Customer customer : customers) {
            customer.setSalesRep(toRep);
            histories.add(CustomerAssignmentHistory.builder()
                    .customer(customer)
                    .fromSalesRep(fromRep)
                    .toSalesRep(toRep)
                    .changedBy(changedBy)
                    .changeType(CustomerAssignmentHistory.TYPE_TRANSFER)
                    .reason(reason)
                    .build());
        }
        customerRepository.saveAll(customers);
        historyRepository.saveAll(histories);

        return new TransferCustomersResponse(customers.size(),
                "Đã chuyển " + customers.size() + " đại lý từ " + fromRep.getFullName() + " sang " + toRep.getFullName());
    }

    @Transactional(readOnly = true)
    public List<AssignmentHistoryResponse> getAssignmentHistory(Long id, UserDetailsImpl actor) {
        Customer customer = findCustomer(id);
        CustomerAccess.checkCanAccess(actor, customer);
        return historyRepository.findByCustomer_IdOrderByChangedAtDescIdDesc(id).stream()
                .map(h -> new AssignmentHistoryResponse(
                        h.getId(),
                        h.getChangeType(),
                        toUserRef(h.getFromSalesRep()),
                        toUserRef(h.getToSalesRep()),
                        toUserRef(h.getChangedBy()),
                        h.getReason(),
                        h.getChangedAt()))
                .toList();
    }

    // ======================= HÀM PHỤ =======================

    private void applyProfile(Customer customer, CustomerProfileRequest req, String taxCode) {
        Region region = regionRepository.findById(req.getRegionId())
                .filter(r -> ACTIVE.equalsIgnoreCase(r.getStatus()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "REGION_NOT_FOUND",
                        "Khu vực không tồn tại hoặc đã ngừng sử dụng", "regionId"));

        customer.setName(req.getName().trim());
        customer.setTaxCode(taxCode);
        customer.setCustomerGroup(req.getCustomerGroup());
        customer.setRegion(region);
        customer.setContactName(blankToNull(req.getContactName()));
        customer.setPhone(blankToNull(req.getPhone()));
        String email = blankToNull(req.getEmail());
        customer.setEmail(email == null ? null : email.toLowerCase());
        customer.setAddress(blankToNull(req.getAddress()));
        customer.setNote(blankToNull(req.getNote()));
    }

    /** Người phụ trách phải là nhân viên kinh doanh đang hoạt động. */
    User loadActiveSalesRep(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SALES_REP",
                        "Không tìm thấy nhân viên kinh doanh", "salesRepId"));
        boolean isSalesRep = user.getRoles().stream().anyMatch(r -> r.getName() == RoleName.ROLE_SALES_REP);
        if (!isSalesRep) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SALES_REP",
                    user.getFullName() + " không phải nhân viên kinh doanh", "salesRepId");
        }
        if (!ACTIVE.equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SALES_REP",
                    "Tài khoản của " + user.getFullName() + " đang bị khoá", "salesRepId");
        }
        return user;
    }

    private void recordHistory(Customer customer, User from, User to, String type, String reason, UserDetailsImpl actor) {
        historyRepository.save(CustomerAssignmentHistory.builder()
                .customer(customer)
                .fromSalesRep(from)
                .toSalesRep(to)
                .changedBy(currentUserRef(actor))
                .changeType(type)
                .reason(reason)
                .build());
    }

    private User currentUserRef(UserDetailsImpl actor) {
        return actor == null || actor.getId() == null ? null : userRepository.getReferenceById(actor.getId());
    }

    private Customer findCustomer(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
    }

    CustomerResponse toResponse(Customer c) {
        Region region = c.getRegion();
        return new CustomerResponse(
                c.getId(),
                c.getCode(),
                c.getName(),
                c.getTaxCode(),
                c.getCustomerGroup() == null ? null : c.getCustomerGroup().name(),
                c.getCustomerGroup() == null ? null : c.getCustomerGroup().getLabel(),
                region == null ? null : new RefItem(region.getId(), region.getCode(), region.getName()),
                toUserRef(c.getSalesRep()),
                c.getContactName(),
                c.getPhone(),
                c.getEmail(),
                c.getAddress(),
                c.getNote(),
                c.getStatus(),
                c.getStatusReason(),
                c.getCreditLimit() != null ? c.getCreditLimit() : BigDecimal.ZERO,
                c.getMaxDebtDays() != null ? c.getMaxDebtDays() : 30,
                c.isTransactionLocked(),
                c.getTransactionLockReason(),
                c.getTransactionLockedAt(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }

    private RefItem toUserRef(User u) {
        return u == null ? null : new RefItem(u.getId(), u.getUsername(), u.getFullName());
    }

    private void checkDebtManagementAccess(UserDetailsImpl actor) {
        if (actor == null || !CustomerAccess.hasFullAccess(actor)) {
            throw BusinessException.forbidden("FORBIDDEN",
                    "Chỉ Kế toán công nợ, Quản lý kinh doanh hoặc Admin mới có quyền thực hiện thao tác này");
        }
    }

    private static void markAuditLogged() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getRequest() != null) {
                attributes.getRequest().setAttribute(AuditLogInterceptor.AUDIT_LOGGED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) {
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
