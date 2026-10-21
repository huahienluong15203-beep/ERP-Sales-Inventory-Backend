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
    @Mock private CustomerRepository customerRepository;
    @Mock private SalesOrderLineRepository salesOrderLineRepository;
    @Mock private UserRepository userRepository;

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
        sampleCustomer.setSalesRep(CustomerTestData.salesRep(20L)); // NV kinh doanh 20 phụ trách

        sampleWarehouse = new Warehouse();
        sampleWarehouse.setId(5L);
        sampleWarehouse.setCode("KHO-TONG");
        sampleWarehouse.setName("Kho Tổng Miền Bắc");
        sampleWarehouse.setStatus("ACTIVE");
        sampleWarehouse.setManager(CustomerTestData.user(30L, "ACTIVE", RoleName.ROLE_WH_MANAGER)); // QL kho 30 phụ trách kho

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
        when(invoiceLineRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(sampleInvoiceLine));
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
        when(invoiceLineRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(sampleInvoiceLine));
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
        when(invoiceLineRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(sampleInvoiceLine));
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
        when(invoiceLineRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(sampleInvoiceLine));
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
    @DisplayName("TC801-11: Hàng hỏng SKU có lô - vào khu hàng lỗi, không cộng tồn bán được, lô hàng tốt vẫn ACTIVE")
    void testReceiveAndRestock_DamagedLotGoods_QuarantinedLotStaysActive() {
        sampleProduct.setHasBatchManagement(true);
        SalesReturn salesReturn = returnWithLine(2L, SalesReturn.REASON_AGENCY_OVERSTOCK, true, "LOT-HN-202610");
        Inventory inv = Inventory.builder().id(50L).physicalStock(new BigDecimal("100")).reservedStock(BigDecimal.ZERO).build();
        when(salesReturnRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(salesReturn));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(5L, 100L)).thenReturn(Optional.of(inv));
        when(salesReturnRepository.save(any(SalesReturn.class))).thenReturn(salesReturn);

        salesReturnService.receiveAndRestock(2L, "Hàng vỡ vỏ lon", warehouseManagerActor);

        assertThat(inv.getPhysicalStock()).isEqualByComparingTo("100");
        assertThat(inv.getDamagedStock()).isEqualByComparingTo("24");
        verify(productLotRepository, never()).findByProductAndWarehouseAndBatchNumberForUpdate(anyLong(), anyLong(), anyString());
        verify(productLotRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC801-12: Hàng hỏng SKU không quản lý lô (lý do hỏng do vận chuyển) - không cộng vào tồn bán được")
    void testReceiveAndRestock_DamagedNonLotGoods_NotSellable() {
        SalesReturn salesReturn = returnWithLine(3L, "Bao bì móp méo / Hỏng khi vận chuyển", false, null);
        Inventory inv = Inventory.builder().id(50L).physicalStock(new BigDecimal("100")).reservedStock(BigDecimal.ZERO).build();
        when(salesReturnRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(salesReturn));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(5L, 100L)).thenReturn(Optional.of(inv));
        when(salesReturnRepository.save(any(SalesReturn.class))).thenReturn(salesReturn);

        salesReturnService.receiveAndRestock(3L, null, warehouseManagerActor);

        assertThat(inv.getPhysicalStock()).isEqualByComparingTo("100");
        assertThat(inv.getAvailableStock()).isEqualByComparingTo("100");
        assertThat(inv.getDamagedStock()).isEqualByComparingTo("24");
    }

    @Test
    @DisplayName("TC801-15: NV kho (không phải QL kho) xác nhận nhận hàng -> 409, tồn không đổi")
    void testReceive_warehouseStaff_rejected() {
        SalesReturn salesReturn = returnWithLine(4L, SalesReturn.REASON_AGENCY_OVERSTOCK, false, null);
        when(salesReturnRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(salesReturn));

        assertThatThrownBy(() -> salesReturnService.receiveAndRestock(4L, null, CustomerTestData.actor(31L, "ROLE_WAREHOUSE")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("RETURN_RECEIVE_MANAGER_ONLY"));
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("TC801-16: QL kho không được phân công kho nhận -> 409")
    void testReceive_managerOfOtherWarehouse_rejected() {
        SalesReturn salesReturn = returnWithLine(4L, SalesReturn.REASON_AGENCY_OVERSTOCK, false, null);
        when(salesReturnRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(salesReturn));
        User other = CustomerTestData.user(32L, "ACTIVE", RoleName.ROLE_WH_MANAGER);
        Warehouse south = new Warehouse();
        south.setId(6L);
        other.setWarehouses(new java.util.HashSet<>(java.util.Set.of(south)));
        when(userRepository.findById(32L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> salesReturnService.receiveAndRestock(4L, null, CustomerTestData.actor(32L, "ROLE_WH_MANAGER")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("RETURN_WAREHOUSE_NOT_ASSIGNED"));
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("TC801-16: QL kho được gắn kho qua phân công user_warehouses thì nhận được")
    void testReceive_assignedManager_ok() {
        sampleWarehouse.setManager(null);
        SalesReturn salesReturn = returnWithLine(4L, SalesReturn.REASON_AGENCY_OVERSTOCK, false, null);
        when(salesReturnRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(salesReturn));
        User mgr = CustomerTestData.user(30L, "ACTIVE", RoleName.ROLE_WH_MANAGER);
        mgr.setWarehouses(new java.util.HashSet<>(java.util.Set.of(sampleWarehouse)));
        when(userRepository.findById(30L)).thenReturn(Optional.of(mgr));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(5L, 100L))
                .thenReturn(Optional.of(Inventory.builder().id(50L).physicalStock(new BigDecimal("10")).build()));
        when(salesReturnRepository.save(any(SalesReturn.class))).thenReturn(salesReturn);

        assertThat(salesReturnService.receiveAndRestock(4L, null, warehouseManagerActor).getStatus())
                .isEqualTo(SalesReturn.STATUS_RECEIVED);
    }

    @Test
    @DisplayName("TC801-18: Nhận hàng ghi nhật ký INVENTORY có người nhận, tồn trước / sau")
    void testReceive_writesAuditLog() {
        SalesReturn salesReturn = returnWithLine(4L, SalesReturn.REASON_AGENCY_OVERSTOCK, false, null);
        when(salesReturnRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(salesReturn));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(5L, 100L))
                .thenReturn(Optional.of(Inventory.builder().id(50L).physicalStock(new BigDecimal("100")).build()));
        when(salesReturnRepository.save(any(SalesReturn.class))).thenReturn(salesReturn);

        salesReturnService.receiveAndRestock(4L, null, warehouseManagerActor);

        verify(auditLogService).record(eq(AuditModule.INVENTORY), eq("SALES_RETURN_RECEIVE"), eq("INVENTORY"), eq(50L),
                contains("PTH-4"), contains("Tồn thực tế: 100"), contains("Tồn thực tế: 124"),
                contains("PTH-4"), eq(warehouseManagerActor));
    }

    private SalesReturn returnWithLine(long id, String reason, boolean damaged, String batch) {
        SalesReturn r = SalesReturn.builder()
                .id(id)
                .code("PTH-" + id)
                .status(SalesReturn.STATUS_PENDING_RECEIPT)
                .receivingWarehouse(sampleWarehouse)
                .customer(sampleCustomer)
                .customerName("Đại lý Hải Đăng")
                .invoiceCode("HD-202610-001")
                .returnReason(reason)
                .build();
        r.addLine(SalesReturnLine.builder()
                .id(id * 10)
                .salesReturn(r)
                .product(sampleProduct)
                .returnQuantity(new BigDecimal("1"))
                .baseQuantity(new BigDecimal("24"))
                .hasBatchManagement(batch != null)
                .batchNumber(batch)
                .isDamaged(damaged)
                .build());
        return r;
    }

    private SalesReturnCreateRequest createRequest(String reason, SalesReturnItemRequest... items) {
        return SalesReturnCreateRequest.builder()
                .invoiceId(10L)
                .receivingWarehouseId(5L)
                .returnReason(reason)
                .items(List.of(items))
                .build();
    }

    private static SalesReturnItemRequest item(long lineId, String qty, String batch) {
        return SalesReturnItemRequest.builder().invoiceLineId(lineId).returnQuantity(new BigDecimal(qty)).batchNumber(batch).build();
    }

    private void stubCreate(BigDecimal previouslyReturned) {
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));
        when(warehouseRepository.findById(5L)).thenReturn(Optional.of(sampleWarehouse));
        when(invoiceLineRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(sampleInvoiceLine));
        when(salesReturnLineRepository.getPreviouslyReturnedQuantity(eq(101L), isNull(), anyString())).thenReturn(previouslyReturned);
    }

    private void stubSave() {
        when(salesReturnRepository.findCodesByPrefix(anyString())).thenReturn(Collections.emptyList());
        when(salesReturnRepository.save(any(SalesReturn.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("TC801-04: Cùng 1 dòng hoá đơn gửi 2 lần (2 + 2 thùng) mà chỉ còn trả được 3 -> 400, kiểm tra tổng")
    void create_duplicateLines_totalChecked() {
        stubCreate(new BigDecimal("7"));

        assertThatThrownBy(() -> salesReturnService.createSalesReturn(createRequest(SalesReturn.REASON_AGENCY_OVERSTOCK,
                item(101L, "2", null), item(101L, "2", null)), salesRepActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Số lượng trả (4)").hasMessageContaining("còn lại (3)");
        verify(invoiceLineRepository, times(1)).findByIdForUpdate(101L);
        verify(salesReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC801-05: Lập phiếu khoá dòng hoá đơn FOR UPDATE rồi mới đọc số đã trả")
    void create_locksInvoiceLineBeforeReadingReturned() {
        stubCreate(BigDecimal.ZERO);
        stubSave();

        salesReturnService.createSalesReturn(createRequest(SalesReturn.REASON_AGENCY_OVERSTOCK, item(101L, "2", null)), salesRepActor);

        var order = inOrder(invoiceLineRepository, salesReturnLineRepository);
        order.verify(invoiceLineRepository).findByIdForUpdate(101L);
        order.verify(salesReturnLineRepository).getPreviouslyReturnedQuantity(eq(101L), isNull(), anyString());
        verify(invoiceLineRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("TC801-06: Lý do trả không có trong danh mục -> 400; nhãn giao diện đang dùng vẫn nhận")
    void create_unknownReason_rejected() {
        assertThatThrownBy(() -> salesReturnService.createSalesReturn(createRequest("LY_DO_BAT_KY", item(101L, "1", null)), salesRepActor))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_RETURN_REASON"));
        verifyNoInteractions(invoiceRepository);

        assertThat(SalesReturnService.reasonCode("Bao bì móp méo / Hỏng khi vận chuyển")).isEqualTo(SalesReturn.REASON_DEFECTIVE_TRANSPORT);
        assertThat(SalesReturnService.reasonCode("Hàng cận date / Hết hạn sử dụng")).isEqualTo(SalesReturn.REASON_EXPIRED_OR_NEAR);
        assertThat(SalesReturnService.reasonCode("agency_overstock")).isEqualTo(SalesReturn.REASON_AGENCY_OVERSTOCK);
        assertThat(SalesReturnService.reasonCode("Lý do khác")).isEqualTo(SalesReturn.REASON_OTHER);
    }

    @Test
    @DisplayName("TC801-10: Số lô khác lô đã xuất trên dòng đơn gốc -> 400")
    void create_batchNotShipped_rejected() {
        sampleProduct.setHasBatchManagement(true);
        sampleInvoiceLine.setOrderLineId(900L);
        SalesOrderLine ol = new SalesOrderLine();
        ol.setBatchNumber("LOT-HN-202610");
        stubCreate(BigDecimal.ZERO);
        when(salesOrderLineRepository.findById(900L)).thenReturn(Optional.of(ol));

        assertThatThrownBy(() -> salesReturnService.createSalesReturn(createRequest(SalesReturn.REASON_AGENCY_OVERSTOCK,
                item(101L, "1", "L-BIA-KHONG-CO")), salesRepActor))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("RETURN_BATCH_MISMATCH"));
        verify(salesReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC801-10: Đúng lô đã xuất (khác hoa thường) -> nhận, lưu theo số lô gốc")
    void create_shippedBatch_ok() {
        sampleProduct.setHasBatchManagement(true);
        sampleInvoiceLine.setOrderLineId(900L);
        SalesOrderLine ol = new SalesOrderLine();
        ol.setBatchNumber("LOT-HN-202610");
        stubCreate(BigDecimal.ZERO);
        stubSave();
        when(salesOrderLineRepository.findById(900L)).thenReturn(Optional.of(ol));

        SalesReturnResponse r = salesReturnService.createSalesReturn(createRequest(SalesReturn.REASON_AGENCY_OVERSTOCK,
                item(101L, "1", " lot-hn-202610 ")), salesRepActor);

        assertThat(r.getStatus()).isEqualTo(SalesReturn.STATUS_PENDING_RECEIPT);
        ArgumentCaptor<SalesReturn> c = ArgumentCaptor.forClass(SalesReturn.class);
        verify(salesReturnRepository).save(c.capture());
        assertThat(c.getValue().getLines().get(0).getBatchNumber()).isEqualTo("LOT-HN-202610");
    }

    @Test
    @DisplayName("TC801-10: Dòng đơn cũ chưa lưu lô xuất, số lô không có thật của SKU -> 400")
    void create_unknownBatch_noShippedInfo_rejected() {
        sampleProduct.setHasBatchManagement(true);
        stubCreate(BigDecimal.ZERO);
        when(productLotRepository.existsByProduct_IdAndBatchNumberIgnoreCase(100L, "L-BIA-KHONG-CO")).thenReturn(false);

        assertThatThrownBy(() -> salesReturnService.createSalesReturn(createRequest(SalesReturn.REASON_AGENCY_OVERSTOCK,
                item(101L, "1", "L-BIA-KHONG-CO")), salesRepActor))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("RETURN_BATCH_MISMATCH"));
    }

    @Test
    @DisplayName("TC801-17: NV kinh doanh lập phiếu trả cho đại lý người khác phụ trách -> 404")
    void create_otherRepCustomer_notFound() {
        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(sampleInvoice));

        assertThatThrownBy(() -> salesReturnService.createSalesReturn(createRequest(SalesReturn.REASON_AGENCY_OVERSTOCK,
                item(101L, "1", null)), CustomerTestData.actor(21L, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(invoiceLineRepository, warehouseRepository);
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
