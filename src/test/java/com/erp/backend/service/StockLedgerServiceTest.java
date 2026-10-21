package com.erp.backend.service;

import com.erp.backend.dto.inventory.StockLedgerCriteria;
import com.erp.backend.dto.inventory.StockLedgerRow;
import com.erp.backend.dto.inventory.WarehouseOptionResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.InventorySpecifications;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class StockLedgerServiceTest {

    @Mock private InventoryRepository inventoryRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private StockLedgerService service;

    private final Warehouse hn = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
    private final Warehouse hcm = Warehouse.builder().id(2L).code("WH-MN01").name("Kho Tổng Miền Nam").status("ACTIVE").build();

    private Inventory inv(String physical, String reserved) {
        ProductCategory cat = ProductCategory.builder().id(5L).name("Nước ngọt").level(2).build();
        Product p = Product.builder().id(10L).sku("SP-COCA").name("Coca lon").baseUnit("Lon").status("ACTIVE")
                .productCategory(cat).build();
        return Inventory.builder().id(100L).warehouse(hn).product(p)
                .physicalStock(new BigDecimal(physical)).reservedStock(new BigDecimal(reserved)).build();
    }

    @Test
    @DisplayName("S5-05: Ba cột tách bạch, khả dụng = thực tế - giữ chỗ")
    void row_threeColumns() {
        StockLedgerRow row = StockLedgerService.toRow(inv("1000.0000", "50.0000"));

        assertThat(row.physicalStock()).isEqualByComparingTo("1000");
        assertThat(row.reservedStock()).isEqualByComparingTo("50");
        assertThat(row.availableStock()).isEqualByComparingTo("950");
        assertThat(row.physicalStock().toPlainString()).isEqualTo("1000");
        assertThat(row.stockStatus()).isEqualTo(InventorySpecifications.IN_STOCK);
        assertThat(row.categoryName()).isEqualTo("Nước ngọt");
        assertThat(row.warehouseCode()).isEqualTo("WH-MB01");
    }

    @Test
    @DisplayName("S5-05: Trạng thái tồn: hết hàng / đã giữ chỗ hết / còn hàng")
    void stockStatus_rules() {
        assertThat(StockLedgerService.toRow(inv("0", "0")).stockStatus()).isEqualTo(InventorySpecifications.OUT_OF_STOCK);
        assertThat(StockLedgerService.toRow(inv("20", "20")).stockStatus()).isEqualTo(InventorySpecifications.FULLY_RESERVED);
        assertThat(StockLedgerService.toRow(inv("20", "20")).stockStatusLabel()).isEqualTo("Đã giữ chỗ hết");
        assertThat(StockLedgerService.toRow(inv("15", "5")).stockStatus()).isEqualTo(InventorySpecifications.IN_STOCK);
    }

    @Test
    @DisplayName("S5-05: Lọc nhóm hàng gồm cả nhóm con (cây 3 cấp)")
    void category_includesDescendants() {
        ProductCategory l1 = ProductCategory.builder().id(1L).name("Đồ uống").level(1).build();
        ProductCategory l2 = ProductCategory.builder().id(2L).name("Nước ngọt").level(2).parent(l1).build();
        ProductCategory l3 = ProductCategory.builder().id(3L).name("Có ga").level(3).parent(l2).build();
        ProductCategory other = ProductCategory.builder().id(9L).name("Gia vị").level(1).build();
        when(categoryRepository.findAllByOrderByLevelAscNameAsc()).thenReturn(List.of(l1, other, l2, l3));

        assertThat(service.categoryWithDescendants(1L)).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(service.categoryWithDescendants(2L)).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("S5-05: Nhóm hàng không tồn tại -> 404")
    void category_notFound() {
        when(categoryRepository.findAllByOrderByLevelAscNameAsc()).thenReturn(List.of());

        assertThatThrownBy(() -> service.categoryWithDescendants(77L)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("S5-05: Trạng thái tồn sai -> 400, không truy vấn DB")
    void invalidStatus_badRequest() {
        assertThatThrownBy(() -> service.search(null, null, "abc", null, null, 0, 20, actor(1, "ROLE_ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STOCK_STATUS");
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("S5-05: Trang quá lớn -> 400 thay vì lỗi 500")
    void hugePage_badRequest() {
        assertThatThrownBy(() -> service.search(null, null, null, null, null, 2_000_000_000, 100, actor(1, "ROLE_ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PAGE_TOO_LARGE");
    }

    @Test
    @DisplayName("S5-05: Cột sắp xếp lạ -> sắp mặc định theo kho, SKU; cỡ trang tối đa 100")
    void unknownSort_fallback() {
        when(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inv("10", "0"))));

        PageResponse<StockLedgerRow> res = service.search(null, null, "in_stock", " coca ", "costPrice,desc", 0, 500,
                actor(1, "ROLE_WH_MANAGER"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(inventoryRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort().getOrderFor("warehouse.code")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("costPrice")).isNull();
        assertThat(res.content()).hasSize(1);
    }

    @Test
    @DisplayName("S5-05: Sắp theo cột được phép")
    void allowedSort() {
        when(inventoryRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.search(null, null, null, null, "physicalStock,desc", 0, 20, actor(1, "ROLE_ADMIN"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(inventoryRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("physicalStock").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("S5-05: NV kho chỉ xem kho mình được gắn; QL kho xem mọi kho")
    void warehouseStaff_restricted() {
        User staff = User.builder().id(5L).username("wh_staff").warehouses(Set.of(hn)).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(staff));

        StockLedgerCriteria c = service.criteria(null, null, null, null, actor(5, "ROLE_WAREHOUSE"));

        assertThat(c.allowedWarehouseIds()).containsExactly(1L);
        assertThat(service.criteria(null, null, null, null, actor(6, "ROLE_WH_MANAGER")).allowedWarehouseIds()).isNull();
    }

    @Test
    @DisplayName("S5-05: Danh sách kho cho NV kho chỉ có kho được gắn")
    void warehouses_filteredForStaff() {
        User staff = User.builder().id(5L).username("wh_staff").warehouses(Set.of(hcm)).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(staff));
        when(warehouseRepository.findByStatusOrderByNameAsc("ACTIVE")).thenReturn(List.of(hn, hcm));

        List<WarehouseOptionResponse> res = service.warehouses("active", actor(5, "ROLE_WAREHOUSE"));

        assertThat(res).extracting(WarehouseOptionResponse::code).containsExactly("WH-MN01");
    }

    @Test
    @DisplayName("S5-05: Kho không tồn tại -> 404")
    void unknownWarehouse_notFound() {
        when(warehouseRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.criteria(99L, null, null, null, actor(1, "ROLE_ADMIN")))
                .isInstanceOf(BusinessException.class);
    }
}
