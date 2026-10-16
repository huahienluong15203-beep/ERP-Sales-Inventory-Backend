package com.erp.backend.service;

import com.erp.backend.dto.salesreturn.ReturnableInvoiceLineResponse;
import com.erp.backend.dto.salesreturn.SalesReturnCreateRequest;
import com.erp.backend.dto.salesreturn.SalesReturnItemRequest;
import com.erp.backend.dto.salesreturn.SalesReturnResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S8-01: Kiểm thử lập phiếu trả hàng từ đại lý và nhập lại kho.
 * - Subtask SCRUM-238: Test BE: Test nhập lại đúng lô/chặn vượt số gốc.
 * - Subtask SCRUM-263: Viết API trả hàng, check số lượng, nhập kho ngược.
 */
@ExtendWith(MockitoExtension.class)
class SalesReturnServiceTest {

    @Mock private SalesReturnRepository salesReturnRepository;
    @Mock private SalesReturnLineRepository salesReturnLineRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceLineRepository invoiceLineRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private ProductLotRepository productLotRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private SalesReturnService salesReturnService;

    private UserDetailsImpl salesRepActor;
    private UserDetailsImpl warehouseManagerActor;

    private Invoice sampleInvoice;
    private InvoiceLine sampleInvoiceLine;
    private Product sampleProduct;
    private Customer sampleCustomer;
    private Warehouse sampleWarehouse;

    @BeforeEach
    void setUp() {
        salesRepActor = CustomerTestData.actor(20L, "ROLE_SALES_REP");
        warehouseManagerActor = CustomerTestData.actor(30L, "ROLE_WH_MANAGER");

        sampleCustomer = new Customer();
        sampleCustomer.setId(1L);
        sampleCustomer.setCode("CUST001");
        sampleCustomer.setName("Đại lý Hải Đăng");

        sampleWarehouse = new Warehouse();
        sampleWarehouse.setId(5L);
        sampleWarehouse.setCode("KHO-TONG");
        sampleWarehouse.setName("Kho Tổng Miền Bắc");
        sampleWarehouse.setStatus("ACTIVE");

        sampleProduct = new Product();
        sampleProduct.setId(100L);
        sampleProduct.setSku("BIA-HN-LON");
        sampleProduct.setName("Bia Hà Nội Lon 330ml");
        sampleProduct.setBaseUnit("Lon");
        sampleProduct.setHasBatchManagement(false);

        sampleInvoice = new Invoice();
        sampleInvoice.setId(10L);
        sampleInvoice.setCode("HD-202610-001");
        sampleInvoice.setStatus(Invoice.STATUS_ISSUED);
        sampleInvoice.setCustomer(sampleCustomer);
        sampleInvoice.setCustomerCode("CUST001");
        sampleInvoice.setCustomerName("Đại lý Hải Đăng");

        sampleInvoiceLine = new InvoiceLine();
        sampleInvoiceLine.setId(101L);
        sampleInvoiceLine.setInvoice(sampleInvoice);
        sampleInvoiceLine.setProduct(sampleProduct);
        sampleInvoiceLine.setProductSku("BIA-HN-LON");
        sampleInvoiceLine.setProductName("Bia Hà Nội Lon 330ml");
        sampleInvoiceLine.setUnitName("Thùng");
        sampleInvoiceLine.setConversionFactor(new BigDecimal("24")); // 1 thùng = 24 lon
        sampleInvoiceLine.setQuantity(new BigDecimal("10")); // Đã giao 10 thùng
        sampleInvoiceLine.setPricePerUnit(new BigDecimal("250000"));
        sampleInvoiceLine.setLineNo(1);
    }

