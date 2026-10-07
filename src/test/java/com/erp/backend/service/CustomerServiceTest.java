package com.erp.backend.service;

import com.erp.backend.dto.customer.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerAssignmentHistoryRepository;
import com.erp.backend.repository.CustomerDeliveryAddressRepository;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.RegionRepository;
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
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerAssignmentHistoryRepository historyRepository;
    @Mock private RegionRepository regionRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private CustomerDeliveryAddressRepository addressRepository;

    @InjectMocks private CustomerService service;

    private final UserDetailsImpl accountant = actor(100, "ROLE_ACCOUNTANT");
    private final UserDetailsImpl manager = actor(101, "ROLE_SALES_MANAGER");
    private final UserDetailsImpl salesRepActor = actor(102, "ROLE_SALES_REP");

    @BeforeEach
    void setUp() {
        lenient().when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(regionRepository.findById(1L)).thenReturn(Optional.of(region(1, "ACTIVE")));
        lenient().when(userRepository.getReferenceById(anyLong()))
                .thenAnswer(inv -> user((Long) inv.getArgument(0), "ACTIVE", RoleName.ROLE_ADMIN));
        lenient().when(addressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(addressRepository.findByCustomer_IdAndStatusOrderByDefaultAddressDescIdAsc(any(), any()))
                .thenReturn(List.of());
    }

    private CreateCustomerRequest newRequest(String code) {
        CreateCustomerRequest req = new CreateCustomerRequest();
        req.setCode(code);
        req.setName("  Đại lý Minh Phát ");
        req.setTaxCode("0101234567");
        req.setCustomerGroup(CustomerGroup.DEALER_LEVEL_1);
        req.setRegionId(1L);
        req.setPhone("0912345678");
        req.setEmail("MinhPhat@Gmail.com");
        return req;
    }

    // ======================= S3-07: LỌC KHOÁ GIAO DỊCH =======================

    @Test
    @DisplayName("S3-07: Lọc đại lý bị khoá giao dịch -> gửi điều kiện xuống DB và phân trang ở server")
    @SuppressWarnings("unchecked")
    void search_lockedFilter_queriesDatabase() {
        Customer locked = customer(7, null);
        locked.setTransactionLocked(true);
        when(customerRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(locked),
                        org.springframework.data.domain.PageRequest.of(1, 20), 21));

        com.erp.backend.dto.user.PageResponse<CustomerResponse> res =
                service.search(null, null, null, null, null, true, 1, 20, manager);

        assertThat(res.content()).extracting(CustomerResponse::transactionLocked).containsExactly(true);
        assertThat(res.totalElements()).isEqualTo(21);
        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(customerRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    }

    // ======================= S3-03: TẠO / SỬA =======================

    @Test
    @DisplayName("S3-03: Tạo đại lý -> mã viết hoa, bỏ khoảng trắng, trạng thái Đang giao dịch")
    void create_normalizesAndSaves() {
        CustomerResponse res = service.create(newRequest(" dl-001 "), accountant);

        assertThat(res.code()).isEqualTo("DL-001");
        assertThat(res.name()).isEqualTo("Đại lý Minh Phát");
        assertThat(res.email()).isEqualTo("minhphat@gmail.com");
        assertThat(res.status()).isEqualTo("ACTIVE");
        assertThat(res.customerGroupLabel()).isEqualTo("Đại lý cấp 1");
        assertThat(res.salesRep()).isNull();
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("S3-03: Mã đại lý là duy nhất -> trùng mã trả 409")
    void create_duplicateCode_conflict() {
        when(customerRepository.existsByCodeIgnoreCase("DL-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(newRequest("dl-001"), accountant))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("CUSTOMER_CODE_EXISTS");
                    assertThat(be.getField()).isEqualTo("code");
                });
        verify(customerRepository, never()).save(any());
    }

    @Test
    @DisplayName("S3-03: Trùng mã số thuế -> 409")
    void create_duplicateTaxCode_conflict() {
        when(customerRepository.existsByTaxCode("0101234567")).thenReturn(true);

        assertThatThrownBy(() -> service.create(newRequest("DL-002"), accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TAX_CODE_EXISTS");
    }

    @Test
    @DisplayName("S3-03: Mã số thuế để trống -> lưu null, không kiểm tra trùng")
    void create_blankTaxCode_savedAsNull() {
        CreateCustomerRequest req = newRequest("DL-003");
        req.setTaxCode("  ");

        CustomerResponse res = service.create(req, accountant);

        assertThat(res.taxCode()).isNull();
        verify(customerRepository, never()).existsByTaxCode(any());
    }

    @Test
    @DisplayName("S3-03: Khu vực đã ngừng sử dụng -> từ chối")
    void create_inactiveRegion_rejected() {
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region(2, "INACTIVE")));
        CreateCustomerRequest req = newRequest("DL-004");
        req.setRegionId(2L);

        assertThatThrownBy(() -> service.create(req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REGION_NOT_FOUND");
    }

    @Test
    @DisplayName("S3-03 + S3-06: Tạo kèm người phụ trách -> ghi lịch sử CREATE")
    void create_withSalesRep_recordsHistory() {
        User rep = salesRep(7);
        when(userRepository.findById(7L)).thenReturn(Optional.of(rep));
        CreateCustomerRequest req = newRequest("DL-005");
        req.setSalesRepId(7L);

        CustomerResponse res = service.create(req, accountant);

        assertThat(res.salesRep().id()).isEqualTo(7L);
        ArgumentCaptor<CustomerAssignmentHistory> captor = ArgumentCaptor.forClass(CustomerAssignmentHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo("CREATE");
        assertThat(captor.getValue().getFromSalesRep()).isNull();
        assertThat(captor.getValue().getToSalesRep()).isSameAs(rep);
    }

    @Test
    @DisplayName("S3-06: Người phụ trách phải là nhân viên kinh doanh (nhân viên kho -> từ chối)")
    void create_salesRepWithWrongRole_rejected() {
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8, "ACTIVE", RoleName.ROLE_WAREHOUSE)));
        CreateCustomerRequest req = newRequest("DL-006");
        req.setSalesRepId(8L);

        assertThatThrownBy(() -> service.create(req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SALES_REP");
        verify(customerRepository, never()).save(any());
    }

    @Test
    @DisplayName("S3-06: Nhân viên kinh doanh đang bị khoá -> không gán được")
    void create_lockedSalesRep_rejected() {
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9, "LOCKED", RoleName.ROLE_SALES_REP)));
        CreateCustomerRequest req = newRequest("DL-007");
        req.setSalesRepId(9L);

        assertThatThrownBy(() -> service.create(req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SALES_REP");
    }

    @Test
    @DisplayName("S3-03: Sửa hồ sơ trùng mã số thuế của đại lý khác -> 409")
    void update_duplicateTaxCode_conflict() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1, null)));
        when(customerRepository.existsByTaxCodeAndIdNot("0101234567", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, newRequest("X"), accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TAX_CODE_EXISTS");
    }

    @Test
    @DisplayName("S3-03: Sửa hồ sơ không làm đổi mã đại lý và người phụ trách")
    void update_keepsCodeAndSalesRep() {
        User rep = salesRep(7);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1, rep)));

        CustomerResponse res = service.update(1L, newRequest("KHAC"), accountant);

        assertThat(res.code()).isEqualTo("DL-1");
        assertThat(res.salesRep().id()).isEqualTo(7L);
        assertThat(res.name()).isEqualTo("Đại lý Minh Phát");
    }

    @Test
    @DisplayName("S3-03: Ngừng giao dịch bắt buộc nhập lý do")
    void changeStatus_inactiveWithoutReason_rejected() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1, null)));
        ChangeCustomerStatusRequest req = new ChangeCustomerStatusRequest();
        req.setStatus("INACTIVE");
        req.setReason("   ");

        assertThatThrownBy(() -> service.changeStatus(1L, req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REASON_REQUIRED");
    }

    @Test
    @DisplayName("S3-03: Ngừng giao dịch có lý do -> INACTIVE, giao dịch lại -> xoá lý do")
    void changeStatus_toggle() {
        Customer c = customer(1, null);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));
        ChangeCustomerStatusRequest stop = new ChangeCustomerStatusRequest();
        stop.setStatus("INACTIVE");
        stop.setReason("Đóng cửa hàng");

        CustomerResponse res = service.changeStatus(1L, stop, accountant);
        assertThat(res.status()).isEqualTo("INACTIVE");
        assertThat(res.statusReason()).isEqualTo("Đóng cửa hàng");

        ChangeCustomerStatusRequest resume = new ChangeCustomerStatusRequest();
        resume.setStatus("ACTIVE");
        res = service.changeStatus(1L, resume, accountant);
        assertThat(res.status()).isEqualTo("ACTIVE");
        assertThat(res.statusReason()).isNull();
    }

    // ======================= S3-06 + S3-08: PHẠM VI XEM =======================

    @Test
    @DisplayName("S3-06: NV kinh doanh mở đại lý của người khác -> 404")
    void getById_salesRepNotOwner_hidden() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1, salesRep(8))));

        assertThatThrownBy(() -> service.getById(1L, actor(7, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("S3-06: NV kinh doanh mở đại lý của mình -> xem được")
    void getById_salesRepOwner_ok() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1, salesRep(7))));

        assertThat(service.getById(1L, actor(7, "ROLE_SALES_REP")).code()).isEqualTo("DL-1");
    }

    @Test
    @DisplayName("Không tìm thấy đại lý -> 404")
    void getById_notFound() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ======================= S3-06: PHÂN CÔNG =======================

    @Test
    @DisplayName("S3-06: Đổi người phụ trách -> ghi lịch sử từ người cũ sang người mới")
    void assignSalesRep_recordsHistory() {
        User oldRep = salesRep(7);
        User newRep = salesRep(8);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(customer(1, oldRep)));
        when(userRepository.findById(8L)).thenReturn(Optional.of(newRep));
        AssignSalesRepRequest req = new AssignSalesRepRequest();
        req.setSalesRepId(8L);
        req.setReason("Đổi tuyến");

        CustomerResponse res = service.assignSalesRep(1L, req, manager);

        assertThat(res.salesRep().id()).isEqualTo(8L);
        ArgumentCaptor<CustomerAssignmentHistory> captor = ArgumentCaptor.forClass(CustomerAssignmentHistory.class);
        verify(historyRepository).save(captor.capture());
        CustomerAssignmentHistory h = captor.getValue();
        assertThat(h.getChangeType()).isEqualTo("ASSIGN");
        assertThat(h.getFromSalesRep()).isSameAs(oldRep);
        assertThat(h.getToSalesRep()).isSameAs(newRep);
        assertThat(h.getReason()).isEqualTo("Đổi tuyến");
        assertThat(h.getChangedBy().getId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("S3-06: Gán lại đúng người đang phụ trách -> báo lỗi, không ghi lịch sử")
    void assignSalesRep_sameRep_rejected() {
        User rep = salesRep(7);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(customer(1, rep)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(rep));
        AssignSalesRepRequest req = new AssignSalesRepRequest();
        req.setSalesRepId(7L);

        assertThatThrownBy(() -> service.assignSalesRep(1L, req, manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SAME_SALES_REP");
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("S3-06: Chuyển giao hàng loạt khi nhân viên nghỉ -> chuyển hết, mỗi đại lý 1 dòng lịch sử")
    @SuppressWarnings("unchecked")
    void transfer_movesAllCustomers() {
        User leaving = user(7, "LOCKED", RoleName.ROLE_SALES_REP); // đã nghỉ, bị khoá tài khoản
        User receiver = salesRep(8);
        Customer c1 = customer(1, leaving);
        Customer c2 = customer(2, leaving);
        when(userRepository.findById(7L)).thenReturn(Optional.of(leaving));
        when(userRepository.findById(8L)).thenReturn(Optional.of(receiver));
        when(customerRepository.findBySalesRepIdForUpdate(7L)).thenReturn(List.of(c1, c2));

        TransferCustomersRequest req = new TransferCustomersRequest();
        req.setFromSalesRepId(7L);
        req.setToSalesRepId(8L);
        req.setReason("Nhân viên nghỉ việc");

        TransferCustomersResponse res = service.transfer(req, manager);

        assertThat(res.transferredCount()).isEqualTo(2);
        assertThat(c1.getSalesRep()).isSameAs(receiver);
        assertThat(c2.getSalesRep()).isSameAs(receiver);

        ArgumentCaptor<List<CustomerAssignmentHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .allSatisfy(h -> {
                    assertThat(h.getChangeType()).isEqualTo("TRANSFER");
                    assertThat(h.getFromSalesRep()).isSameAs(leaving);
                    assertThat(h.getToSalesRep()).isSameAs(receiver);
                    assertThat(h.getReason()).isEqualTo("Nhân viên nghỉ việc");
                });
    }

    @Test
    @DisplayName("S3-06: Chuyển giao theo khu vực -> chỉ lấy đại lý thuộc khu vực đó")
    void transfer_byRegion() {
        User leaving = salesRep(7);
        when(userRepository.findById(7L)).thenReturn(Optional.of(leaving));
        when(userRepository.findById(8L)).thenReturn(Optional.of(salesRep(8)));
        when(customerRepository.findBySalesRepIdAndRegionIdForUpdate(7L, 3L)).thenReturn(List.of(customer(1, leaving)));

        TransferCustomersRequest req = new TransferCustomersRequest();
        req.setFromSalesRepId(7L);
        req.setToSalesRepId(8L);
        req.setRegionId(3L);
        req.setReason("Chia lại địa bàn");

        assertThat(service.transfer(req, manager).transferredCount()).isEqualTo(1);
        verify(customerRepository, never()).findBySalesRepIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("S3-06: Người nhận trùng người bàn giao -> từ chối")
    void transfer_sameFromTo_rejected() {
        TransferCustomersRequest req = new TransferCustomersRequest();
        req.setFromSalesRepId(7L);
        req.setToSalesRepId(7L);
        req.setReason("x");

        assertThatThrownBy(() -> service.transfer(req, manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SAME_SALES_REP");
    }

    @Test
    @DisplayName("S3-06: Nhân viên bàn giao không phụ trách đại lý nào -> báo lỗi")
    void transfer_nothingToTransfer() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(salesRep(7)));
        when(userRepository.findById(8L)).thenReturn(Optional.of(salesRep(8)));
        when(customerRepository.findBySalesRepIdForUpdate(7L)).thenReturn(List.of());

        TransferCustomersRequest req = new TransferCustomersRequest();
        req.setFromSalesRepId(7L);
        req.setToSalesRepId(8L);
        req.setReason("x");

        assertThatThrownBy(() -> service.transfer(req, manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("NO_CUSTOMERS_TO_TRANSFER");
        verify(historyRepository, never()).saveAll(anyList());
    }

    // ======================= S3-05: TESTS HẠN MỨC CÔNG NỢ =======================

    @Test
    @DisplayName("S3-05: Cập nhật hạn mức công nợ thành công và ghi nhật ký kiểm toán")
    void updateDebtLimit_success_recordsAuditLog() {
        Customer c = customer(1L, null);
        c.setCreditLimit(java.math.BigDecimal.valueOf(50_000_000));
        c.setMaxDebtDays(30);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        UpdateDebtLimitRequest req = UpdateDebtLimitRequest.builder()
                .creditLimit(java.math.BigDecimal.valueOf(100_000_000))
                .maxDebtDays(45)
                .reason("Nâng hạn mức sau 6 tháng thanh toán tốt")
                .build();

        CustomerResponse res = service.updateDebtLimit(1L, req, accountant);

        assertThat(res.creditLimit()).isEqualByComparingTo(java.math.BigDecimal.valueOf(100_000_000));
        assertThat(res.maxDebtDays()).isEqualTo(45);
        verify(customerRepository).save(c);
        verify(auditLogService).record(
                eq(AuditModule.DEBT_LIMIT),
                eq("UPDATE_DEBT_LIMIT"),
                eq("CUSTOMER"),
                eq(1L),
                eq("DL-1"),
                contains("50000000"),
                contains("100000000"),
                eq("Nâng hạn mức sau 6 tháng thanh toán tốt"),
                eq(accountant));
    }

    @Test
    @DisplayName("S3-05: Thiếu lý do thay đổi hạn mức -> báo lỗi REASON_REQUIRED")
    void updateDebtLimit_missingReason_throwsException() {
        Customer c = customer(1L, null);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        UpdateDebtLimitRequest req = UpdateDebtLimitRequest.builder()
                .creditLimit(java.math.BigDecimal.valueOf(100_000_000))
                .maxDebtDays(45)
                .reason("   ")
                .build();

        assertThatThrownBy(() -> service.updateDebtLimit(1L, req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REASON_REQUIRED");
    }

    @Test
    @DisplayName("S3-05: Hạn mức và số ngày nợ không đổi -> báo lỗi LIMIT_UNCHANGED")
    void updateDebtLimit_unchanged_throwsException() {
        Customer c = customer(1L, null);
        c.setCreditLimit(java.math.BigDecimal.valueOf(50_000_000));
        c.setMaxDebtDays(30);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        UpdateDebtLimitRequest req = UpdateDebtLimitRequest.builder()
                .creditLimit(java.math.BigDecimal.valueOf(50_000_000))
                .maxDebtDays(30)
                .reason("Giữ nguyên hạn mức")
                .build();

        assertThatThrownBy(() -> service.updateDebtLimit(1L, req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("LIMIT_UNCHANGED");
    }

    @Test
    @DisplayName("S3-05: Vai trò không đủ quyền sửa hạn mức (vd Sales Rep) -> từ chối 403")
    void updateDebtLimit_unauthorizedRole_throwsForbidden() {
        UpdateDebtLimitRequest req = UpdateDebtLimitRequest.builder()
                .creditLimit(java.math.BigDecimal.valueOf(100_000_000))
                .maxDebtDays(45)
                .reason("Tự nâng hạn mức")
                .build();

        assertThatThrownBy(() -> service.updateDebtLimit(1L, req, salesRepActor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ======================= S3-07: TESTS KHÓA / MỞ GIAO DỊCH =======================

    @Test
    @DisplayName("S3-07: Khóa giao dịch đại lý thành công và ghi nhật ký kiểm toán")
    void setTransactionLock_lockSuccess() {
        Customer c = customer(1L, null);
        c.setTransactionLocked(false);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        CustomerTransactionLockRequest req = CustomerTransactionLockRequest.builder()
                .locked(true)
                .reason("Nợ quá hạn trên 60 ngày chưa thanh toán")
                .build();

        CustomerResponse res = service.setTransactionLock(1L, req, accountant);

        assertThat(res.transactionLocked()).isTrue();
        assertThat(res.transactionLockReason()).isEqualTo("Nợ quá hạn trên 60 ngày chưa thanh toán");
        verify(customerRepository).save(c);
        verify(auditLogService).record(
                eq(AuditModule.DEBT_LIMIT),
                eq("LOCK_TRANSACTION"),
                eq("CUSTOMER"),
                eq(1L),
                eq("DL-1"),
                contains("false"),
                contains("true"),
                eq("Nợ quá hạn trên 60 ngày chưa thanh toán"),
                eq(accountant));
    }

    @Test
    @DisplayName("S3-07: Mở giao dịch đại lý thành công và ghi nhật ký kiểm toán")
    void setTransactionLock_unlockSuccess() {
        Customer c = customer(1L, null);
        c.setTransactionLocked(true);
        c.setTransactionLockReason("Tạm khóa cũ");
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        CustomerTransactionLockRequest req = CustomerTransactionLockRequest.builder()
                .locked(false)
                .reason("Đại lý đã thanh toán hết nợ cũ")
                .build();

        CustomerResponse res = service.setTransactionLock(1L, req, manager);

        assertThat(res.transactionLocked()).isFalse();
        assertThat(res.transactionLockReason()).isEqualTo("Đại lý đã thanh toán hết nợ cũ");
        verify(customerRepository).save(c);
        verify(auditLogService).record(
                eq(AuditModule.DEBT_LIMIT),
                eq("UNLOCK_TRANSACTION"),
                eq("CUSTOMER"),
                eq(1L),
                eq("DL-1"),
                contains("true"),
                contains("false"),
                eq("Đại lý đã thanh toán hết nợ cũ"),
                eq(manager));
    }

    @Test
    @DisplayName("S3-07: Trạng thái khóa không đổi -> báo lỗi LOCK_STATUS_UNCHANGED")
    void setTransactionLock_statusUnchanged_throwsException() {
        Customer c = customer(1L, null);
        c.setTransactionLocked(true);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        CustomerTransactionLockRequest req = CustomerTransactionLockRequest.builder()
                .locked(true)
                .reason("Lại khóa tiếp")
                .build();

        assertThatThrownBy(() -> service.setTransactionLock(1L, req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("LOCK_STATUS_UNCHANGED");
    }

    @Test
    @DisplayName("S3-07: Thiếu lý do khóa/mở -> báo lỗi REASON_REQUIRED")
    void setTransactionLock_missingReason_throwsException() {
        Customer c = customer(1L, null);
        when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        CustomerTransactionLockRequest req = CustomerTransactionLockRequest.builder()
                .locked(true)
                .reason("   ")
                .build();

        assertThatThrownBy(() -> service.setTransactionLock(1L, req, accountant))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REASON_REQUIRED");
    }

    // ======================= S3-07: TESTS KIỂM TRA ĐIỀU KIỆN TẠO ĐƠN =======================

    @Test
    @DisplayName("S3-07 & S4-02: Kiểm tra tạo đơn - Bị chặn khi đại lý bị khóa giao dịch")
    void checkOrderCreation_locked_blocked() {
        Customer c = customer(1L, null);
        c.setTransactionLocked(true);
        c.setTransactionLockReason("Nợ xấu");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));

        OrderCreationCheckResponse res = service.checkOrderCreation(1L);

        assertThat(res.allowed()).isFalse();
        assertThat(res.blockReason()).contains("khóa giao dịch").contains("Nợ xấu");
        assertThat(res.transactionLocked()).isTrue();
    }

    @Test
    @DisplayName("S3-07 & S4-02: Kiểm tra tạo đơn - Bị chặn khi đại lý ngừng giao dịch (INACTIVE)")
    void checkOrderCreation_inactive_blocked() {
        Customer c = customer(1L, null);
        c.setStatus("INACTIVE");
        c.setStatusReason("Đóng cửa kinh doanh");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));

        OrderCreationCheckResponse res = service.checkOrderCreation(1L);

        assertThat(res.allowed()).isFalse();
        assertThat(res.blockReason()).contains("ngừng giao dịch");
    }

    @Test
    @DisplayName("S3-07 & S4-02: Kiểm tra tạo đơn - Cho phép khi đại lý ACTIVE và chưa bị khóa")
    void checkOrderCreation_activeAndUnlocked_allowed() {
        Customer c = customer(1L, null);
        c.setStatus("ACTIVE");
        c.setTransactionLocked(false);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));

        OrderCreationCheckResponse res = service.checkOrderCreation(1L);

        assertThat(res.allowed()).isTrue();
        assertThat(res.blockReason()).isNull();
    }

    @Test
    @DisplayName("S3-07: assertCanCreateOrder ném lỗi khi đại lý bị khóa giao dịch")
    void assertCanCreateOrder_locked_throwsForbidden() {
        Customer c = customer(1L, null);
        c.setTransactionLocked(true);
        c.setTransactionLockReason("Có nguy cơ quỵt nợ");

        assertThatThrownBy(() -> service.assertCanCreateOrder(c))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CUSTOMER_TRANSACTION_LOCKED");
    }
}
