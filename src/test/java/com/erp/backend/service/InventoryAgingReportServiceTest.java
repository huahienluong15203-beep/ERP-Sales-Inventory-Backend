package com.erp.backend.service;

import com.erp.backend.dto.inventory.aging.*;
import com.erp.backend.entity.*;
import com.erp.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.erp.backend.exception.BusinessException;
import com.erp.backend.security.UserDetailsImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * S8-07: Kiểm thử Báo cáo tồn kho và vòng quay hàng hóa.
 * - Test thuật toán tính số ngày bình quân của từng SKU.
 * - Test nhận diện hàng chậm luân chuyển chôn vốn.
 * - Test giá trị tồn theo kho và theo nhóm hàng.
 * - Test xuất file Excel báo cáo.
 */
@ExtendWith(MockitoExtension.class)
class InventoryAgingReportServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductLotRepository productLotRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private ProductCategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @InjectMocks
    private InventoryAgingReportService agingReportService;

    private final ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
    private final LocalDate today = LocalDate.of(2026, 10, 16);
    private final Clock fixedClock = Clock.fixed(today.atTime(10, 0).atZone(zone).toInstant(), zone);

    private Warehouse warehouse1;
    private ProductCategory categoryDrinks;
    private Product product1;
    private Product product2;
    private Inventory inventory1;
    private Inventory inventory2;

    @BeforeEach
    void setUp() {
        agingReportService.clock = fixedClock;

        warehouse1 = Warehouse.builder()
                .id(1L)
                .code("WH-MB01")
                .name("Kho Tổng Miền Bắc")
                .build();

        categoryDrinks = ProductCategory.builder()
                .id(10L)
                .code("DO_UONG")
                .name("Đồ uống giải khát")
                .level(1)
                .build();

        product1 = Product.builder()
                .id(100L)
                .sku("BIA-HN-01")
                .name("Bia Hà Nội Lon 330ml")
                .productCategory(categoryDrinks)
                .category("Đồ uống giải khát")
                .baseUnit("Lon")
                .costPrice(new BigDecimal("12000"))
                .build();

        product2 = Product.builder()
                .id(200L)
                .sku("RUOU-VANG-01")
                .name("Rượu Vang Đỏ Thượng Hạng")
                .productCategory(categoryDrinks)
                .category("Đồ uống giải khát")
                .baseUnit("Chai")
                .costPrice(new BigDecimal("250000"))
                .build();

        inventory1 = Inventory.builder()
                .id(1L)
                .warehouse(warehouse1)
                .product(product1)
                .physicalStock(new BigDecimal("150"))
                .build();

        inventory2 = Inventory.builder()
                .id(2L)
                .warehouse(warehouse1)
                .product(product2)
                .physicalStock(new BigDecimal("20"))
                .build();
    }

    @Test
    @DisplayName("S8-07: Thuật toán tính số ngày lưu kho bình quân có trọng số theo tồn từng lô")
    void testCalculateAverageAgingDays_WithProductLots_ComputesWeightedAverage() {
        // Lô 1: tồn 100 lon, sản xuất cách đây 20 ngày (26/09/2026) -> 100 * 20 = 2000
        ProductLot lot1 = ProductLot.builder()
                .id(1L)
                .product(product1)
                .warehouse(warehouse1)
                .batchNumber("LOT-2609-01")
                .manufactureDate(today.minusDays(20))
                .quantity(new BigDecimal("100"))
                .build();

        // Lô 2: tồn 50 lon, sản xuất cách đây 50 ngày (27/08/2026) -> 50 * 50 = 2500
        ProductLot lot2 = ProductLot.builder()
                .id(2L)
                .product(product1)
                .warehouse(warehouse1)
                .batchNumber("LOT-2708-01")
                .manufactureDate(today.minusDays(50))
                .quantity(new BigDecimal("50"))
                .build();

        when(productLotRepository.findByProduct_IdAndWarehouse_Id(100L, 1L))
                .thenReturn(List.of(lot1, lot2));

        // Bình quân = (2000 + 2500) / 150 = 4500 / 150 = 30 ngày
        long avgDays = agingReportService.calculateAverageAgingDays(product1, warehouse1, inventory1, today);

        assertThat(avgDays).isEqualTo(30L);
    }

    @Test
    @DisplayName("S8-07: Thuật toán số ngày bình quân fallback theo ngày tạo tồn kho khi không có lô")
    void testCalculateAverageAgingDays_WithoutLots_FallsBackToInventoryCreationDate() {
        when(productLotRepository.findByProduct_IdAndWarehouse_Id(200L, 1L))
                .thenReturn(List.of());

        // Tồn kho tạo cách đây 100 ngày
        inventory2.setCreatedAt(today.minusDays(100).atStartOfDay());

        long avgDays = agingReportService.calculateAverageAgingDays(product2, warehouse1, inventory2, today);

        assertThat(avgDays).isEqualTo(100L);
    }

    @Test
    @DisplayName("S8-07: Báo cáo nhận diện chính xác hàng chậm luân chuyển (> 60 ngày) và chôn vốn (> 90 ngày)")
    void testGetAgingReport_DetectsSlowMovingAndStagnantStock() {
        when(inventoryRepository.findAll()).thenReturn(List.of(inventory1, inventory2));

        // product1: 30 ngày -> Nhóm 0_30, FAST
        ProductLot lot1 = ProductLot.builder()
                .id(1L)
                .product(product1)
                .warehouse(warehouse1)
                .batchNumber("LOT-01")
                .manufactureDate(today.minusDays(30))
                .quantity(new BigDecimal("150"))
                .build();
        when(productLotRepository.findByProduct_IdAndWarehouse_Id(100L, 1L)).thenReturn(List.of(lot1));

        // product2: 120 ngày -> Nhóm OVER_90, STAGNANT
        ProductLot lot2 = ProductLot.builder()
                .id(2L)
                .product(product2)
                .warehouse(warehouse1)
                .batchNumber("LOT-02")
                .manufactureDate(today.minusDays(120))
                .quantity(new BigDecimal("20"))
                .build();
        when(productLotRepository.findByProduct_IdAndWarehouse_Id(200L, 1L)).thenReturn(List.of(lot2));

        InventoryAgingReportResponse report = agingReportService.getAgingReport("ALL", "ALL", "ALL", null);

        assertThat(report.getItems()).hasSize(2);

        // Item 1: rượu vang (120 ngày, sắp lên đầu)
        InventoryAgingItemResponse stagnantItem = report.getItems().get(0);
        assertThat(stagnantItem.getProductSku()).isEqualTo("RUOU-VANG-01");
        assertThat(stagnantItem.getAverageAgingDays()).isEqualTo(120L);
        assertThat(stagnantItem.getAgingBracket()).isEqualTo("OVER_90");
        assertThat(stagnantItem.getTurnoverSpeed()).isEqualTo("STAGNANT");
        // Giá trị = 20 * 250,000 = 5,000,000
        assertThat(stagnantItem.getTotalValue()).isEqualByComparingTo("5000000");

        // Item 2: bia (30 ngày)
        InventoryAgingItemResponse fastItem = report.getItems().get(1);
        assertThat(fastItem.getProductSku()).isEqualTo("BIA-HN-01");
        assertThat(fastItem.getAverageAgingDays()).isEqualTo(30L);
        assertThat(fastItem.getAgingBracket()).isEqualTo("0_30");
        assertThat(fastItem.getTurnoverSpeed()).isEqualTo("FAST");
        // Giá trị = 150 * 12,000 = 1,800,000
        assertThat(fastItem.getTotalValue()).isEqualByComparingTo("1800000");

        // Kiểm tra Summary
        InventoryAgingSummaryResponse summary = report.getSummary();
        // Tổng giá trị = 1.8tr + 5tr = 6.8tr
        assertThat(summary.getTotalInventoryValue()).isEqualByComparingTo("6800000");
        // Số SKU chậm luân chuyển (> 60 ngày) = 1 (rượu vang)
        assertThat(summary.getSlowMovingCount()).isEqualTo(1);
        assertThat(summary.getSlowMovingRatio()).isEqualTo(50.0);
        // Vốn chôn (> 90 ngày) = 5,000,000 đ
        assertThat(summary.getStagnantValue()).isEqualByComparingTo("5000000");
    }

    @Test
    @DisplayName("S8-07: Thống kê giá trị tồn theo kho và theo nhóm hàng")
    void testGetSummaryByWarehouseAndCategory() {
        when(inventoryRepository.findAll()).thenReturn(List.of(inventory1, inventory2));

        // 1. Thống kê theo kho
        List<InventoryAgingWarehouseValueResponse> whSummary = agingReportService.getSummaryByWarehouse();
        assertThat(whSummary).hasSize(1);
        assertThat(whSummary.get(0).getWarehouseCode()).isEqualTo("WH-MB01");
        assertThat(whSummary.get(0).getSkuCount()).isEqualTo(2);
        assertThat(whSummary.get(0).getTotalValue()).isEqualByComparingTo("6800000");

        // 2. Thống kê theo nhóm hàng
        List<InventoryAgingCategoryValueResponse> catSummary = agingReportService.getSummaryByCategory();
        assertThat(catSummary).hasSize(1);
        assertThat(catSummary.get(0).getCategoryCode()).isEqualTo("DO_UONG");
        assertThat(catSummary.get(0).getSkuCount()).isEqualTo(2);
        assertThat(catSummary.get(0).getTotalValue()).isEqualByComparingTo("6800000");
    }

    @Test
    @DisplayName("S8-07: Xuất file Excel báo cáo luân chuyển kho (.xlsx) hợp lệ")
    void testExportToExcel_GeneratesValidExcelFile() throws Exception {
        when(inventoryRepository.findAll()).thenReturn(List.of(inventory1));

        byte[] excelBytes = agingReportService.exportToExcel("ALL", "ALL", "ALL", null);

        assertThat(excelBytes).isNotNull();
        assertThat(excelBytes.length).isGreaterThan(100);
    }

    @Test
    @DisplayName("TC807-02: Lô có ngày sản xuất sớm hơn ngày nhập kho -> Tính theo ngày NHẬP KHO thực tế")
    void testTC807_02_CalculateAverageAgingDays_UsesLotCreatedAtOverManufactureDate() {
        // Lô sản xuất cách đây 273 ngày, nhưng nhập kho cách đây 3 ngày
        ProductLot lot = ProductLot.builder()
                .id(15L)
                .product(product1)
                .warehouse(warehouse1)
                .batchNumber("LOT-EXP-273")
                .manufactureDate(today.minusDays(273))
                .createdAt(today.minusDays(3).atStartOfDay())
                .quantity(new BigDecimal("100"))
                .build();

        when(productLotRepository.findByProduct_IdAndWarehouse_Id(100L, 1L))
                .thenReturn(List.of(lot));

        long avgDays = agingReportService.calculateAverageAgingDays(product1, warehouse1, inventory1, today);

        // Phải ra 3 ngày (nhóm 0_30 / FAST), không được lấy 273 ngày (chôn vốn)
        assertThat(avgDays).isEqualTo(3L);
        assertThat(InventoryAgingReportService.determineAgingBracket(avgDays)).isEqualTo("0_30");
        assertThat(InventoryAgingReportService.determineTurnoverSpeed(avgDays)).isEqualTo("FAST");
    }

    @Test
    @DisplayName("TC807-03: SKU không quản lý lô đã bán hết rồi nhập lại -> Tính theo đợt nhập lại mới nhất")
    void testTC807_03_CalculateAverageAgingDays_WithoutLots_UsesLatestInboundMovementDate() {
        when(productLotRepository.findByProduct_IdAndWarehouse_Id(200L, 1L))
                .thenReturn(List.of());

        // Bản ghi tồn kho tạo từ 90 ngày trước
        inventory2.setCreatedAt(today.minusDays(90).atStartOfDay());

        // Đợt nhập kho mới nhất diễn ra cách đây 2 ngày
        LocalDateTime latestInbound = today.minusDays(2).atStartOfDay();

        long avgDays = agingReportService.calculateAverageAgingDays(
                product2, warehouse1, inventory2, today, null, latestInbound);

        // Phải ra 2 ngày (nhóm 0_30 / FAST)
        assertThat(avgDays).isEqualTo(2L);
        assertThat(InventoryAgingReportService.determineAgingBracket(avgDays)).isEqualTo("0_30");
        assertThat(InventoryAgingReportService.determineTurnoverSpeed(avgDays)).isEqualTo("FAST");
    }

    @Test
    @DisplayName("TC807-11: Validate tham số không hợp lệ trên URL -> Ném 400 Bad Request")
    void testTC807_11_ValidateInvalidParameters_ThrowsBadRequest() {
        // WarehouseId không phải số
        assertThatThrownBy(() -> agingReportService.getAgingReport("abc", "ALL", "ALL", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus().value()).isEqualTo(400);
                    assertThat(be.getMessage()).contains("Mã kho không hợp lệ");
                });

        // CategoryId không phải số
        assertThatThrownBy(() -> agingReportService.getAgingReport("1", "invalid_cat", "ALL", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus().value()).isEqualTo(400);
                    assertThat(be.getMessage()).contains("Mã nhóm hàng không hợp lệ");
                });

        // AgingBracket không hợp lệ
        assertThatThrownBy(() -> agingReportService.getAgingReport("1", "ALL", "INVALID_BRACKET", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus().value()).isEqualTo(400);
                    assertThat(be.getMessage()).contains("Nhóm tuổi hàng không hợp lệ");
                });
    }

    @Test
    @DisplayName("TC807-13: Phân quyền: Nhân viên kho / Kế toán bị ẩn giá vốn và tổng giá trị tồn kho")
    void testTC807_13_WarehouseStaffOrAccountant_CostPriceAndTotalValueMasked() {
        when(inventoryRepository.findAll()).thenReturn(List.of(inventory1));

        UserDetailsImpl warehouseStaff = new UserDetailsImpl(
                5L, "staff", "Warehouse Staff", "staff@erp.com", "pass", true,
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE")));

        User userEntity = User.builder()
                .id(5L)
                .warehouses(java.util.Set.of(warehouse1))
                .build();
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(userEntity));

        InventoryAgingReportResponse report = agingReportService.getAgingReport(
                "1", "ALL", "ALL", null, warehouseStaff);

        assertThat(report.getItems()).hasSize(1);
        InventoryAgingItemResponse item = report.getItems().get(0);
        // Nhân viên kho không được xem costPrice và totalValue
        assertThat(item.getCostPrice()).isNull();
        assertThat(item.getTotalValue()).isNull();

        // Summary cũng bị ẩn giá trị tiền
        assertThat(report.getSummary().getTotalInventoryValue()).isNull();
        assertThat(report.getSummary().getStagnantValue()).isNull();
    }

    @Test
    @DisplayName("TC807-14: Phân quyền IDOR: Nhân viên kho xem kho khác chi nhánh -> Ném 403 Forbidden")
    void testTC807_14_WarehouseStaff_AccessDifferentWarehouse_Throws403Forbidden() {
        UserDetailsImpl warehouseStaff = new UserDetailsImpl(
                5L, "staff", "Warehouse Staff", "staff@erp.com", "pass", true,
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE")));

        User userEntity = User.builder()
                .id(5L)
                .warehouses(java.util.Set.of(warehouse1)) // được gán kho 1
                .build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(userEntity));

        // Nhân viên kho 1 cố tình truy vấn kho 2
        assertThatThrownBy(() -> agingReportService.getAgingReport(
                "2", "ALL", "ALL", null, warehouseStaff))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus().value()).isEqualTo(403);
                    assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
    }
}
