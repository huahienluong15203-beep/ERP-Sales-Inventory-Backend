package com.erp.backend.service;

import com.erp.backend.dto.inventory.alert.MinStockAlertResponse;
import com.erp.backend.dto.inventory.alert.MinStockSummaryResponse;
import com.erp.backend.dto.inventory.alert.UpdateMinThresholdPayload;
import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.MinStockThreshold;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit test MinStockAlertService - S5-09 Cảnh báo tồn dưới mức tối thiểu")
class MinStockAlertServiceTest {

    @Mock private MinStockThresholdRepository minStockThresholdRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private ProductRepository productRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductLotRepository productLotRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private MinStockAlertService minStockAlertService;

    private Warehouse warehouse;
    private Product product1;
    private Product product2;
    private UserDetailsImpl adminActor;

    @BeforeEach
    void setUp() {
        warehouse = Warehouse.builder()
                .id(1L)
                .code("WH-MB01")
                .name("Kho Tổng Miền Bắc")
                .status("ACTIVE")
                .build();

        product1 = Product.builder()
                .id(10L)
                .sku("BEV-PEPSI-330")
                .name("Nước ngọt Pepsi 330ml")
                .category("Nước giải khát")
                .baseUnit("Thùng")
                .status("ACTIVE")
                .build();

        product2 = Product.builder()
                .id(20L)
                .sku("BEV-AQUAFINA-500")
                .name("Nước khoáng Aquafina 500ml")
                .category("Nước giải khát")
                .baseUnit("Thùng")
                .status("ACTIVE")
                .build();

        adminActor = new UserDetailsImpl(
                1L, "admin", "Admin User", "admin@erp.com", "pass",
                true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        org.mockito.Mockito.lenient().when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(warehouse));
        org.mockito.Mockito.lenient().when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));
    }

    @Test
    @DisplayName("AC1 & AC2: Tính toán chính xác cảnh báo CRITICAL khi tồn thực tế < 50% định mức")
    void fetchMinStockConfigs_CriticalAlert() {
        when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));
        when(productRepository.findAll()).thenReturn(List.of(product1));

        Inventory inv = Inventory.builder()
                .warehouse(warehouse)
                .product(product1)
                .physicalStock(new BigDecimal("18"))
                .reservedStock(new BigDecimal("3"))
                .build();
        when(inventoryRepository.findByWarehouse_Id(1L)).thenReturn(List.of(inv));

        MinStockThreshold thresh = MinStockThreshold.builder()
                .warehouse(warehouse)
                .product(product1)
                .minThreshold(new BigDecimal("100"))
                .build();
        when(minStockThresholdRepository.findByWarehouse_Id(1L)).thenReturn(List.of(thresh));
        when(productLotRepository.findByWarehouse_Id(1L)).thenReturn(Collections.emptyList());

        List<MinStockAlertResponse> responses = minStockAlertService.fetchMinStockConfigs("WH-MB01", false, null, adminActor);

        assertThat(responses).hasSize(1);
        MinStockAlertResponse res = responses.get(0);
        assertThat(res.getProductSku()).isEqualTo("BEV-PEPSI-330");
        assertThat(res.getMinThreshold()).isEqualByComparingTo("100");
        assertThat(res.getCurrentStock()).isEqualByComparingTo("18");
        assertThat(res.getAvailableStock()).isEqualByComparingTo("15");
        assertThat(res.isBelowThreshold()).isTrue();
        assertThat(res.getDeficitQuantity()).isEqualByComparingTo("82");
        assertThat(res.getSeverity()).isEqualTo("CRITICAL");
        assertThat(res.getSuggestedReorderQuantity()).isEqualByComparingTo("132"); // 82 + 50
    }

    @Test
    @DisplayName("AC2: Trạng thái SAFE khi tồn thực tế >= định mức tối thiểu")
    void fetchMinStockConfigs_SafeStatus() {
        when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));
        when(productRepository.findAll()).thenReturn(List.of(product2));

        Inventory inv = Inventory.builder()
                .warehouse(warehouse)
                .product(product2)
                .physicalStock(new BigDecimal("120"))
                .reservedStock(new BigDecimal("10"))
                .build();
        when(inventoryRepository.findByWarehouse_Id(1L)).thenReturn(List.of(inv));

        MinStockThreshold thresh = MinStockThreshold.builder()
                .warehouse(warehouse)
                .product(product2)
                .minThreshold(new BigDecimal("50"))
                .build();
        when(minStockThresholdRepository.findByWarehouse_Id(1L)).thenReturn(List.of(thresh));
        when(productLotRepository.findByWarehouse_Id(1L)).thenReturn(Collections.emptyList());

        List<MinStockAlertResponse> responses = minStockAlertService.fetchMinStockConfigs("WH-MB01", false, null, adminActor);

        assertThat(responses).hasSize(1);
        MinStockAlertResponse res = responses.get(0);
        assertThat(res.isBelowThreshold()).isFalse();
        assertThat(res.getSeverity()).isEqualTo("SAFE");
        assertThat(res.getDeficitQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("AC2: Trạng thái WARNING khi tồn < định mức nhưng vẫn >= 50% định mức")
    void fetchMinStockConfigs_WarningStatus() {
        when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));
        when(productRepository.findAll()).thenReturn(List.of(product1));

        Inventory inv = Inventory.builder()
                .warehouse(warehouse)
                .product(product1)
                .physicalStock(new BigDecimal("70"))
                .reservedStock(BigDecimal.ZERO)
                .build();
        when(inventoryRepository.findByWarehouse_Id(1L)).thenReturn(List.of(inv));

        MinStockThreshold thresh = MinStockThreshold.builder()
                .warehouse(warehouse)
                .product(product1)
                .minThreshold(new BigDecimal("100"))
                .build();
        when(minStockThresholdRepository.findByWarehouse_Id(1L)).thenReturn(List.of(thresh));
        when(productLotRepository.findByWarehouse_Id(1L)).thenReturn(Collections.emptyList());

        List<MinStockAlertResponse> responses = minStockAlertService.fetchMinStockConfigs("WH-MB01", false, null, adminActor);

        assertThat(responses).hasSize(1);
        MinStockAlertResponse res = responses.get(0);
        assertThat(res.isBelowThreshold()).isTrue();
        assertThat(res.getSeverity()).isEqualTo("WARNING");
        assertThat(res.getDeficitQuantity()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("AC1: Cập nhật định mức tồn tối thiểu thành công")
    void updateMinThreshold_Success() {
        when(productRepository.findBySkuIgnoreCase("BEV-PEPSI-330")).thenReturn(Optional.of(product1));
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(warehouse));

        MinStockThreshold savedThresh = MinStockThreshold.builder()
                .id(5L)
                .warehouse(warehouse)
                .product(product1)
                .minThreshold(new BigDecimal("150"))
                .build();
        when(minStockThresholdRepository.findByProduct_IdAndWarehouse_Id(10L, 1L)).thenReturn(Optional.empty());
        when(minStockThresholdRepository.save(any(MinStockThreshold.class))).thenReturn(savedThresh);

        UpdateMinThresholdPayload payload = UpdateMinThresholdPayload.builder()
                .productSku("BEV-PEPSI-330")
                .warehouseCode("WH-MB01")
                .minThreshold(new BigDecimal("150"))
                .build();

        MinStockAlertResponse res = minStockAlertService.updateMinThreshold(payload, adminActor);

        assertThat(res.getId()).isEqualTo("cfg-5");
        assertThat(res.getProductSku()).isEqualTo("BEV-PEPSI-330");
        assertThat(res.getMinThreshold()).isEqualByComparingTo("150");
        verify(minStockThresholdRepository).save(any(MinStockThreshold.class));
    }

    @Test
    @DisplayName("AC1: Báo lỗi khi định mức tồn tối thiểu < 0")
    void updateMinThreshold_ThrowsWhenNegative() {
        UpdateMinThresholdPayload payload = UpdateMinThresholdPayload.builder()
                .productSku("BEV-PEPSI-330")
                .warehouseCode("WH-MB01")
                .minThreshold(new BigDecimal("-5"))
                .build();

        assertThatThrownBy(() -> minStockAlertService.updateMinThreshold(payload, adminActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Định mức tồn tối thiểu không thể nhỏ hơn 0");
    }

    @Test
    @DisplayName("SCRUM-164: checkBelowThreshold chỉ trả về các mặt hàng dưới định mức")
    void checkBelowThreshold_ReturnsOnlyBelowItems() {
        when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));
        when(productRepository.findAll()).thenReturn(List.of(product1, product2));

        Inventory inv1 = Inventory.builder().warehouse(warehouse).product(product1).physicalStock(new BigDecimal("10")).build();
        Inventory inv2 = Inventory.builder().warehouse(warehouse).product(product2).physicalStock(new BigDecimal("200")).build();
        when(inventoryRepository.findByWarehouse_Id(1L)).thenReturn(List.of(inv1, inv2));

        MinStockThreshold thresh1 = MinStockThreshold.builder().warehouse(warehouse).product(product1).minThreshold(new BigDecimal("50")).build();
        MinStockThreshold thresh2 = MinStockThreshold.builder().warehouse(warehouse).product(product2).minThreshold(new BigDecimal("50")).build();
        when(minStockThresholdRepository.findByWarehouse_Id(1L)).thenReturn(List.of(thresh1, thresh2));
        when(productLotRepository.findByWarehouse_Id(1L)).thenReturn(Collections.emptyList());

        List<MinStockAlertResponse> belowItems = minStockAlertService.checkBelowThreshold("WH-MB01", adminActor);

        assertThat(belowItems).hasSize(1);
        assertThat(belowItems.get(0).getProductSku()).isEqualTo("BEV-PEPSI-330");
    }

    @Test
    @DisplayName("AC2: Thống kê số lượng cảnh báo phục vụ Dashboard kho")
    void getSummary_AggregatesCounts() {
        when(warehouseRepository.findAll()).thenReturn(List.of(warehouse));
        when(productRepository.findAll()).thenReturn(List.of(product1));

        Inventory inv1 = Inventory.builder().warehouse(warehouse).product(product1).physicalStock(new BigDecimal("10")).build();
        when(inventoryRepository.findByWarehouse_Id(1L)).thenReturn(List.of(inv1));

        MinStockThreshold thresh1 = MinStockThreshold.builder().warehouse(warehouse).product(product1).minThreshold(new BigDecimal("100")).build();
        when(minStockThresholdRepository.findByWarehouse_Id(1L)).thenReturn(List.of(thresh1));
        when(productLotRepository.findByWarehouse_Id(1L)).thenReturn(Collections.emptyList());

        MinStockSummaryResponse summary = minStockAlertService.getSummary("WH-MB01", adminActor);

        assertThat(summary.getTotalAlerts()).isEqualTo(1);
        assertThat(summary.getCriticalCount()).isEqualTo(1);
        assertThat(summary.getWarningCount()).isEqualTo(0);
        assertThat(summary.getCriticalItems()).hasSize(1);
    }
}
