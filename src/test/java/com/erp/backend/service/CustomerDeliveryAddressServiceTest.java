package com.erp.backend.service;

import com.erp.backend.dto.customer.DeliveryAddressRequest;
import com.erp.backend.dto.customer.DeliveryAddressResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.CustomerDeliveryAddress;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerDeliveryAddressRepository;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerDeliveryAddressServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerDeliveryAddressRepository addressRepository;

    @InjectMocks private CustomerDeliveryAddressService service;

    private final UserDetailsImpl owner = actor(7, "ROLE_SALES_REP");
    private Customer customer;
    private final List<CustomerDeliveryAddress> active = new ArrayList<>();

    @BeforeEach
    void setUp() {
        customer = customer(1, salesRep(7));
        lenient().when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        lenient().when(customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(customer));
        lenient().when(addressRepository.save(any(CustomerDeliveryAddress.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(addressRepository.findByCustomer_IdAndStatusOrderByDefaultAddressDescIdAsc(1L, "ACTIVE"))
                .thenAnswer(inv -> active.stream().filter(a -> "ACTIVE".equals(a.getStatus())).toList());
        lenient().when(addressRepository.findByIdAndCustomer_Id(any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long customerId = inv.getArgument(1);
            return active.stream()
                    .filter(a -> a.getId().equals(id) && a.getCustomer().getId().equals(customerId))
                    .findFirst();
        });
    }

    private CustomerDeliveryAddress existing(long id, boolean isDefault) {
        CustomerDeliveryAddress a = CustomerDeliveryAddress.builder()
                .id(id)
                .customer(customer)
                .address("Địa chỉ " + id)
                .receiverName("Anh Nam")
                .receiverPhone("0912345678")
                .defaultAddress(isDefault)
                .status("ACTIVE")
                .build();
        active.add(a);
        return a;
    }

    private DeliveryAddressRequest request(Boolean isDefault) {
        DeliveryAddressRequest req = new DeliveryAddressRequest();
        req.setLabel(" Kho chính ");
        req.setAddress(" 12 Nguyễn Trãi, Hà Đông ");
        req.setReceiverName("Chị Lan");
        req.setReceiverPhone("0987654321");
        req.setNote("Đi vào ngõ 5");
        req.setIsDefault(isDefault);
        return req;
    }

    @Test
    @DisplayName("S3-04: Điểm giao đầu tiên tự động thành mặc định")
    void create_firstAddress_becomesDefault() {
        DeliveryAddressResponse res = service.create(1L, request(null), owner);

        assertThat(res.isDefault()).isTrue();
        assertThat(res.label()).isEqualTo("Kho chính");
        assertThat(res.address()).isEqualTo("12 Nguyễn Trãi, Hà Đông");
        assertThat(res.customerId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("S3-04: Thêm điểm mới không chọn mặc định -> điểm mặc định cũ giữ nguyên")
    void create_notDefault_keepsOldDefault() {
        CustomerDeliveryAddress old = existing(10, true);

        DeliveryAddressResponse res = service.create(1L, request(false), owner);

        assertThat(res.isDefault()).isFalse();
        assertThat(old.isDefaultAddress()).isTrue();
    }

    @Test
    @DisplayName("S3-04: Thêm điểm mới chọn làm mặc định -> bỏ mặc định ở điểm cũ (chỉ 1 điểm mặc định)")
    void create_asDefault_clearsOldDefault() {
        CustomerDeliveryAddress old = existing(10, true);

        DeliveryAddressResponse res = service.create(1L, request(true), owner);

        assertThat(res.isDefault()).isTrue();
        assertThat(old.isDefaultAddress()).isFalse();
    }

    @Test
    @DisplayName("S3-04: Đổi điểm mặc định -> chỉ còn đúng 1 điểm mặc định")
    void setDefault_switches() {
        CustomerDeliveryAddress a = existing(10, true);
        CustomerDeliveryAddress b = existing(11, false);

        DeliveryAddressResponse res = service.setDefault(1L, 11L, owner);

        assertThat(res.isDefault()).isTrue();
        assertThat(b.isDefaultAddress()).isTrue();
        assertThat(a.isDefaultAddress()).isFalse();
    }

    @Test
    @DisplayName("S3-04: Ngừng dùng điểm mặc định -> điểm còn lại tự thành mặc định")
    void deactivate_default_promotesNext() {
        CustomerDeliveryAddress a = existing(10, true);
        CustomerDeliveryAddress b = existing(11, false);

        service.deactivate(1L, 10L, owner);

        assertThat(a.getStatus()).isEqualTo("INACTIVE");
        assertThat(a.isDefaultAddress()).isFalse();
        assertThat(b.isDefaultAddress()).isTrue();
    }

    @Test
    @DisplayName("S3-04: Sửa điểm giao không thuộc đại lý này -> 404")
    void update_addressOfOtherCustomer_notFound() {
        Customer other = customer(2, salesRep(7));
        active.add(CustomerDeliveryAddress.builder().id(20L).customer(other).status("ACTIVE").build());

        assertThatThrownBy(() -> service.update(1L, 20L, request(null), owner))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("S3-06: NV kinh doanh thêm điểm giao cho đại lý không do mình phụ trách -> 404")
    void create_salesRepNotOwner_hidden() {
        assertThatThrownBy(() -> service.create(1L, request(null), actor(8, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(addressRepository, never()).save(any());
    }

    @Test
    @DisplayName("S3-04: Đại lý đang ngừng giao dịch -> không thêm được điểm giao")
    void create_inactiveCustomer_rejected() {
        customer.setStatus("INACTIVE");

        assertThatThrownBy(() -> service.create(1L, request(null), owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CUSTOMER_INACTIVE");
    }

    @Test
    @DisplayName("S3-04: Chưa có điểm giao nhưng hồ sơ đã có địa chỉ -> tự động tạo điểm giao mặc định từ địa chỉ hồ sơ")
    void list_whenEmpty_autoCreatesFromCustomerAddress() {
        customer.setAddress("123 Phố Huế, Hà Nội");
        customer.setContactName("Chị Hoa");
        customer.setPhone("0912345678");

        List<DeliveryAddressResponse> res = service.list(1L, owner);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).address()).isEqualTo("123 Phố Huế, Hà Nội");
        assertThat(res.get(0).label()).isEqualTo("Địa chỉ trụ sở / Kho chính");
        assertThat(res.get(0).receiverName()).isEqualTo("Chị Hoa");
        assertThat(res.get(0).receiverPhone()).isEqualTo("0912345678");
        assertThat(res.get(0).isDefault()).isTrue();
        verify(addressRepository).save(any(CustomerDeliveryAddress.class));
    }
}