    @Test
    @DisplayName("SCRUM-263: Lập phiếu trả hàng thành công - Trạng thái PENDING_RECEIPT, chưa thay đổi tồn kho")
    void testCreateSalesReturn_Success() {
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));
        when(warehouseRepository.findById(5L)).thenReturn(Optional.of(sampleWarehouse));
        when(invoiceLineRepository.findById(101L)).thenReturn(Optional.of(sampleInvoiceLine));
        when(salesReturnLineRepository.getPreviouslyReturnedQuantity(eq(101L), isNull(), anyString()))
                .thenReturn(BigDecimal.ZERO);
        when(salesReturnRepository.findCodesByPrefix(anyString())).thenReturn(Collections.emptyList());
        when(salesReturnRepository.save(any(SalesReturn.class))).thenAnswer(invocation -> {
            SalesReturn r = invocation.getArgument(0);
            r.setId(1L);
            return r;
        });

        SalesReturnCreateRequest request = SalesReturnCreateRequest.builder()
                .invoiceId(10L)
                .receivingWarehouseId(5L)
                .returnReason(SalesReturn.REASON_DEFECTIVE_TRANSPORT)
                .reasonDescription("Vỏ thùng bị móp méo trong quá trình vận chuyển")
                .returnDate(LocalDate.of(2026, 10, 16))
                .items(List.of(
                        SalesReturnItemRequest.builder()
                                .invoiceLineId(101L)
                                .returnQuantity(new BigDecimal("3"))
                                .isDamaged(true)
                                .build()
                ))
                .build();

        SalesReturnResponse response = salesReturnService.createSalesReturn(request, salesRepActor);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(SalesReturn.STATUS_PENDING_RECEIPT);
        assertThat(response.getTotalQuantity()).isEqualByComparingTo("3");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("750000"); // 3 * 250,000

        // Đảm bảo ở bước lập phiếu CHƯA cộng tồn kho hay ghi thẻ kho (AC4)
        verify(inventoryRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCRUM-238: Chặn trả vượt quá số lượng đã giao gốc (returnQuantity > deliveredQuantity)")
    void testCreateSalesReturn_FailWhenQuantityExceedsDelivered() {
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));
        when(warehouseRepository.findById(5L)).thenReturn(Optional.of(sampleWarehouse));
        when(invoiceLineRepository.findById(101L)).thenReturn(Optional.of(sampleInvoiceLine));
        when(salesReturnLineRepository.getPreviouslyReturnedQuantity(eq(101L), isNull(), anyString()))
                .thenReturn(BigDecimal.ZERO);

        SalesReturnCreateRequest request = SalesReturnCreateRequest.builder()
                .invoiceId(10L)
                .receivingWarehouseId(5L)
                .returnReason(SalesReturn.REASON_AGENCY_OVERSTOCK)
                .items(List.of(
                        SalesReturnItemRequest.builder()
                                .invoiceLineId(101L)
                                .returnQuantity(new BigDecimal("15")) // Giao 10 nhưng đòi trả 15
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> salesReturnService.createSalesReturn(request, salesRepActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vượt quá số lượng đã giao còn lại");

        verify(salesReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCRUM-238: Chặn trả vượt quá phần còn lại khi đã trả một phần trước đó")
    void testCreateSalesReturn_FailWhenQuantityExceedsRemainingAfterPreviousReturn() {
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));
        when(warehouseRepository.findById(5L)).thenReturn(Optional.of(sampleWarehouse));
        when(invoiceLineRepository.findById(101L)).thenReturn(Optional.of(sampleInvoiceLine));
        // Đã trả 7 thùng trước đó -> chỉ còn được trả tối đa 3 thùng
        when(salesReturnLineRepository.getPreviouslyReturnedQuantity(eq(101L), isNull(), anyString()))
                .thenReturn(new BigDecimal("7"));

        SalesReturnCreateRequest request = SalesReturnCreateRequest.builder()
                .invoiceId(10L)
                .receivingWarehouseId(5L)
                .returnReason(SalesReturn.REASON_AGENCY_OVERSTOCK)
                .items(List.of(
                        SalesReturnItemRequest.builder()
                                .invoiceLineId(101L)
                                .returnQuantity(new BigDecimal("5")) // Đòi trả 5 (7 + 5 = 12 > 10)
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> salesReturnService.createSalesReturn(request, salesRepActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vượt quá số lượng đã giao còn lại");
    }

    @Test
    @DisplayName("SCRUM-238: Bắt buộc chọn/nhập số lô nếu SKU có quản lý theo lô (AC3)")
    void testCreateSalesReturn_FailWhenBatchManagedProductHasNoBatchNumber() {
        sampleProduct.setHasBatchManagement(true); // Có quản lý lô

        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));
        when(warehouseRepository.findById(5L)).thenReturn(Optional.of(sampleWarehouse));
        when(invoiceLineRepository.findById(101L)).thenReturn(Optional.of(sampleInvoiceLine));
        when(salesReturnLineRepository.getPreviouslyReturnedQuantity(eq(101L), isNull(), anyString()))
                .thenReturn(BigDecimal.ZERO);

        SalesReturnCreateRequest request = SalesReturnCreateRequest.builder()
                .invoiceId(10L)
                .receivingWarehouseId(5L)
                .returnReason(SalesReturn.REASON_EXPIRED_OR_NEAR)
                .items(List.of(
                        SalesReturnItemRequest.builder()
                                .invoiceLineId(101L)
                                .returnQuantity(new BigDecimal("2"))
                                .batchNumber("") // Không nhập số lô
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> salesReturnService.createSalesReturn(request, salesRepActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bắt buộc phải chọn/nhập số lô sản xuất (AC3)");
    }

    @Test
    @DisplayName("SCRUM-238 & 263: Quản lý kho xác nhận nhận hàng - Tăng tồn thực tế, cộng tồn lô và ghi thẻ kho")
    void testReceiveAndRestock_Success_IncreasesPhysicalStockAndUpdatesLot() {
        sampleProduct.setHasBatchManagement(true);

        SalesReturn salesReturn = SalesReturn.builder()
                .id(1L)
                .code("PTH-202610-001")
                .status(SalesReturn.STATUS_PENDING_RECEIPT)
                .receivingWarehouse(sampleWarehouse)
                .warehouseCode("KHO-TONG")
                .customer(sampleCustomer)
                .customerName("Đại lý Hải Đăng")
                .invoice(sampleInvoice)
                .invoiceCode("HD-202610-001")
                .returnReason(SalesReturn.REASON_AGENCY_OVERSTOCK)
                .build();

        SalesReturnLine line = SalesReturnLine.builder()
                .id(11L)
                .salesReturn(salesReturn)
                .product(sampleProduct)
                .unitName("Thùng")
                .conversionFactor(new BigDecimal("24"))
                .returnQuantity(new BigDecimal("2")) // 2 thùng
                .baseQuantity(new BigDecimal("48"))   // 48 lon
                .hasBatchManagement(true)
                .batchNumber("LOT-HN-202610")
                .manufactureDate(LocalDate.of(2026, 9, 1))
                .expiredDate(LocalDate.of(2027, 3, 1))
                .isDamaged(false)
                .build();
        salesReturn.addLine(line);

        when(salesReturnRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(salesReturn));

        // Tồn kho hiện tại: 100 lon
        Inventory existingInventory = Inventory.builder()
                .id(50L)
                .warehouse(sampleWarehouse)
                .product(sampleProduct)
                .physicalStock(new BigDecimal("100"))
                .reservedStock(BigDecimal.ZERO)
                .build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(5L, 100L))
                .thenReturn(Optional.of(existingInventory));

        // Lô hàng hiện tại: 50 lon
        ProductLot existingLot = ProductLot.builder()
                .id(80L)
                .product(sampleProduct)
                .warehouse(sampleWarehouse)
                .batchNumber("LOT-HN-202610")
                .quantity(new BigDecimal("50"))
                .status("ACTIVE")
                .build();
        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(100L, 5L, "LOT-HN-202610"))
                .thenReturn(Optional.of(existingLot));

        when(salesReturnRepository.save(any(SalesReturn.class))).thenReturn(salesReturn);

        // Quản lý kho xác nhận nhận hàng
        SalesReturnResponse response = salesReturnService.receiveAndRestock(1L, "Kiểm đếm đủ 2 thùng", warehouseManagerActor);

        assertThat(response.getStatus()).isEqualTo(SalesReturn.STATUS_RECEIVED);

        // 1. Kiểm tra tồn kho tăng lên 100 + 48 = 148 lon
        assertThat(existingInventory.getPhysicalStock()).isEqualByComparingTo("148");
        verify(inventoryRepository).save(existingInventory);

        // 2. Kiểm tra tồn lô tăng lên 50 + 48 = 98 lon
        assertThat(existingLot.getQuantity()).isEqualByComparingTo("98");
        verify(productLotRepository).save(existingLot);

        // 3. Kiểm tra ghi thẻ kho StockMovement
        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movementCaptor.capture());
        StockMovement movement = movementCaptor.getValue();
        assertThat(movement.getMovementType()).isEqualTo(StockMovement.TYPE_SALES_RETURN);
        assertThat(movement.getQuantity()).isEqualByComparingTo("48");
        assertThat(movement.getBalanceAfter()).isEqualByComparingTo("148");
        assertThat(movement.getDocType()).isEqualTo(StockMovement.DOC_SALES_RETURN);
    }

    @Test
    @DisplayName("SCRUM-238: Hàng hỏng / vận chuyển vỡ - Nhập lại lô hàng ở trạng thái LOCKED để không xuất bán")
    void testReceiveAndRestock_DamagedGoods_LocksLot() {
        sampleProduct.setHasBatchManagement(true);

        SalesReturn salesReturn = SalesReturn.builder()
                .id(2L)
                .code("PTH-202610-002")
                .status(SalesReturn.STATUS_PENDING_RECEIPT)
                .receivingWarehouse(sampleWarehouse)
                .customer(sampleCustomer)
                .returnReason(SalesReturn.REASON_DEFECTIVE_TRANSPORT) // Lý do hỏng do vận chuyển
                .build();

        SalesReturnLine line = SalesReturnLine.builder()
                .id(12L)
                .salesReturn(salesReturn)
                .product(sampleProduct)
                .returnQuantity(new BigDecimal("1"))
                .baseQuantity(new BigDecimal("24"))
                .hasBatchManagement(true)
                .batchNumber("LOT-DAMAGED-01")
                .isDamaged(true) // Đánh dấu hàng lỗi
                .build();
        salesReturn.addLine(line);

        when(salesReturnRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(salesReturn));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(anyLong(), anyLong()))
                .thenReturn(Optional.of(Inventory.builder().physicalStock(BigDecimal.ZERO).build()));
        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty()); // Chưa có lô này
        when(salesReturnRepository.save(any(SalesReturn.class))).thenReturn(salesReturn);

        salesReturnService.receiveAndRestock(2L, "Hàng vỡ vỏ lon", warehouseManagerActor);

        // Kiểm tra lô hàng tạo mới bị khóa LOCKED
        ArgumentCaptor<ProductLot> lotCaptor = ArgumentCaptor.forClass(ProductLot.class);
        verify(productLotRepository).save(lotCaptor.capture());
        ProductLot savedLot = lotCaptor.getValue();
        assertThat(savedLot.getStatus()).isEqualTo("LOCKED");
        assertThat(savedLot.getNotes()).contains("Hàng trả lỗi");
    }

    @Test
    @DisplayName("Chặn nhận hàng 2 lần cho phiếu đã nhận (chỉ nhận khi PENDING_RECEIPT)")
    void testReceiveAndRestock_FailWhenNotPendingReceipt() {
        SalesReturn salesReturn = SalesReturn.builder()
                .id(1L)
                .code("PTH-202610-001")
                .status(SalesReturn.STATUS_RECEIVED) // Đã nhận trước đó
                .build();

        when(salesReturnRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(salesReturn));

        assertThatThrownBy(() -> salesReturnService.receiveAndRestock(1L, "Thử nhận lần 2", warehouseManagerActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không ở trạng thái chờ nhận hàng");
    }

    @Test
    @DisplayName("Huỷ phiếu trả hàng khi còn chờ nhận hàng thành công")
    void testCancelSalesReturn_SuccessWhenPendingReceipt() {
        SalesReturn salesReturn = SalesReturn.builder()
                .id(1L)
                .code("PTH-202610-001")
                .returnReason(SalesReturn.REASON_AGENCY_OVERSTOCK)
                .status(SalesReturn.STATUS_PENDING_RECEIPT)
                .build();

        when(salesReturnRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(salesReturn));
        when(salesReturnRepository.save(any(SalesReturn.class))).thenReturn(salesReturn);

        SalesReturnResponse response = salesReturnService.cancelSalesReturn(1L, "Đại lý đổi ý giữ lại hàng", salesRepActor);

        assertThat(response.getStatus()).isEqualTo(SalesReturn.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("Chặn huỷ phiếu trả hàng khi hàng đã được thủ kho nhận vào kho (STATUS_RECEIVED)")
    void testCancelSalesReturn_FailWhenAlreadyReceived() {
        SalesReturn salesReturn = SalesReturn.builder()
                .id(1L)
                .code("PTH-202610-001")
                .returnReason(SalesReturn.REASON_AGENCY_OVERSTOCK)
                .status(SalesReturn.STATUS_RECEIVED)
                .build();

        when(salesReturnRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(salesReturn));

        assertThatThrownBy(() -> salesReturnService.cancelSalesReturn(1L, "Muốn huỷ sau khi đã nhập kho", salesRepActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không thể huỷ phiếu trả hàng ở trạng thái RECEIVED");
    }

    @Test
    @DisplayName("Kiểm tra tính toán số lượng còn có thể trả lại trên hoá đơn (getReturnableInvoiceLines)")
    void testGetReturnableInvoiceLines() {
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));
        when(invoiceLineRepository.findByInvoice_IdOrderByLineNoAsc(10L)).thenReturn(List.of(sampleInvoiceLine));
        // Đã trả 4 thùng trước đó
        when(salesReturnLineRepository.getPreviouslyReturnedQuantity(eq(101L), isNull(), anyString()))
                .thenReturn(new BigDecimal("4"));

        List<ReturnableInvoiceLineResponse> lines = salesReturnService.getReturnableInvoiceLines(10L);

        assertThat(lines).hasSize(1);
        ReturnableInvoiceLineResponse line = lines.get(0);
        assertThat(line.getDeliveredQuantity()).isEqualByComparingTo("10");
        assertThat(line.getPreviouslyReturnedQuantity()).isEqualByComparingTo("4");
        assertThat(line.getRemainingReturnableQuantity()).isEqualByComparingTo("6"); // 10 - 4 = 6
    }
}
