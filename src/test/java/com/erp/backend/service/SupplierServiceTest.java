package com.erp.backend.service;

import com.erp.backend.dto.supplier.ChangeSupplierStatusRequest;
import com.erp.backend.dto.supplier.CreateSupplierRequest;
import com.erp.backend.dto.supplier.SupplierProfileRequest;
import com.erp.backend.dto.supplier.SupplierResponse;
import com.erp.backend.entity.Supplier;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock private SupplierRepository supplierRepository;

    @InjectMocks private SupplierService service;

    @BeforeEach
    void setUp() {
        lenient().when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Supplier existing(long id, String status) {
        Supplier s = Supplier.builder().id(id).code("NCC-" + id).name("Nhà cung cấp " + id)
                .taxCode("010000000" + id).status(status).build();
        lenient().when(supplierRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private CreateSupplierRequest createRequest() {
        CreateSupplierRequest req = new CreateSupplierRequest();
        req.setCode("  ncc-vnm  ");
        req.setName(" Công ty Sữa Việt Nam ");
        req.setTaxCode(" 0300588569 ");
        req.setContactName("Chị Hoa");
        req.setPhone("");
        req.setPaymentTerms("Thanh toán trong 30 ngày");
        return req;
    }

    private ChangeSupplierStatusRequest statusRequest(String status, String reason) {
        ChangeSupplierStatusRequest req = new ChangeSupplierStatusRequest();
        req.setStatus(status);
        req.setReason(reason);
        return req;
    }

    @Test
    @DisplayName("S2-09: Tạo nhà cung cấp hợp lệ -> mã viết hoa, bỏ khoảng trắng, trạng thái ACTIVE")
    void create_valid() {
        SupplierResponse res = service.create(createRequest());

        assertThat(res.code()).isEqualTo("NCC-VNM");
        assertThat(res.name()).isEqualTo("Công ty Sữa Việt Nam");
        assertThat(res.taxCode()).isEqualTo("0300588569");
        assertThat(res.phone()).isNull();
        assertThat(res.paymentTerms()).isEqualTo("Thanh toán trong 30 ngày");
        assertThat(res.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("S2-09: Trùng mã nhà cung cấp -> 409 SUPPLIER_CODE_EXISTS")
    void create_duplicateCode() {
        when(supplierRepository.existsByCodeIgnoreCase("NCC-VNM")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SUPPLIER_CODE_EXISTS");
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("S2-09: Trùng mã số thuế -> 409 TAX_CODE_EXISTS")
    void create_duplicateTaxCode() {
        when(supplierRepository.existsByTaxCode("0300588569")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("S2-09: Sửa thông tin -> mã giữ nguyên")
    void update_keepsCode() {
        existing(1, "ACTIVE");
        SupplierProfileRequest req = new SupplierProfileRequest();
        req.setName("Tên mới");
        req.setTaxCode("0100000009");

        SupplierResponse res = service.update(1L, req);

        assertThat(res.code()).isEqualTo("NCC-1");
        assertThat(res.name()).isEqualTo("Tên mới");
        assertThat(res.taxCode()).isEqualTo("0100000009");
    }

    @Test
    @DisplayName("S2-09: Sửa sang mã số thuế của nhà cung cấp khác -> 409")
    void update_duplicateTaxCode() {
        existing(1, "ACTIVE");
        when(supplierRepository.existsByTaxCodeAndIdNot("0100000002", 1L)).thenReturn(true);
        SupplierProfileRequest req = new SupplierProfileRequest();
        req.setName("Tên");
        req.setTaxCode("0100000002");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TAX_CODE_EXISTS");
    }

    @Test
    @DisplayName("S2-09: Nhà cung cấp không tồn tại -> 404")
    void getById_notFound() {
        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("S2-09: Ngừng giao dịch không có lý do -> 400 REASON_REQUIRED")
    void deactivate_withoutReason() {
        existing(1, "ACTIVE");

        assertThatThrownBy(() -> service.changeStatus(1L, statusRequest("INACTIVE", "  ")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REASON_REQUIRED");
    }

    @Test
    @DisplayName("S2-09: Ngừng giao dịch có lý do, rồi giao dịch lại -> lý do bị xoá")
    void deactivate_thenReactivate() {
        Supplier s = existing(1, "ACTIVE");

        SupplierResponse off = service.changeStatus(1L, statusRequest("INACTIVE", "Hàng lỗi nhiều lần"));
        assertThat(off.status()).isEqualTo("INACTIVE");
        assertThat(off.statusReason()).isEqualTo("Hàng lỗi nhiều lần");

        SupplierResponse on = service.changeStatus(1L, statusRequest("ACTIVE", null));
        assertThat(on.status()).isEqualTo("ACTIVE");
        assertThat(s.getStatusReason()).isNull();
    }

    @Test
    @DisplayName("S2-09: Đổi sang đúng trạng thái hiện tại -> 400 STATUS_UNCHANGED")
    void changeStatus_unchanged() {
        existing(1, "INACTIVE");

        assertThatThrownBy(() -> service.changeStatus(1L, statusRequest("INACTIVE", "x")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("STATUS_UNCHANGED");
    }

    @Test
    @DisplayName("S2-09: Xoá nhà cung cấp chưa có giao dịch")
    void delete_existing() {
        Supplier s = existing(1, "ACTIVE");

        service.delete(1L);

        verify(supplierRepository).delete(s);
    }
}
