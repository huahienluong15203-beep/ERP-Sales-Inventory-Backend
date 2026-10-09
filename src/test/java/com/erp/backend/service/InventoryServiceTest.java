package com.erp.backend.service;

import com.erp.backend.dto.inventory.StockInfoDto;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock private InventoryRepository inventoryRepository;
    @Mock private WarehouseRepository warehouseRepository;

    @InjectMocks private InventoryService service;

    private Warehouse whNorth;
    private Warehouse whCentral;
    private Warehouse whSouth;
    private Product beer;

    @BeforeEach
    void setUp() {
        whNorth = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
        whCentral = Warehouse.builder().id(2L).code("WH-MT01").name("Kho Miền Trung").status("ACTIVE").build();
        whSouth = Warehouse.builder().id(3L).code("WH-MN01").name("Kho Tổng Miền Nam").status("ACTIVE").build();

        beer = Product.builder().id(10L).sku("BIA-HN-01").name("Bia Hà Nội").baseUnit("Lon").status("ACTIVE").build();
    }

    // =========================================================================
    // TIÊU CHÍ 1: Mỗi dòng hàng hiển thị tồn khả dụng của kho phục vụ đại lý đó
    // =========================================================================

    @Test
    @DisplayName("S4-03 AC1: Đại lý ở Miền Bắc -> Kho phục vụ là WH-MB01 (Kho Tổng Miền Bắc)")
    void resolveWarehouse_northRegion() {
        Region north = Region.builder().id(1L).code("MB").name("Miền Bắc").build();
        Customer customer = Customer.builder().id(100L).code("DL-HN").name("Đại lý Hà Nội")
                .region(north).address("Hoàn Kiếm, Hà Nội").build();

        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(whNorth));

        Warehouse result = service.resolveWarehouseForCustomer(customer);

        assertThat(result.getCode()).isEqualTo("WH-MB01");
        assertThat(result.getName()).isEqualTo("Kho Tổng Miền Bắc");
    }

    @Test
    @DisplayName("S4-03 AC1: Đại lý ở Miền Trung -> Kho phục vụ là WH-MT01 (Kho Miền Trung)")
    void resolveWarehouse_centralRegion() {
        Region central = Region.builder().id(2L).code("MT").name("Miền Trung").build();
        Customer customer = Customer.builder().id(101L).code("DL-DN").name("Đại lý Đà Nẵng")
                .region(central).address("Hải Châu, Đà Nẵng").build();

        when(warehouseRepository.findByCodeIgnoreCase("WH-MT01")).thenReturn(Optional.of(whCentral));

        Warehouse result = service.resolveWarehouseForCustomer(customer);

        assertThat(result.getCode()).isEqualTo("WH-MT01");
        assertThat(result.getName()).isEqualTo("Kho Miền Trung");
    }

    @Test
    @DisplayName("S4-03 AC1: Đại lý ở Miền Nam -> Kho phục vụ là WH-MN01 (Kho Tổng Miền Nam)")
    void resolveWarehouse_southRegion() {
        Region south = Region.builder().id(3L).code("MN").name("Miền Nam").build();
        Customer customer = Customer.builder().id(102L).code("DL-SG").name("Đại lý Sài Gòn")
                .region(south).address("Quận 1, TP. Hồ Chí Minh").build();

        when(warehouseRepository.findByCodeIgnoreCase("WH-MN01")).thenReturn(Optional.of(whSouth));

        Warehouse result = service.resolveWarehouseForCustomer(customer);

        assertThat(result.getCode()).isEqualTo("WH-MN01");
        assertThat(result.getName()).isEqualTo("Kho Tổng Miền Nam");
    }

    // =========================================================================
    // TIÊU CHÍ 2: Tồn khả dụng = Tồn thực tế - Tồn đang giữ chỗ cho đơn khác
    // =========================================================================

    @Test
    @DisplayName("S4-03 AC2: Tính đúng tồn khả dụng = physicalStock - reservedStock")
    void getStockInfo_calculatesAvailableCorrectly() {
        Inventory inv = Inventory.builder()
                .id(1L)
                .warehouse(whNorth)
                .product(beer)
                .physicalStock(new BigDecimal("150"))
                .reservedStock(new BigDecimal("50"))
                .build();

        when(inventoryRepository.findByWarehouse_IdAndProduct_Id(1L, 10L)).thenReturn(Optional.of(inv));

        StockInfoDto stock = service.getStockInfo(whNorth, beer);

        assertThat(stock.warehouseCode()).isEqualTo("WH-MB01");
        assertThat(stock.physicalStock()).isEqualByComparingTo("150");
        assertThat(stock.reservedStock()).isEqualByComparingTo("50");
        assertThat(stock.availableStock()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("S4-03 AC2: Mặt hàng chưa có tồn kho thì trả về 0")
    void getStockInfo_noInventoryReturnsZero() {
        when(inventoryRepository.findByWarehouse_IdAndProduct_Id(1L, 10L)).thenReturn(Optional.empty());

        StockInfoDto stock = service.getStockInfo(whNorth, beer);

        assertThat(stock.physicalStock()).isEqualByComparingTo("0");
        assertThat(stock.reservedStock()).isEqualByComparingTo("0");
        assertThat(stock.availableStock()).isEqualByComparingTo("0");
    }

    // =========================================================================
    // TIÊU CHÍ 3: Đặt vượt tồn khả dụng bị chặn, có gợi ý số lượng tối đa còn đặt được
    // =========================================================================

    @Test
    @DisplayName("S4-03 AC3: Đặt vượt tồn khả dụng bị chặn và gợi ý số lượng tối đa còn đặt được")
    void checkAndReserveStock_overStock_throwsConflictWithSuggestion() {
        // Tồn thực tế 20, giữ chỗ 15 -> khả dụng chỉ còn 5 lon
        Inventory inv = Inventory.builder()
                .id(1L)
                .warehouse(whNorth)
                .product(beer)
                .physicalStock(new BigDecimal("20"))
                .reservedStock(new BigDecimal("15"))
                .build();

        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(whNorth));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inv));

        Customer customer = Customer.builder().id(100L).address("Hà Nội").build();
        SalesOrder order = SalesOrder.builder().id(999L).customer(customer).build();
        SalesOrderLine line = SalesOrderLine.builder()
                .product(beer)
                .productSku(beer.getSku())
                .productName(beer.getName())
                .unitName("Lon")
                .conversionFactor(BigDecimal.ONE)
                .quantity(new BigDecimal("10"))
                .baseQuantity(new BigDecimal("10"))
                .build();
        order.addLine(line);

        assertThatThrownBy(() -> service.checkAndReserveStock(order))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không đủ tồn khả dụng")
                .hasMessageContaining("còn 5 Lon")
                .hasMessageContaining("yêu cầu 10 Lon");

        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("S4-03 AC3: Đặt theo Đơn vị quy đổi (Thùng x24) vượt tồn -> Gợi ý số thùng tối đa còn đặt được")
    void checkAndReserveStock_overStockWithConversionUnit_suggestsCorrectUnitQuantity() {
        // Khả dụng còn 30 lon (30/24 = 1 thùng tối đa)
        Inventory inv = Inventory.builder()
                .id(1L)
                .warehouse(whNorth)
                .product(beer)
                .physicalStock(new BigDecimal("30"))
                .reservedStock(BigDecimal.ZERO)
                .build();

        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(whNorth));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inv));

        Customer customer = Customer.builder().id(100L).address("Hà Nội").build();
        SalesOrder order = SalesOrder.builder().id(999L).customer(customer).build();
        SalesOrderLine line = SalesOrderLine.builder()
                .product(beer)
                .productSku(beer.getSku())
                .productName(beer.getName())
                .unitName("Thùng")
                .conversionFactor(new BigDecimal("24"))
                .quantity(new BigDecimal("2")) // Yêu cầu 2 thùng = 48 lon > 30 lon
                .baseQuantity(new BigDecimal("48"))
                .build();
        order.addLine(line);

        assertThatThrownBy(() -> service.checkAndReserveStock(order))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("còn 1 Thùng")
                .hasMessageContaining("yêu cầu 2 Thùng");
    }

    // =========================================================================
    // TIÊU CHÍ 4: Hai người cùng chốt đơn trên SKU sắp hết phải cho kết quả đúng, không âm tồn
    // =========================================================================

    @Test
    @DisplayName("S4-03 AC4: Hai người cùng chốt đơn: người 1 thành công (tăng giữ chỗ), người 2 bị chặn vì hết tồn khả dụng, không âm tồn")
    void checkAndReserveStock_concurrencySimulation_noNegativeStock() {
        // Tồn thực tế 10, chưa ai giữ chỗ -> Khả dụng = 10
        Inventory inv = Inventory.builder()
                .id(1L)
                .warehouse(whNorth)
                .product(beer)
                .physicalStock(new BigDecimal("10"))
                .reservedStock(BigDecimal.ZERO)
                .build();

        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(whNorth));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inv));

        Customer customer = Customer.builder().id(100L).address("Hà Nội").build();

        // Người 1 chốt đơn 8 lon
        SalesOrder order1 = SalesOrder.builder().id(1001L).customer(customer).build();
        order1.addLine(SalesOrderLine.builder()
                .product(beer).productSku(beer.getSku()).productName(beer.getName())
                .unitName("Lon").conversionFactor(BigDecimal.ONE)
                .quantity(new BigDecimal("8")).baseQuantity(new BigDecimal("8"))
                .build());

        service.checkAndReserveStock(order1);

        // Sau khi Người 1 chốt: reservedStock tăng lên 8, khả dụng chỉ còn 10 - 8 = 2
        assertThat(inv.getReservedStock()).isEqualByComparingTo("8");
        assertThat(inv.getAvailableStock()).isEqualByComparingTo("2");
        verify(inventoryRepository, times(1)).save(inv);

        // Người 2 chốt đơn 5 lon (yêu cầu 5 > khả dụng còn 2)
        SalesOrder order2 = SalesOrder.builder().id(1002L).customer(customer).build();
        order2.addLine(SalesOrderLine.builder()
                .product(beer).productSku(beer.getSku()).productName(beer.getName())
                .unitName("Lon").conversionFactor(BigDecimal.ONE)
                .quantity(new BigDecimal("5")).baseQuantity(new BigDecimal("5"))
                .build());

        // Người 2 bị từ chối chốt đơn vì không đủ hàng
        assertThatThrownBy(() -> service.checkAndReserveStock(order2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không đủ tồn khả dụng")
                .hasMessageContaining("còn 2 Lon");

        // Đảm bảo tồn kho không bị âm
        assertThat(inv.getAvailableStock()).isEqualByComparingTo("2");
        assertThat(inv.getReservedStock()).isEqualByComparingTo("8");
        assertThat(inv.getPhysicalStock()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("S4-03: Khi đơn hàng bị Từ chối hoặc Trả lại sửa -> Giải phóng hàng giữ chỗ an toàn")
    void releaseReservedStock_decreasesReservedCorrectly() {
        Inventory inv = Inventory.builder()
                .id(1L)
                .warehouse(whNorth)
                .product(beer)
                .physicalStock(new BigDecimal("50"))
                .reservedStock(new BigDecimal("20"))
                .build();

        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(whNorth));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inv));

        Customer customer = Customer.builder().id(100L).address("Hà Nội").build();
        SalesOrder order = SalesOrder.builder().id(1001L).customer(customer).build();
        order.addLine(SalesOrderLine.builder()
                .product(beer).productSku(beer.getSku()).productName(beer.getName())
                .unitName("Lon").conversionFactor(BigDecimal.ONE)
                .quantity(new BigDecimal("15")).baseQuantity(new BigDecimal("15"))
                .build());

        service.releaseReservedStock(order);

        // reservedStock giảm từ 20 xuống 5 (giải phóng 15 lon)
        assertThat(inv.getReservedStock()).isEqualByComparingTo("5");
        assertThat(inv.getAvailableStock()).isEqualByComparingTo("45");
        verify(inventoryRepository).save(inv);
    }
}
