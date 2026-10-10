package com.erp.backend.service;

import com.erp.backend.dto.inventory.transfer.*;
import com.erp.backend.entity.*;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockTransferServiceTest {

    @Mock private StockTransferRepository stockTransferRepository;
    @Mock private StockTransferLineRepository stockTransferLineRepository;
    @Mock private ProductLotRepository productLotRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductRepository productRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private StockTransferService stockTransferService;

    private UserDetailsImpl whStaffActor;
    private Warehouse sourceWh;
    private Warehouse destWh;
    private Product sampleProduct;
    private Inventory sourceInventory;

    @BeforeEach
    void setUp() {
        whStaffActor = new UserDetailsImpl(
                1L, "wh_staff", "Thủ Kho Miền Bắc", "wh@erp.com", "pass", true,
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))
        );

        sourceWh = Warehouse.builder()
                .id(1L)
                .code("WH-MB01")
                .name("Kho Tổng Miền Bắc")
                .status("ACTIVE")
                .build();

        destWh = Warehouse.builder()
                .id(2L)
                .code("WH-MT01")
                .name("Kho Miền Trung")
                .status("ACTIVE")
                .build();

        sampleProduct = Product.builder()
                .id(10L)
                .sku("BEV-PEPSI-330")
                .name("Nước ngọt Pepsi lon 330ml")
                .baseUnit("Lon")
                .status("ACTIVE")
                .build();

        sourceInventory = Inventory.builder()
                .id(100L)
                .warehouse(sourceWh)
                .product(sampleProduct)
                .physicalStock(BigDecimal.valueOf(500))
                .reservedStock(BigDecimal.valueOf(50)) // Khả dụng = 450
                .build();
    }

    @Test
    @DisplayName("S5-07 AC1: Tạo phiếu chuyển kho nháp (DRAFT) thành công, kiểm tra tồn khả dụng hợp lệ")
    void createTransfer_draft_success() {
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(sourceWh));
        when(warehouseRepository.findByCodeIgnoreCase("WH-MT01")).thenReturn(Optional.of(destWh));
        when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(sourceInventory));
        when(stockTransferRepository.findCodesByPrefix(anyString())).thenReturn(Collections.emptyList());
        when(stockTransferRepository.save(any(StockTransfer.class))).thenAnswer(inv -> {
            StockTransfer t = inv.getArgument(0);
            t.setId(101L);
            return t;
        });

        CreateStockTransferLinePayload line = CreateStockTransferLinePayload.builder()
                .productId(10L)
                .unit("Thùng")
                .transferQuantity(BigDecimal.valueOf(100))
                .build();

        CreateStockTransferPayload req = CreateStockTransferPayload.builder()
                .sourceWarehouseCode("WH-MB01")
                .destWarehouseCode("WH-MT01")
                .transferDate("2026-10-10")
                .status("DRAFT")
                .lines(List.of(line))
                .build();

        StockTransferResponse res = stockTransferService.createTransfer(req, whStaffActor);

        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo("TRF-202610-001");
        assertThat(res.getStatus()).isEqualTo("DRAFT");
        assertThat(res.getSourceWarehouseCode()).isEqualTo("WH-MB01");
        assertThat(res.getDestWarehouseCode()).isEqualTo("WH-MT01");

        // Chưa xuất kho nên tồn kho nguồn chưa bị trừ
        assertThat(sourceInventory.getPhysicalStock()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    @DisplayName("S5-07: Kho nguồn và kho đích trùng nhau -> Báo lỗi SAME_WAREHOUSE")
    void createTransfer_sameWarehouse_throwsException() {
        CreateStockTransferPayload req = CreateStockTransferPayload.builder()
                .sourceWarehouseCode("WH-MB01")
                .destWarehouseCode("WH-MB01")
                .transferDate("2026-10-10")
                .lines(List.of())
                .build();

        assertThatThrownBy(() -> stockTransferService.createTransfer(req, whStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Kho nguồn và kho đích không được trùng nhau");
    }

    @Test
    @DisplayName("S5-07: Tồn khả dụng tại kho nguồn không đủ để điều chuyển -> Báo lỗi INSUFFICIENT_STOCK")
    void createTransfer_insufficientStock_throwsException() {
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(sourceWh));
        when(warehouseRepository.findByCodeIgnoreCase("WH-MT01")).thenReturn(Optional.of(destWh));
        when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(sourceInventory));

        // Khả dụng là 450, nhưng yêu cầu chuyển 600
        CreateStockTransferLinePayload line = CreateStockTransferLinePayload.builder()
                .productId(10L)
                .transferQuantity(BigDecimal.valueOf(600))
                .build();

        CreateStockTransferPayload req = CreateStockTransferPayload.builder()
                .sourceWarehouseCode("WH-MB01")
                .destWarehouseCode("WH-MT01")
                .transferDate("2026-10-10")
                .lines(List.of(line))
                .build();

        assertThatThrownBy(() -> stockTransferService.createTransfer(req, whStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Tồn khả dụng tại kho xuất không đủ");
    }

    @Test
    @DisplayName("S5-07 AC2: Xuất kho (dispatch) -> Trừ tồn kho nguồn, ghi nhận Đang trên đường, CHƯA cộng kho đến")
    void dispatchTransfer_success_subtractsSourceStock() {
        StockTransferLine line = StockTransferLine.builder()
                .id(1L)
                .product(sampleProduct)
                .productSku(sampleProduct.getSku())
                .productName(sampleProduct.getName())
                .unit("Lon")
                .transferQuantity(BigDecimal.valueOf(100))
                .build();

        StockTransfer transfer = StockTransfer.builder()
                .id(50L)
                .code("TRF-202610-001")
                .sourceWarehouse(sourceWh)
                .destWarehouse(destWh)
                .status(StockTransfer.STATUS_DRAFT)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setStockTransfer(transfer);

        when(stockTransferRepository.findById(50L)).thenReturn(Optional.of(transfer));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(sourceInventory));
        when(stockTransferRepository.save(any(StockTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        StockTransferResponse res = stockTransferService.dispatchTransfer(50L, whStaffActor);

        assertThat(res.getStatus()).isEqualTo(StockTransfer.STATUS_IN_TRANSIT);
        assertThat(res.getDispatchedAt()).isNotNull();

        // Tồn kho nguồn đã trừ: 500 - 100 = 400
        assertThat(sourceInventory.getPhysicalStock()).isEqualByComparingTo(BigDecimal.valueOf(400));
        verify(inventoryRepository).save(sourceInventory);

        // Kho đích tuyệt đối CHƯA được cộng tồn (AC2)
        verify(inventoryRepository, never()).findByWarehouseIdAndProductIdForUpdate(eq(2L), anyLong());
    }

    @Test
    @DisplayName("S5-07 AC3: Kho đến xác nhận nhận đủ (COMPLETED) -> Tồn kho đến mới được cộng")
    void receiveTransfer_completed_addsDestStock() {
        StockTransferLine line = StockTransferLine.builder()
                .id(1L)
                .product(sampleProduct)
                .productSku(sampleProduct.getSku())
                .productName(sampleProduct.getName())
                .unit("Lon")
                .transferQuantity(BigDecimal.valueOf(100))
                .build();

        StockTransfer transfer = StockTransfer.builder()
                .id(50L)
                .code("TRF-202610-001")
                .sourceWarehouse(sourceWh)
                .destWarehouse(destWh)
                .status(StockTransfer.STATUS_IN_TRANSIT)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setStockTransfer(transfer);

        Inventory destInventory = Inventory.builder()
                .id(200L)
                .warehouse(destWh)
                .product(sampleProduct)
                .physicalStock(BigDecimal.valueOf(20))
                .reservedStock(BigDecimal.ZERO)
                .build();

        when(stockTransferRepository.findById(50L)).thenReturn(Optional.of(transfer));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(2L, 10L)).thenReturn(Optional.of(destInventory));
        when(stockTransferRepository.save(any(StockTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReceiveTransferPayload payload = ReceiveTransferPayload.builder()
                .transferId("50")
                .receivedLines(List.of(
                        ReceiveTransferLineItem.builder()
                                .lineId("1")
                                .receivedQuantity(BigDecimal.valueOf(100)) // Nhận đủ 100/100
                                .build()
                ))
                .build();

        StockTransferResponse res = stockTransferService.receiveTransfer(50L, payload, whStaffActor);

        assertThat(res.getStatus()).isEqualTo(StockTransfer.STATUS_COMPLETED);
        // Tồn kho đến được cộng: 20 + 100 = 120 (AC3)
        assertThat(destInventory.getPhysicalStock()).isEqualByComparingTo(BigDecimal.valueOf(120));
        verify(inventoryRepository).save(destInventory);
    }

    @Test
    @DisplayName("S5-07 AC4: Nhận có chênh lệch nhưng thiếu lý do -> Báo lỗi DISCREPANCY_REASON_REQUIRED")
    void receiveTransfer_withDiscrepancy_missingReason_throwsException() {
        StockTransferLine line = StockTransferLine.builder()
                .id(1L)
                .product(sampleProduct)
                .productSku(sampleProduct.getSku())
                .productName(sampleProduct.getName())
                .unit("Lon")
                .transferQuantity(BigDecimal.valueOf(100))
                .build();

        StockTransfer transfer = StockTransfer.builder()
                .id(50L)
                .code("TRF-202610-001")
                .sourceWarehouse(sourceWh)
                .destWarehouse(destWh)
                .status(StockTransfer.STATUS_IN_TRANSIT)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setStockTransfer(transfer);

        when(stockTransferRepository.findById(50L)).thenReturn(Optional.of(transfer));

        // Nhận 95/100 (thiếu 5 lon) nhưng không nhập lý do
        ReceiveTransferPayload payload = ReceiveTransferPayload.builder()
                .transferId("50")
                .receivedLines(List.of(
                        ReceiveTransferLineItem.builder()
                                .lineId("1")
                                .receivedQuantity(BigDecimal.valueOf(95))
                                .discrepancyReason("") // Để trống lý do
                                .build()
                ))
                .generalReason(null)
                .build();

        assertThatThrownBy(() -> stockTransferService.receiveTransfer(50L, payload, whStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Bắt buộc nhập lý do chênh lệch");
    }

    @Test
    @DisplayName("S5-07 AC4: Nhận có chênh lệch và có lý do -> Trạng thái DISCREPANCY_RESOLVED, cộng kho theo số thực nhận")
    void receiveTransfer_withDiscrepancy_hasReason_success() {
        StockTransferLine line = StockTransferLine.builder()
                .id(1L)
                .product(sampleProduct)
                .productSku(sampleProduct.getSku())
                .productName(sampleProduct.getName())
                .unit("Lon")
                .transferQuantity(BigDecimal.valueOf(100))
                .build();

        StockTransfer transfer = StockTransfer.builder()
                .id(50L)
                .code("TRF-202610-001")
                .sourceWarehouse(sourceWh)
                .destWarehouse(destWh)
                .status(StockTransfer.STATUS_IN_TRANSIT)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setStockTransfer(transfer);

        Inventory destInventory = Inventory.builder()
                .id(200L)
                .warehouse(destWh)
                .product(sampleProduct)
                .physicalStock(BigDecimal.ZERO)
                .build();

        when(stockTransferRepository.findById(50L)).thenReturn(Optional.of(transfer));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(2L, 10L)).thenReturn(Optional.of(destInventory));
        when(stockTransferRepository.save(any(StockTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReceiveTransferPayload payload = ReceiveTransferPayload.builder()
                .transferId("50")
                .receivedLines(List.of(
                        ReceiveTransferLineItem.builder()
                                .lineId("1")
                                .receivedQuantity(BigDecimal.valueOf(95))
                                .discrepancyReason("Bị vỡ 5 lon khi bốc dỡ xe")
                                .build()
                ))
                .generalReason("Biên bản sự cố xe số 29H-882.19")
                .build();

        StockTransferResponse res = stockTransferService.receiveTransfer(50L, payload, whStaffActor);

        assertThat(res.getStatus()).isEqualTo(StockTransfer.STATUS_DISCREPANCY_RESOLVED);
        assertThat(res.getDiscrepancyGeneralReason()).isEqualTo("Biên bản sự cố xe số 29H-882.19");
        // Tồn kho đến được cộng theo số lượng thực nhận (95 lon)
        assertThat(destInventory.getPhysicalStock()).isEqualByComparingTo(BigDecimal.valueOf(95));
    }
}
