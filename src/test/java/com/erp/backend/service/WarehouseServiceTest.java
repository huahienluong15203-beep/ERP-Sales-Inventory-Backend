package com.erp.backend.service;

import com.erp.backend.dto.customer.CustomerResponse;
import com.erp.backend.dto.warehouse.*;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.User;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.entity.WarehouseLocation;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseLocationRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    @Mock private WarehouseRepository warehouseRepository;
    @Mock private WarehouseLocationRepository locationRepository;
    @Mock private UserRepository userRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerService customerService;

    @InjectMocks private WarehouseService warehouseService;

    private UserDetailsImpl whManagerActor;
    private User managerUser;
    private Warehouse sampleWarehouse;
    private WarehouseLocation sampleLocation;

    @BeforeEach
    void setUp() {
        whManagerActor = new UserDetailsImpl(
                1L, "wh_mgr", "Quản Lý Kho", "whmgr@erp.com", "pass", true,
                List.of(new SimpleGrantedAuthority("ROLE_WH_MANAGER"))
        );

        managerUser = User.builder()
                .id(2L)
                .username("wh_lead")
                .fullName("Nguyễn Văn Quản Lý Kho")
                .email("lead@erp.com")
                .phone("0988776655")
                .build();

        sampleWarehouse = Warehouse.builder()
                .id(10L)
                .code("WH-TEST-01")
                .name("Kho Thử Nghiệm 01")
                .address("Lô C1, KCN Sài Đồng, Long Biên, Hà Nội")
                .manager(managerUser)
                .status("ACTIVE")
                .build();

        sampleLocation = WarehouseLocation.builder()
                .id(100L)
                .warehouse(sampleWarehouse)
                .code("RACK-A01")
                .name("Kệ A01 - Tầng 1")
                .locationType("RACK")
                .zone("Khu A")
                .shelf("Kệ 01")
                .status("ACTIVE")
                .build();
    }

    // ==============================================================
    // 1. KHO HÀNG (WAREHOUSE CRUD)
    // ==============================================================

    @Test
    @DisplayName("S5-03: Khai báo kho hàng thành công với đầy đủ mã, tên, địa chỉ, người phụ trách, trạng thái")
    void createWarehouse_success() {
        WarehouseCreateRequest req = WarehouseCreateRequest.builder()
                .code("WH-HN01")
                .name("Kho Tổng Hà Nội Mới")
                .address("KCN Tiên Sơn, Bắc Ninh")
                .managerId(2L)
                .status("ACTIVE")
                .build();

        when(warehouseRepository.existsByCodeIgnoreCase("WH-HN01")).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(managerUser));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> {
            Warehouse w = inv.getArgument(0);
            w.setId(11L);
            return w;
        });

        WarehouseResponse res = warehouseService.create(req, whManagerActor);

        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo(11L);
        assertThat(res.getCode()).isEqualTo("WH-HN01");
        assertThat(res.getName()).isEqualTo("Kho Tổng Hà Nội Mới");
        assertThat(res.getAddress()).isEqualTo("KCN Tiên Sơn, Bắc Ninh");
        assertThat(res.getStatus()).isEqualTo("ACTIVE");
        assertThat(res.getManager()).isNotNull();
        assertThat(res.getManager().id()).isEqualTo(2L);
        assertThat(res.getManagerPhone()).isEqualTo("0988776655");
    }

    @Test
    @DisplayName("S5-03: Trùng mã kho -> Báo lỗi DUPLICATE_WAREHOUSE_CODE")
    void createWarehouse_duplicateCode_throwsException() {
        WarehouseCreateRequest req = WarehouseCreateRequest.builder()
                .code("WH-TEST-01")
                .name("Kho Trùng Mã")
                .build();

        when(warehouseRepository.existsByCodeIgnoreCase("WH-TEST-01")).thenReturn(true);

        assertThatThrownBy(() -> warehouseService.create(req, whManagerActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã tồn tại trong hệ thống");
    }

    @Test
    @DisplayName("S5-03: Cập nhật kho hàng thành công")
    void updateWarehouse_success() {
        WarehouseUpdateRequest req = WarehouseUpdateRequest.builder()
                .name("Kho Thử Nghiệm Đổi Tên")
                .address("Địa chỉ mới cập nhật")
                .managerId(2L)
                .status("ACTIVE")
                .build();

        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(sampleWarehouse));
        when(userRepository.findById(2L)).thenReturn(Optional.of(managerUser));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        WarehouseResponse res = warehouseService.update(10L, req, whManagerActor);

        assertThat(res.getName()).isEqualTo("Kho Thử Nghiệm Đổi Tên");
        assertThat(res.getAddress()).isEqualTo("Địa chỉ mới cập nhật");
        assertThat(sampleWarehouse.getName()).isEqualTo("Kho Thử Nghiệm Đổi Tên");
    }

    @Test
    @DisplayName("S5-03: Xóa kho hàng có vị trí liên quan -> Chuyển sang INACTIVE (Soft Delete)")
    void deleteWarehouse_withLocations_softDeletes() {
        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(sampleWarehouse));
        when(locationRepository.countByWarehouse_Id(10L)).thenReturn(3L);
        when(customerRepository.findAll()).thenReturn(List.of());

        warehouseService.delete(10L, whManagerActor);

        assertThat(sampleWarehouse.getStatus()).isEqualTo("INACTIVE");
        verify(warehouseRepository).save(sampleWarehouse);
        verify(warehouseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("S5-03: Xóa kho hàng không có dữ liệu ràng buộc -> Xóa cứng thành công")
    void deleteWarehouse_empty_hardDeletes() {
        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(sampleWarehouse));
        when(locationRepository.countByWarehouse_Id(10L)).thenReturn(0L);
        when(customerRepository.findAll()).thenReturn(List.of());

        warehouseService.delete(10L, whManagerActor);

        verify(warehouseRepository).delete(sampleWarehouse);
    }

    // ==============================================================
    // 2. VỊ TRÍ LƯU TRONG KHO (LOCATION / SHELF / ZONE CRUD)
    // ==============================================================

    @Test
    @DisplayName("S5-03: Khai báo vị trí lưu trong kho ở mức kệ thành công")
    void createLocation_rack_success() {
        WarehouseLocationCreateRequest req = WarehouseLocationCreateRequest.builder()
                .code("KE-B02")
                .name("Kệ B02 - Khu Đồ Uống")
                .locationType("RACK")
                .zone("Khu B")
                .shelf("Kệ 02")
                .description("Chứa nước ngọt các loại")
                .status("ACTIVE")
                .build();

        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(sampleWarehouse));
        when(locationRepository.existsByWarehouse_IdAndCodeIgnoreCase(10L, "KE-B02")).thenReturn(false);
        when(locationRepository.save(any(WarehouseLocation.class))).thenAnswer(inv -> {
            WarehouseLocation l = inv.getArgument(0);
            l.setId(101L);
            return l;
        });

        WarehouseLocationResponse res = warehouseService.createLocation(10L, req, whManagerActor);

        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo(101L);
        assertThat(res.getCode()).isEqualTo("KE-B02");
        assertThat(res.getLocationType()).isEqualTo("RACK");
        assertThat(res.getZone()).isEqualTo("Khu B");
        assertThat(res.getShelf()).isEqualTo("Kệ 02");
        assertThat(res.getWarehouseCode()).isEqualTo("WH-TEST-01");
    }

    @Test
    @DisplayName("S5-03: Khai báo vị trí lưu trong kho ở mức khu thành công")
    void createLocation_zone_success() {
        WarehouseLocationCreateRequest req = WarehouseLocationCreateRequest.builder()
                .code("ZONE-C")
                .name("Khu C - Khu vực bia rượu")
                .locationType("ZONE")
                .zone("Khu C")
                .status("ACTIVE")
                .build();

        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(sampleWarehouse));
        when(locationRepository.existsByWarehouse_IdAndCodeIgnoreCase(10L, "ZONE-C")).thenReturn(false);
        when(locationRepository.save(any(WarehouseLocation.class))).thenAnswer(inv -> {
            WarehouseLocation l = inv.getArgument(0);
            l.setId(102L);
            return l;
        });

        WarehouseLocationResponse res = warehouseService.createLocation(10L, req, whManagerActor);

        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo("ZONE-C");
        assertThat(res.getLocationType()).isEqualTo("ZONE");
    }

    @Test
    @DisplayName("S5-03: Trùng mã vị trí trong cùng kho -> Báo lỗi DUPLICATE_LOCATION_CODE")
    void createLocation_duplicateCode_throwsException() {
        WarehouseLocationCreateRequest req = WarehouseLocationCreateRequest.builder()
                .code("RACK-A01")
                .name("Kệ Trùng Mã")
                .build();

        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(sampleWarehouse));
        when(locationRepository.existsByWarehouse_IdAndCodeIgnoreCase(10L, "RACK-A01")).thenReturn(true);

        assertThatThrownBy(() -> warehouseService.createLocation(10L, req, whManagerActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã tồn tại trong kho");
    }

    @Test
    @DisplayName("S5-03: Cập nhật và xóa vị trí lưu trong kho")
    void updateAndDeleteLocation_success() {
        WarehouseLocationUpdateRequest updateReq = WarehouseLocationUpdateRequest.builder()
                .name("Kệ A01 - Đã cập nhật mô tả")
                .locationType("RACK")
                .zone("Khu A-VIP")
                .shelf("Kệ 01-Tầng 3")
                .status("ACTIVE")
                .build();

        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(sampleWarehouse));
        when(locationRepository.findByWarehouse_IdAndId(10L, 100L)).thenReturn(Optional.of(sampleLocation));
        when(locationRepository.save(any(WarehouseLocation.class))).thenAnswer(inv -> inv.getArgument(0));

        WarehouseLocationResponse updated = warehouseService.updateLocation(10L, 100L, updateReq, whManagerActor);
        assertThat(updated.getName()).isEqualTo("Kệ A01 - Đã cập nhật mô tả");
        assertThat(updated.getZone()).isEqualTo("Khu A-VIP");

        // Xóa vị trí
        warehouseService.deleteLocation(10L, 100L, whManagerActor);
        verify(locationRepository).delete(sampleLocation);
    }

    // ==============================================================
    // 3. GÁN KHO PHỤC VỤ MẶC ĐỊNH CHO ĐẠI LÝ
    // ==============================================================

    @Test
    @DisplayName("S5-03: Gán kho phục vụ mặc định cho đại lý")
    void assignDefaultWarehouse_success() {
        when(customerService.assignDefaultWarehouse(eq(5L), eq(10L), eq(whManagerActor)))
                .thenReturn(mock(CustomerResponse.class));

        CustomerResponse response = warehouseService.assignDefaultWarehouse(5L, 10L, whManagerActor);

        assertThat(response).isNotNull();
        verify(customerService).assignDefaultWarehouse(5L, 10L, whManagerActor);
    }
}
