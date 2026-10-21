package com.erp.backend.service;

import com.erp.backend.dto.audit.CreateAuditLogEntry;
import com.erp.backend.dto.stockadjustment.StockAdjustmentCreateRequest;
import com.erp.backend.dto.stockadjustment.StockAdjustmentDecisionRequest;
import com.erp.backend.dto.stockadjustment.StockAdjustmentItemRequest;
import com.erp.backend.dto.stockadjustment.StockAdjustmentResponse;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S8-03: Kiểm thử phiếu điều chỉnh tồn kho có phê duyệt.
 * - Test BE: Test phân quyền người lập/người duyệt.
 * - API Audit tồn kho ngầm, bắt buộc có ID QL.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceTest {

    @Mock private StockAdjustmentRepository stockAdjustmentRepository;
    @Mock private StockAdjustmentLineRepository stockAdjustmentLineRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductRepository productRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private ProductLotRepository productLotRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private StockAdjustmentService stockAdjustmentService;

    private UserDetailsImpl warehouseStaffActor; // Người lập (ID = 10)
    private UserDetailsImpl warehouseManagerActor; // Người duyệt (ID = 20)

    private Warehouse sampleWarehouse;
    private Product sampleProduct;
    private Inventory sampleInventory;
    private ProductLot sampleLot;

    @BeforeEach
    void setUp() {
        warehouseStaffActor = CustomerTestData.actor(10L, "ROLE_WAREHOUSE");
        warehouseManagerActor = CustomerTestData.actor(20L, "ROLE_WH_MANAGER");

        sampleWarehouse = new Warehouse();
        sampleWarehouse.setId(1L);
        sampleWarehouse.setCode("KHO-TONG");
        sampleWarehouse.setName("Kho Tổng Hà Nội");
        sampleWarehouse.setStatus("ACTIVE");

        User staffUser = CustomerTestData.user(10L, "ACTIVE", RoleName.ROLE_WAREHOUSE);
        staffUser.setWarehouses(new HashSet<>(Set.of(sampleWarehouse)));
        User managerUser = CustomerTestData.user(20L, "ACTIVE", RoleName.ROLE_WH_MANAGER);
        managerUser.setWarehouses(new HashSet<>(Set.of(sampleWarehouse)));
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
        lenient().when(userRepository.findById(20L)).thenReturn(Optional.of(managerUser));

        sampleProduct = new Product();
        sampleProduct.setId(100L);
        sampleProduct.setSku("BIA-HN-LON");
        sampleProduct.setName("Bia Hà Nội Lon 330ml");
        sampleProduct.setBaseUnit("Lon");
        sampleProduct.setCostPrice(new BigDecimal("12000")); // Giá vốn 12.000 đ
        sampleProduct.setHasBatchManagement(true);

        sampleInventory = new Inventory();
        sampleInventory.setId(50L);
        sampleInventory.setWarehouse(sampleWarehouse);
        sampleInventory.setProduct(sampleProduct);
        sampleInventory.setPhysicalStock(new BigDecimal("100")); // Tồn 100 lon

        sampleLot = new ProductLot();
        sampleLot.setId(80L);
        sampleLot.setWarehouse(sampleWarehouse);
        sampleLot.setProduct(sampleProduct);
        sampleLot.setBatchNumber("LOT-HN-202610");
        sampleLot.setQuantity(new BigDecimal("50")); // Lô có 50 lon
        sampleLot.setStatus("ACTIVE");
    }

    @Test
    @DisplayName("Tiêu chí 1 & 2: Lập phiếu điều chỉnh thành công - Bắt buộc lý do, tính đúng giá trị tổn thất")
    void testCreateAdjustment_Success() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(sampleWarehouse));
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByWarehouse_IdAndProduct_Id(1L, 100L)).thenReturn(Optional.of(sampleInventory));
        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(100L, 1L, "LOT-HN-202610"))
                .thenReturn(Optional.of(sampleLot));
        when(stockAdjustmentRepository.findCodesByPrefix(anyString())).thenReturn(Collections.emptyList());
        when(stockAdjustmentRepository.save(any(StockAdjustment.class))).thenAnswer(inv -> {
            StockAdjustment a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });

        StockAdjustmentCreateRequest request = StockAdjustmentCreateRequest.builder()
                .warehouseId(1L)
                .adjustmentReason(StockAdjustment.REASON_DAMAGED_GOODS)
                .reasonDescription("Bị đổ vỡ 10 lon trong quá trình xếp dỡ")
                .items(List.of(
                        StockAdjustmentItemRequest.builder()
                                .productId(100L)
                                .adjustmentQuantity(new BigDecimal("10"))
                                .batchNumber("LOT-HN-202610")
                                .build()
                ))
                .build();

        StockAdjustmentResponse response = stockAdjustmentService.createAdjustment(request, warehouseManagerActor);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(StockAdjustment.STATUS_PENDING_APPROVAL);
        assertThat(response.getTotalQuantity()).isEqualByComparingTo("10");
        // Giá trị tổn thất = 10 lon * 12.000 đ = 120.000 đ
        assertThat(response.getTotalLossValue()).isEqualByComparingTo("120000");

        // Ở bước lập phiếu, tồn kho CHƯA thay đổi
        verify(inventoryRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    @DisplayName("Chặn điều chỉnh khi số lượng giảm vượt quá tồn kho thực tế")
    void testCreateAdjustment_FailWhenQuantityExceedsCurrentStock() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(sampleWarehouse));
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByWarehouse_IdAndProduct_Id(1L, 100L)).thenReturn(Optional.of(sampleInventory)); // Tồn 100

        StockAdjustmentCreateRequest request = StockAdjustmentCreateRequest.builder()
                .warehouseId(1L)
                .adjustmentReason(StockAdjustment.REASON_LOST_GOODS)
                .items(List.of(
                        StockAdjustmentItemRequest.builder()
                                .productId(100L)
                                .adjustmentQuantity(new BigDecimal("150")) // Đòi giảm 150 > 100
                                .batchNumber("LOT-HN-202610")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> stockAdjustmentService.createAdjustment(request, warehouseStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vượt quá tồn kho thực tế hiện tại");
    }

    @Test
    @DisplayName("Bắt buộc nhập số lô nếu sản phẩm có quản lý theo lô")
    void testCreateAdjustment_FailWhenBatchManagedProductHasNoBatchNumber() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(sampleWarehouse));
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByWarehouse_IdAndProduct_Id(1L, 100L)).thenReturn(Optional.of(sampleInventory));

        StockAdjustmentCreateRequest request = StockAdjustmentCreateRequest.builder()
                .warehouseId(1L)
                .adjustmentReason(StockAdjustment.REASON_EXPIRED_DISPOSAL)
                .items(List.of(
                        StockAdjustmentItemRequest.builder()
                                .productId(100L)
                                .adjustmentQuantity(new BigDecimal("5"))
                                .batchNumber("") // Không nhập số lô
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> stockAdjustmentService.createAdjustment(request, warehouseStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quản lý theo lô, bắt buộc phải chọn số lô");
    }

    @Test
    @DisplayName("S8-03: Quản lý kho duyệt phiếu - Trừ tồn, trừ lô, ghi thẻ kho và ghi AuditLog có ID QL")
    void testApproveAdjustment_Success_UpdatesStockAndLotAndRecordsMovementAndAudit() {
        StockAdjustment adjustment = StockAdjustment.builder()
                .id(1L)
                .code("DC-202610-001")
                .warehouse(sampleWarehouse)
                .warehouseCode("KHO-TONG")
                .adjustmentReason(StockAdjustment.REASON_DAMAGED_GOODS)
                .status(StockAdjustment.STATUS_PENDING_APPROVAL)
                .createdById(10L) // Người lập = 10
                .createdByName("staff_user")
                .totalQuantity(new BigDecimal("10"))
                .totalLossValue(new BigDecimal("120000"))
                .build();

        StockAdjustmentLine line = StockAdjustmentLine.builder()
                .id(11L)
                .stockAdjustment(adjustment)
                .product(sampleProduct)
                .currentStock(new BigDecimal("100"))
                .adjustmentQuantity(new BigDecimal("10"))
                .costPrice(new BigDecimal("12000"))
                .lossValue(new BigDecimal("120000"))
                .hasBatchManagement(true)
                .batchNumber("LOT-HN-202610")
                .build();
        adjustment.addLine(line);

        when(stockAdjustmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(adjustment));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 100L)).thenReturn(Optional.of(sampleInventory));
        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(100L, 1L, "LOT-HN-202610"))
                .thenReturn(Optional.of(sampleLot));
        when(stockAdjustmentRepository.save(any(StockAdjustment.class))).thenReturn(adjustment);

        // Người duyệt là Quản lý kho ID = 20 (khác người lập 10)
        StockAdjustmentResponse response = stockAdjustmentService.approveAdjustment(
                1L, new StockAdjustmentDecisionRequest("Đã kiểm tra thực tế hàng vỡ"), warehouseManagerActor);

        assertThat(response.getStatus()).isEqualTo(StockAdjustment.STATUS_APPROVED);
        assertThat(adjustment.getApprovedById()).isEqualTo(20L);

        // 1. Kiểm tra tồn kho bị trừ: 100 - 10 = 90
        assertThat(sampleInventory.getPhysicalStock()).isEqualByComparingTo("90");
        verify(inventoryRepository).save(sampleInventory);

        // 2. Kiểm tra tồn lô bị trừ: 50 - 10 = 40
        assertThat(sampleLot.getQuantity()).isEqualByComparingTo("40");
        verify(productLotRepository).save(sampleLot);

        // 3. Tiêu chí 4: Thẻ kho StockMovement được ghi
        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movementCaptor.capture());
        StockMovement movement = movementCaptor.getValue();
        assertThat(movement.getMovementType()).isEqualTo(StockMovement.TYPE_STOCK_ADJUSTMENT);
        assertThat(movement.getQuantity()).isEqualByComparingTo("-10"); // Dấu âm
        assertThat(movement.getBalanceAfter()).isEqualByComparingTo("90");
        assertThat(movement.getDocType()).isEqualTo(StockMovement.DOC_STOCK_ADJUSTMENT);

        // 4. Ghi AuditLog ngầm với ID QL = 20
        ArgumentCaptor<CreateAuditLogEntry> auditCaptor = ArgumentCaptor.forClass(CreateAuditLogEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        CreateAuditLogEntry auditEntry = auditCaptor.getValue();
        assertThat(auditEntry.getModule()).isEqualTo(AuditModule.INVENTORY);
        assertThat(auditEntry.getAction()).isEqualTo("APPROVE_STOCK_ADJUSTMENT");
        assertThat(auditEntry.getActorId()).isEqualTo(20L); // Bắt buộc ID QL
    }

    @Test
    @DisplayName("S8-03: Chặn duyệt khi người lập và người duyệt là cùng một người")
    void testApproveAdjustment_FailWhenApproverIsCreator_EnforcesSegregationOfDuties() {
        StockAdjustment adjustment = StockAdjustment.builder()
                .id(1L)
                .code("DC-202610-001")
                .status(StockAdjustment.STATUS_PENDING_APPROVAL)
                .createdById(20L) // Người lập = 20
                .createdByName("manager_user")
                .build();

        when(stockAdjustmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(adjustment));

        // Người duyệt cũng là ID = 20 (cùng 1 người)
        assertThatThrownBy(() -> stockAdjustmentService.approveAdjustment(
                1L, new StockAdjustmentDecisionRequest("Tự duyệt phiếu của mình"), warehouseManagerActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Người lập và người duyệt không được là một người");

        verify(inventoryRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    @DisplayName("Chặn duyệt khi phiếu không ở trạng thái PENDING_APPROVAL")
    void testApproveAdjustment_FailWhenNotPendingApproval() {
        StockAdjustment adjustment = StockAdjustment.builder()
                .id(1L)
                .code("DC-202610-001")
                .status(StockAdjustment.STATUS_APPROVED) // Đã duyệt trước đó
                .createdById(10L)
                .build();

        when(stockAdjustmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(adjustment));

        assertThatThrownBy(() -> stockAdjustmentService.approveAdjustment(
                1L, null, warehouseManagerActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không ở trạng thái chờ duyệt");
    }

    @Test
    @DisplayName("S8-03: Quản lý kho từ chối duyệt phiếu - Lưu lý do từ chối và ghi AuditLog với ID QL")
    void testRejectAdjustment_Success_WithAuditLog() {
        StockAdjustment adjustment = StockAdjustment.builder()
                .id(1L)
                .code("DC-202610-001")
                .status(StockAdjustment.STATUS_PENDING_APPROVAL)
                .createdById(10L) // Người lập = 10
                .build();

        when(stockAdjustmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(adjustment));
        when(stockAdjustmentRepository.save(any(StockAdjustment.class))).thenReturn(adjustment);

        StockAdjustmentResponse response = stockAdjustmentService.rejectAdjustment(
                1L, new StockAdjustmentDecisionRequest("Chưa có biên bản xác nhận của thủ kho"), warehouseManagerActor);

        assertThat(response.getStatus()).isEqualTo(StockAdjustment.STATUS_REJECTED);
        assertThat(adjustment.getRejectionReason()).contains("Chưa có biên bản");
        assertThat(adjustment.getApprovedById()).isEqualTo(20L); // ID QL từ chối

        // Tồn kho không bị thay đổi
        verify(inventoryRepository, never()).save(any());

        // Ghi AuditLog ngầm với ID QL
        ArgumentCaptor<CreateAuditLogEntry> auditCaptor = ArgumentCaptor.forClass(CreateAuditLogEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("REJECT_STOCK_ADJUSTMENT");
        assertThat(auditCaptor.getValue().getActorId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Huỷ phiếu điều chỉnh khi còn ở trạng thái chờ duyệt thành công")
    void testCancelAdjustment_SuccessWhenPendingApproval() {
        StockAdjustment adjustment = StockAdjustment.builder()
                .id(1L)
                .code("DC-202610-001")
                .status(StockAdjustment.STATUS_PENDING_APPROVAL)
                .createdById(10L)
                .build();

        when(stockAdjustmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(adjustment));
        when(stockAdjustmentRepository.save(any(StockAdjustment.class))).thenReturn(adjustment);

        StockAdjustmentResponse response = stockAdjustmentService.cancelAdjustment(1L, "Lập nhầm kho", warehouseStaffActor);

        assertThat(response.getStatus()).isEqualTo(StockAdjustment.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("Chặn huỷ phiếu điều chỉnh khi đã được phê duyệt")
    void testCancelAdjustment_FailWhenAlreadyApproved() {
        StockAdjustment adjustment = StockAdjustment.builder()
                .id(1L)
                .code("DC-202610-001")
                .status(StockAdjustment.STATUS_APPROVED) // Đã duyệt
                .build();

        when(stockAdjustmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(adjustment));

        assertThatThrownBy(() -> stockAdjustmentService.cancelAdjustment(1L, "Muốn huỷ sau khi duyệt", warehouseStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không thể huỷ phiếu điều chỉnh tồn kho ở trạng thái APPROVED");
    }
}
