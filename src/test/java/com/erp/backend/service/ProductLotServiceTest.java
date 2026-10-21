package com.erp.backend.service;

import com.erp.backend.dto.inventory.lot.*;
import com.erp.backend.dto.product.ProductResponse;
import com.erp.backend.dto.user.PageResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductLotServiceTest {

    @Mock private ProductLotRepository productLotRepository;
    @Mock private ProductRepository productRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private ProductLotService service;

    private final UserDetailsImpl staff = actor(1, "ROLE_WAREHOUSE");
    private final UserDetailsImpl manager = actor(2, "ROLE_WH_MANAGER");
    private final UserDetailsImpl admin = actor(3, "ROLE_ADMIN");

    private Warehouse whHn;
    private Product productPepsi;
    private Supplier supplierVnm;

    @BeforeEach
    void setUp() {
        whHn = Warehouse.builder().id(10L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
        productPepsi = Product.builder().id(20L).sku("SP-PEPSI-330").name("Pepsi 330ml").baseUnit("Lon")
                .hasBatchManagement(false).status("ACTIVE").build();
        supplierVnm = Supplier.builder().id(30L).code("SUP-VNM").name("Vinamilk").build();

        lenient().when(productLotRepository.save(any(ProductLot.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("S6-01 (TC601-08): Khai báo lô khi SKU chưa bật quản lý lô -> lỗi 400 Bad Request")
    void createLot_whenHasBatchManagementFalse_throwsBadRequest() {
        ProductLotCreateRequest req = ProductLotCreateRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .batchNumber("LOT-202610-01")
                .manufactureDate(LocalDate.now().minusMonths(1))
                .expiredDate(LocalDate.now().plusMonths(5))
                .initialQuantity(new BigDecimal("100"))
                .supplierId(30L)
                .notes("Lô hàng nhập mới")
                .build();

        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));
        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(whHn));
        when(supplierRepository.findById(30L)).thenReturn(Optional.of(supplierVnm));

        assertThatThrownBy(() -> service.createLot(req, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chưa được bật quản lý theo lô hàng");
    }

    @Test
    @DisplayName("S6-01: Khai báo lô hàng mới hợp lệ cho SKU đã bật quản lý lô -> Thành công")
    void createLot_success_whenBatchManagementEnabled() {
        productPepsi.setHasBatchManagement(true);
        ProductLotCreateRequest req = ProductLotCreateRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .batchNumber("LOT-202610-01")
                .manufactureDate(LocalDate.now().minusMonths(1))
                .expiredDate(LocalDate.now().plusMonths(5))
                .initialQuantity(new BigDecimal("100"))
                .supplierId(30L)
                .notes("Lô hàng nhập mới")
                .build();

        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));
        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(whHn));
        when(supplierRepository.findById(30L)).thenReturn(Optional.of(supplierVnm));
        when(productLotRepository.existsByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-202610-01")).thenReturn(false);

        ProductLotResponse res = service.createLot(req, manager);

        assertThat(res).isNotNull();
        assertThat(res.getBatchNumber()).isEqualTo("LOT-202610-01");
        assertThat(res.getQuantity()).isEqualByComparingTo("100");
        assertThat(res.getExpiryStatus()).isEqualTo("SAFE");
        assertThat(res.getProductSku()).isEqualTo("SP-PEPSI-330");
    }

    @Test
    @DisplayName("S6-01: Khai báo lô có ngày HSD trước ngày SX -> lỗi 400 Bad Request")
    void createLot_invalidDates_throwsBadRequest() {
        ProductLotCreateRequest req = ProductLotCreateRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .batchNumber("LOT-ERR-01")
                .manufactureDate(LocalDate.now())
                .expiredDate(LocalDate.now().minusDays(1)) // HSD trước NSX
                .build();

        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));
        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(whHn));

        assertThatThrownBy(() -> service.createLot(req, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hạn sử dụng không được nhỏ hơn ngày sản xuất");
    }

    @Test
    @DisplayName("S6-01: Khai báo trùng số lô trong cùng một kho -> lỗi 409 Conflict")
    void createLot_duplicateBatchNumber_throwsConflict() {
        ProductLotCreateRequest req = ProductLotCreateRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .batchNumber("LOT-DUP-01")
                .expiredDate(LocalDate.now().plusMonths(3))
                .build();

        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));
        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(whHn));
        when(productLotRepository.existsByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-DUP-01")).thenReturn(true);

        assertThatThrownBy(() -> service.createLot(req, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã tồn tại cho sản phẩm này");
    }

    @Test
    @DisplayName("S6-01: Cập nhật trạng thái lô hàng thành LOCKED thành công")
    void updateLot_statusToLocked_success() {
        ProductLot lot = ProductLot.builder()
                .id(1L)
                .product(productPepsi)
                .warehouse(whHn)
                .batchNumber("LOT-01")
                .quantity(new BigDecimal("50"))
                .status("ACTIVE")
                .build();

        when(productLotRepository.findById(1L)).thenReturn(Optional.of(lot));

        ProductLotUpdateRequest req = ProductLotUpdateRequest.builder()
                .status("LOCKED")
                .notes("Khóa lô để kiểm định chất lượng")
                .build();

        ProductLotResponse res = service.updateLot(1L, req, manager);

        assertThat(res.getStatus()).isEqualTo("LOCKED");
        assertThat(lot.getStatus()).isEqualTo("LOCKED");
        assertThat(lot.getNotes()).isEqualTo("Khóa lô để kiểm định chất lượng");
    }

    @Test
    @DisplayName("S6-01: Bật/tắt quản lý lô theo SKU thành công khi là Quản lý kho")
    void toggleBatchManagement_asWhManager_success() {
        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));

        ProductResponse res = service.toggleBatchManagement(20L, true, manager);

        assertThat(res).isNotNull();
        assertThat(res.getSku()).isEqualTo("SP-PEPSI-330");
        assertThat(res.getHasBatchManagement()).isTrue();
        assertThat(productPepsi.getHasBatchManagement()).isTrue();
    }

    @Test
    @DisplayName("S6-01: Bật/tắt quản lý lô theo SKU thành công khi là Nhân viên kho")
    void toggleBatchManagement_asWarehouseStaff_success() {
        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));

        ProductResponse res = service.toggleBatchManagement(20L, true, staff);

        assertThat(res).isNotNull();
        assertThat(res.getSku()).isEqualTo("SP-PEPSI-330");
        assertThat(res.getHasBatchManagement()).isTrue();
        assertThat(productPepsi.getHasBatchManagement()).isTrue();
    }

    @Test
    @DisplayName("S6-01 (TC601-09): Tắt quản lý lô khi vẫn còn hàng trong các lô -> lỗi 400 Bad Request")
    void toggleBatchManagement_disableWhenStockRemaining_throwsBadRequest() {
        productPepsi.setHasBatchManagement(true);
        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));

        ProductLot lotWithStock = ProductLot.builder().id(101L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-01").quantity(new BigDecimal("100")).status("ACTIVE").build();
        when(productLotRepository.findByProduct_Id(20L)).thenReturn(List.of(lotWithStock));

        assertThatThrownBy(() -> service.toggleBatchManagement(20L, false, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vẫn còn 100 đơn vị tồn kho trong các lô hàng");
    }

    @Test
    @DisplayName("S6-01: Tính tồn theo lô & phân loại FEFO chính xác (quá hạn, sắp hết hạn, an toàn)")
    void calculateStockByLot_fefoAndCategoriesCalculatedCorrectly() {
        LocalDate today = LocalDate.now();

        // Lot 1: Đã quá hạn (hết hạn 5 ngày trước, tồn 15)
        ProductLot lotExpired = ProductLot.builder().id(101L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-EXP").expiredDate(today.minusDays(5)).quantity(new BigDecimal("15")).build();

        // Lot 2: Sắp hết hạn trong 10 ngày (tồn 25)
        ProductLot lotNear = ProductLot.builder().id(102L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-NEAR").expiredDate(today.plusDays(10)).quantity(new BigDecimal("25")).build();

        // Lot 3: Còn hạn an toàn 90 ngày (tồn 60)
        ProductLot lotSafe = ProductLot.builder().id(103L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-SAFE").expiredDate(today.plusDays(90)).quantity(new BigDecimal("60")).build();

        when(productRepository.findById(20L)).thenReturn(Optional.of(productPepsi));
        when(warehouseRepository.findById(10L)).thenReturn(Optional.of(whHn));
        when(productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(20L, 10L))
                .thenReturn(List.of(lotExpired, lotNear, lotSafe));

        ProductLotStockResponse stock = service.calculateStockByLot(20L, 10L, 30, manager);

        assertThat(stock.getTotalStock()).isEqualByComparingTo("100");
        assertThat(stock.getExpiredStock()).isEqualByComparingTo("15");
        assertThat(stock.getNearExpiryStock()).isEqualByComparingTo("25");
        assertThat(stock.getValidStock()).isEqualByComparingTo("85"); // 25 + 60
        assertThat(stock.getLots()).hasSize(3);
        assertThat(stock.getLots().get(0).getBatchNumber()).isEqualTo("LOT-EXP");
    }

    @Test
    @DisplayName("S6-01: Kiểm tra HSD khi xuất kho - Lô bị khóa (LOCKED) thì từ chối xuất")
    void validateLotForDispatch_lockedLot_rejected() {
        ProductLot lockedLot = ProductLot.builder().id(1L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-LCK").quantity(new BigDecimal("50")).status("LOCKED")
                .expiredDate(LocalDate.now().plusMonths(3)).build();

        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-LCK"))
                .thenReturn(Optional.of(lockedLot));

        LotDispatchValidationRequest req = LotDispatchValidationRequest.builder()
                .productId(20L).warehouseId(10L).batchNumber("LOT-LCK").quantity(new BigDecimal("10")).build();

        LotDispatchValidationResponse res = service.validateLotForDispatch(req, staff);

        assertThat(res.isValid()).isFalse();
        assertThat(res.isLocked()).isTrue();
        assertThat(res.getMessage()).contains("đang bị khóa");
    }

    @Test
    @DisplayName("S6-01: Kiểm tra HSD khi xuất kho - Không đủ tồn trong lô thì từ chối xuất")
    void validateLotForDispatch_insufficientQuantity_rejected() {
        ProductLot lot = ProductLot.builder().id(1L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-01").quantity(new BigDecimal("5")).status("ACTIVE")
                .expiredDate(LocalDate.now().plusMonths(3)).build();

        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-01"))
                .thenReturn(Optional.of(lot));

        LotDispatchValidationRequest req = LotDispatchValidationRequest.builder()
                .productId(20L).warehouseId(10L).batchNumber("LOT-01").quantity(new BigDecimal("20")).build();

        LotDispatchValidationResponse res = service.validateLotForDispatch(req, staff);

        assertThat(res.isValid()).isFalse();
        assertThat(res.isInsufficientQuantity()).isTrue();
        assertThat(res.getMessage()).contains("không đủ để xuất");
    }

    @Test
    @DisplayName("S6-01: Kiểm tra HSD khi xuất kho - Lô quá hạn không có ghi đè thì cấm xuất")
    void validateLotForDispatch_expiredWithoutOverride_rejected() {
        ProductLot expiredLot = ProductLot.builder().id(1L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-EXP-01").quantity(new BigDecimal("50")).status("ACTIVE")
                .expiredDate(LocalDate.now().minusDays(2)).build();

        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-EXP-01"))
                .thenReturn(Optional.of(expiredLot));

        LotDispatchValidationRequest req = LotDispatchValidationRequest.builder()
                .productId(20L).warehouseId(10L).batchNumber("LOT-EXP-01").quantity(new BigDecimal("10"))
                .overrideExpired(false).build();

        LotDispatchValidationResponse res = service.validateLotForDispatch(req, staff);

        assertThat(res.isValid()).isFalse();
        assertThat(res.isExpired()).isTrue();
        assertThat(res.getMessage()).contains("đã hết hạn sử dụng");
    }

    @Test
    @DisplayName("S6-01: Kiểm tra HSD khi xuất kho - Quản lý kho ghi đè kèm lý do cho phép xuất lô quá hạn")
    void validateLotForDispatch_expiredWithManagerOverride_success() {
        ProductLot expiredLot = ProductLot.builder().id(1L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-EXP-01").quantity(new BigDecimal("50")).status("ACTIVE")
                .expiredDate(LocalDate.now().minusDays(2)).build();

        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-EXP-01"))
                .thenReturn(Optional.of(expiredLot));

        LotDispatchValidationRequest req = LotDispatchValidationRequest.builder()
                .productId(20L).warehouseId(10L).batchNumber("LOT-EXP-01").quantity(new BigDecimal("10"))
                .overrideExpired(true)
                .overrideReason("Xuất hủy lô hàng theo quyết định số 123/QD-KHO")
                .build();

        LotDispatchValidationResponse res = service.validateLotForDispatch(req, manager);

        assertThat(res.isValid()).isTrue();
        assertThat(res.isOverrideApplied()).isTrue();
        assertThat(res.isExpired()).isTrue();
        assertThat(res.getMessage()).contains("Quản lý kho đã xác nhận ghi đè");
    }

    @Test
    @DisplayName("S6-01: Kiểm tra HSD khi xuất kho - Nhân viên kho cố tình ghi đè xuất lô quá hạn -> lỗi 403 Forbidden")
    void validateLotForDispatch_expiredWithStaffOverride_throwsForbidden() {
        ProductLot expiredLot = ProductLot.builder().id(1L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-EXP-01").quantity(new BigDecimal("50")).status("ACTIVE")
                .expiredDate(LocalDate.now().minusDays(2)).build();

        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-EXP-01"))
                .thenReturn(Optional.of(expiredLot));

        LotDispatchValidationRequest req = LotDispatchValidationRequest.builder()
                .productId(20L).warehouseId(10L).batchNumber("LOT-EXP-01").quantity(new BigDecimal("10"))
                .overrideExpired(true)
                .overrideReason("Nhan vien tu y xuat")
                .build();

        assertThatThrownBy(() -> service.validateLotForDispatch(req, staff))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Chỉ Quản lý kho hoặc Quản trị viên mới có quyền");
    }

    @Test
    @DisplayName("S6-01: Thống kê tổng quan tình trạng các lô hàng trong kho")
    void getLotSummary_calculatesCorrectBuckets() {
        LocalDate today = LocalDate.now();

        ProductLot lot1 = ProductLot.builder().id(1L).warehouse(whHn).quantity(new BigDecimal("10"))
                .expiredDate(today.minusDays(5)).build(); // expired
        ProductLot lot2 = ProductLot.builder().id(2L).warehouse(whHn).quantity(new BigDecimal("20"))
                .expiredDate(today.plusDays(10)).build(); // near expiry
        ProductLot lot3 = ProductLot.builder().id(3L).warehouse(whHn).quantity(new BigDecimal("30"))
                .expiredDate(today.plusDays(80)).build(); // safe
        ProductLot lot4 = ProductLot.builder().id(4L).warehouse(whHn).quantity(BigDecimal.ZERO)
                .expiredDate(today.plusDays(40)).build(); // out of stock

        when(productLotRepository.findByWarehouse_Id(10L)).thenReturn(List.of(lot1, lot2, lot3, lot4));

        LotStockSummaryResponse summary = service.getLotSummary(30, 10L, manager);

        assertThat(summary.getTotalLots()).isEqualTo(4);
        assertThat(summary.getTotalQuantity()).isEqualByComparingTo("60");
        assertThat(summary.getExpiredLots()).isEqualTo(1);
        assertThat(summary.getExpiredQuantity()).isEqualByComparingTo("10");
        assertThat(summary.getNearExpiryLots()).isEqualTo(1);
        assertThat(summary.getNearExpiryQuantity()).isEqualByComparingTo("20");
        assertThat(summary.getSafeLots()).isEqualTo(1);
        assertThat(summary.getSafeQuantity()).isEqualByComparingTo("30");
        assertThat(summary.getOutOfStockLots()).isEqualTo(1);
        assertThat(summary.getOutOfStockQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("S6-01 (TC601-23): deductLotStock - Lô đã hết hạn -> Bị từ chối lỗi 400 Bad Request")
    void deductLotStock_expiredLot_throwsBadRequest() {
        ProductLot expiredLot = ProductLot.builder().id(101L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-EXP-01").quantity(new BigDecimal("50")).status("ACTIVE")
                .expiredDate(LocalDate.now().minusDays(3)).build();

        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(20L, 10L, "LOT-EXP-01"))
                .thenReturn(Optional.of(expiredLot));

        assertThatThrownBy(() -> service.deductLotStock(20L, 10L, "LOT-EXP-01", new BigDecimal("10"), "SO", 1L, "SO-01", manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã hết hạn sử dụng");
    }

    @Test
    @DisplayName("S6-01 (TC601-25): deductLotStock - Lô đang khóa (LOCKED) -> Bị từ chối lỗi 400 Bad Request")
    void deductLotStock_lockedLot_throwsBadRequest() {
        ProductLot lockedLot = ProductLot.builder().id(102L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-LCK-01").quantity(new BigDecimal("50")).status("LOCKED")
                .expiredDate(LocalDate.now().plusMonths(3)).build();

        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(20L, 10L, "LOT-LCK-01"))
                .thenReturn(Optional.of(lockedLot));

        assertThatThrownBy(() -> service.deductLotStock(20L, 10L, "LOT-LCK-01", new BigDecimal("10"), "SO", 1L, "SO-01", manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đang bị khóa (LOCKED)");
    }

    @Test
    @DisplayName("S6-01 (TC601-28): validateLotForDispatch - Hỗ trợ tùy chỉnh ngưỡng cảnh báo cận hạn (VD: 45 ngày)")
    void validateLotForDispatch_withCustomNearExpiryDays_evaluatesCorrectly() {
        // Lô còn 40 ngày HSD: với ngưỡng mặc định 30 ngày -> false; với ngưỡng tùy chỉnh 45 ngày -> true
        ProductLot lot40Days = ProductLot.builder().id(103L).product(productPepsi).warehouse(whHn)
                .batchNumber("LOT-40D").quantity(new BigDecimal("50")).status("ACTIVE")
                .expiredDate(LocalDate.now().plusDays(40)).build();

        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-40D"))
                .thenReturn(Optional.of(lot40Days));

        // 1. Không truyền ngưỡng (mặc định 30) -> nearExpiry = false
        LotDispatchValidationRequest reqDef = LotDispatchValidationRequest.builder()
                .productId(20L).warehouseId(10L).batchNumber("LOT-40D").quantity(new BigDecimal("10")).build();
        LotDispatchValidationResponse resDef = service.validateLotForDispatch(reqDef, staff);
        assertThat(resDef.isNearExpiry()).isFalse();

        // 2. Truyền ngưỡng 45 ngày -> nearExpiry = true
        LotDispatchValidationRequest req45 = LotDispatchValidationRequest.builder()
                .productId(20L).warehouseId(10L).batchNumber("LOT-40D").quantity(new BigDecimal("10"))
                .nearExpiryDays(45).build();
        LotDispatchValidationResponse res45 = service.validateLotForDispatch(req45, staff);
        assertThat(res45.isNearExpiry()).isTrue();
    }
}
