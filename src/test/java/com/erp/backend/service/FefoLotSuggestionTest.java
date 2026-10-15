package com.erp.backend.service;

import com.erp.backend.dto.inventory.lot.FefoLotSuggestionResponse;
import com.erp.backend.dto.inventory.lot.LotSelectionValidationRequest;
import com.erp.backend.dto.inventory.lot.LotSelectionValidationResponse;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductLot;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.entity.WarehouseLocation;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductLotRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test FEFO Lot Suggestion & Stock Deduction - S6-04 Gợi ý lô xuất FEFO")
class FefoLotSuggestionTest {

    @Mock private ProductLotRepository productLotRepository;
    @Mock private ProductRepository productRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private ProductLotService productLotService;

    private final UserDetailsImpl warehouseStaff = actor(1, "ROLE_ADMIN");

    private Warehouse warehouse;
    private Product product;
    private ProductLot lotA; // Exp in 10 days, qty = 20
    private ProductLot lotB; // Exp in 30 days, qty = 50
    private ProductLot lotC; // Exp in 60 days, qty = 100
    private ProductLot lotExpired; // Expired yesterday, qty = 50
    private ProductLot lotLocked; // Locked, qty = 50

    @BeforeEach
    void setUp() {
        warehouse = Warehouse.builder().id(10L).code("WH-01").name("Kho Tổng").status("ACTIVE").build();
        product = Product.builder().id(20L).sku("SKU-MILK-100").name("Sữa tươi tiệt trùng 1L")
                .hasBatchManagement(true).status("ACTIVE").build();

        WarehouseLocation locA = WarehouseLocation.builder().id(101L).code("LOC-A1").name("Kệ A1").build();
        WarehouseLocation locB = WarehouseLocation.builder().id(102L).code("LOC-B2").name("Kệ B2").build();
        WarehouseLocation locC = WarehouseLocation.builder().id(103L).code("LOC-C3").name("Kệ C3").build();

        LocalDate today = LocalDate.now();

        lotA = ProductLot.builder().id(1L).product(product).warehouse(warehouse)
                .batchNumber("LOT-A")
                .manufactureDate(today.minusMonths(2))
                .expiredDate(today.plusDays(10))
                .quantity(new BigDecimal("20.00"))
                .status("ACTIVE")
                .location(locA)
                .build();

        lotB = ProductLot.builder().id(2L).product(product).warehouse(warehouse)
                .batchNumber("LOT-B")
                .manufactureDate(today.minusMonths(1))
                .expiredDate(today.plusDays(30))
                .quantity(new BigDecimal("50.00"))
                .status("ACTIVE")
                .location(locB)
                .build();

        lotC = ProductLot.builder().id(3L).product(product).warehouse(warehouse)
                .batchNumber("LOT-C")
                .manufactureDate(today)
                .expiredDate(today.plusDays(60))
                .quantity(new BigDecimal("100.00"))
                .status("ACTIVE")
                .location(locC)
                .build();

        lotExpired = ProductLot.builder().id(4L).product(product).warehouse(warehouse)
                .batchNumber("LOT-EXP")
                .manufactureDate(today.minusMonths(6))
                .expiredDate(today.minusDays(1))
                .quantity(new BigDecimal("50.00"))
                .status("ACTIVE")
                .build();

        lotLocked = ProductLot.builder().id(5L).product(product).warehouse(warehouse)
                .batchNumber("LOT-LCK")
                .manufactureDate(today.minusMonths(1))
                .expiredDate(today.plusDays(40))
                .quantity(new BigDecimal("50.00"))
                .status("LOCKED")
                .build();

        lenient().when(productRepository.findById(20L)).thenReturn(Optional.of(product));
        lenient().when(warehouseRepository.findById(10L)).thenReturn(Optional.of(warehouse));
        lenient().when(productLotRepository.save(any(ProductLot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("S6-04 AC1: Gợi ý lô có HSD gần nhất còn đủ số lượng (Single lot sufficiency)")
    void getFefoLotSuggestion_singleLotSufficiency_suggestsNearestSufficientLot() {
        // Cần xuất 30: Lot A (20) không đủ, Lot B (50) có HSD gần nhất (30 ngày) và đủ số lượng
        when(productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(20L, 10L))
                .thenReturn(List.of(lotA, lotB, lotC));

        FefoLotSuggestionResponse res = productLotService.getFefoLotSuggestion(
                20L, 10L, new BigDecimal("30.00"), warehouseStaff);

        assertThat(res).isNotNull();
        assertThat(res.getProductId()).isEqualTo(20L);
        assertThat(res.getWarehouseId()).isEqualTo(10L);
        assertThat(res.getRequestedQuantity()).isEqualByComparingTo("30.00");
        assertThat(res.getIsSingleLotSufficient()).isTrue();
        assertThat(res.getSuggestedBatchNumber()).isEqualTo("LOT-B");
        assertThat(res.getSuggestedLot()).isNotNull();
        assertThat(res.getSuggestedLot().getBatchNumber()).isEqualTo("LOT-B");
        assertThat(res.getSuggestedLot().getIsDefault()).isTrue();
        assertThat(res.getSuggestedLot().getIsSufficient()).isTrue();
    }

    @Test
    @DisplayName("S6-04 AC1: Cần xuất ít hơn số lượng lô gần nhất -> chọn lô gần nhất (Lot A)")
    void getFefoLotSuggestion_smallQuantity_suggestsEarliestLot() {
        // Cần xuất 15: Lot A (20) đủ và có HSD gần nhất (10 ngày)
        when(productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(20L, 10L))
                .thenReturn(List.of(lotA, lotB, lotC));

        FefoLotSuggestionResponse res = productLotService.getFefoLotSuggestion(
                20L, 10L, new BigDecimal("15.00"), warehouseStaff);

        assertThat(res).isNotNull();
        assertThat(res.getIsSingleLotSufficient()).isTrue();
        assertThat(res.getSuggestedBatchNumber()).isEqualTo("LOT-A");
        assertThat(res.getSuggestedLot().getBatchNumber()).isEqualTo("LOT-A");
    }

    @Test
    @DisplayName("S6-04 AC1: Không có lô đơn lẻ nào đủ -> phân bổ tuần tự theo nguyên tắc FEFO")
    void getFefoLotSuggestion_noSingleLotSufficient_allocatesSequentiallyFefo() {
        // Cần xuất 120: Không lô nào >= 120 (A=20, B=50, C=100)
        // FEFO phân bổ: Lot A (20) -> Lot B (50) -> Lot C (50). Tổng = 120.
        when(productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(20L, 10L))
                .thenReturn(List.of(lotA, lotB, lotC));

        FefoLotSuggestionResponse res = productLotService.getFefoLotSuggestion(
                20L, 10L, new BigDecimal("120.00"), warehouseStaff);

        assertThat(res).isNotNull();
        assertThat(res.getIsSingleLotSufficient()).isFalse();
        assertThat(res.getSuggestedBatchNumber()).isEqualTo("LOT-A");
        assertThat(res.getAllocations()).hasSize(3);

        assertThat(res.getAllocations().get(0).getBatchNumber()).isEqualTo("LOT-A");
        assertThat(res.getAllocations().get(0).getAllocatedQuantity()).isEqualByComparingTo("20.00");

        assertThat(res.getAllocations().get(1).getBatchNumber()).isEqualTo("LOT-B");
        assertThat(res.getAllocations().get(1).getAllocatedQuantity()).isEqualByComparingTo("50.00");

        assertThat(res.getAllocations().get(2).getBatchNumber()).isEqualTo("LOT-C");
        assertThat(res.getAllocations().get(2).getAllocatedQuantity()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("S6-04 AC2: Chọn đúng lô gợi ý FEFO -> hợp lệ, không cần nhập lý do")
    void validateLotSelection_sameLot_validWithoutReason() {
        when(productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(20L, 10L))
                .thenReturn(List.of(lotA, lotB, lotC));
        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-B"))
                .thenReturn(Optional.of(lotB));

        LotSelectionValidationRequest req = LotSelectionValidationRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .selectedBatchNumber("LOT-B")
                .requestedQuantity(new BigDecimal("30.00"))
                .build();

        LotSelectionValidationResponse res = productLotService.validateLotSelection(req, warehouseStaff);

        assertThat(res.isValid()).isTrue();
        assertThat(res.isDifferentFromSuggested()).isFalse();
        assertThat(res.isRequiresReason()).isFalse();
        assertThat(res.getSuggestedBatchNumber()).isEqualTo("LOT-B");
        assertThat(res.getSelectedBatchNumber()).isEqualTo("LOT-B");
    }

    @Test
    @DisplayName("S6-04 AC2: Thủ kho chọn lô khác FEFO gợi ý mà không nhập lý do -> không hợp lệ")
    void validateLotSelection_differentLotWithoutReason_invalid() {
        when(productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(20L, 10L))
                .thenReturn(List.of(lotA, lotB, lotC));
        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-C"))
                .thenReturn(Optional.of(lotC));

        LotSelectionValidationRequest req = LotSelectionValidationRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .selectedBatchNumber("LOT-C")
                .requestedQuantity(new BigDecimal("30.00"))
                .lotChangeReason("") // Không có lý do
                .build();

        LotSelectionValidationResponse res = productLotService.validateLotSelection(req, warehouseStaff);

        assertThat(res.isValid()).isFalse();
        assertThat(res.isDifferentFromSuggested()).isTrue();
        assertThat(res.isRequiresReason()).isTrue();
        assertThat(res.isReasonValid()).isFalse();
        assertThat(res.getMessage()).contains("Bắt buộc phải nhập lý do đổi lô");
    }

    @Test
    @DisplayName("S6-04 AC2: Thủ kho chọn lô khác kèm lý do đầy đủ (>= 5 ký tự) -> hợp lệ")
    void validateLotSelection_differentLotWithValidReason_valid() {
        when(productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(20L, 10L))
                .thenReturn(List.of(lotA, lotB, lotC));
        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-C"))
                .thenReturn(Optional.of(lotC));

        LotSelectionValidationRequest req = LotSelectionValidationRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .selectedBatchNumber("LOT-C")
                .requestedQuantity(new BigDecimal("30.00"))
                .lotChangeReason("Khách hàng yêu cầu date xa trên 60 ngày")
                .build();

        LotSelectionValidationResponse res = productLotService.validateLotSelection(req, warehouseStaff);

        assertThat(res.isValid()).isTrue();
        assertThat(res.isDifferentFromSuggested()).isTrue();
        assertThat(res.isRequiresReason()).isTrue();
        assertThat(res.isReasonValid()).isTrue();
        assertThat(res.isOverrideApplied()).isFalse();
    }

    @Test
    @DisplayName("S6-04 AC2: Chọn lô hàng đã hết hạn sử dụng -> không hợp lệ")
    void validateLotSelection_expiredLot_invalid() {
        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-EXP"))
                .thenReturn(Optional.of(lotExpired));

        LotSelectionValidationRequest req = LotSelectionValidationRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .selectedBatchNumber("LOT-EXP")
                .requestedQuantity(new BigDecimal("10.00"))
                .lotChangeReason("Xuất mẫu test")
                .build();

        LotSelectionValidationResponse res = productLotService.validateLotSelection(req, warehouseStaff);

        assertThat(res.isValid()).isFalse();
        assertThat(res.isExpired()).isTrue();
        assertThat(res.getMessage()).contains("đã hết hạn sử dụng");
    }

    @Test
    @DisplayName("S6-04 AC2: Chọn lô hàng đang bị khóa -> không hợp lệ")
    void validateLotSelection_lockedLot_invalid() {
        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(20L, 10L, "LOT-LCK"))
                .thenReturn(Optional.of(lotLocked));

        LotSelectionValidationRequest req = LotSelectionValidationRequest.builder()
                .productId(20L)
                .warehouseId(10L)
                .selectedBatchNumber("LOT-LCK")
                .requestedQuantity(new BigDecimal("10.00"))
                .lotChangeReason("Đổi lô lấy hàng gần")
                .build();

        LotSelectionValidationResponse res = productLotService.validateLotSelection(req, warehouseStaff);

        assertThat(res.isValid()).isFalse();
        assertThat(res.isLocked()).isTrue();
        assertThat(res.getMessage()).contains("đang bị khóa");
    }

    @Test
    @DisplayName("S6-04 AC3: Trừ đúng tồn của lô đã chọn với khóa bi quan (Pessimistic Lock)")
    void deductLotStock_valid_reducesLotQuantity() {
        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(20L, 10L, "LOT-B"))
                .thenReturn(Optional.of(lotB));

        ProductLot updated = productLotService.deductLotStock(
                20L, 10L, "LOT-B", new BigDecimal("20.00"),
                "SALES_ORDER", 1001L, "SO-202610-001", warehouseStaff);

        assertThat(updated).isNotNull();
        assertThat(updated.getQuantity()).isEqualByComparingTo("30.00"); // 50 - 20 = 30
        verify(productLotRepository).save(lotB);
    }

    @Test
    @DisplayName("S6-04 AC3: Trừ tồn vượt quá số lượng hiện có trong lô -> lỗi CONFLICT")
    void deductLotStock_insufficientQuantity_throwsConflict() {
        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(20L, 10L, "LOT-A"))
                .thenReturn(Optional.of(lotA)); // lotA chỉ có 20.00

        assertThatThrownBy(() -> productLotService.deductLotStock(
                20L, 10L, "LOT-A", new BigDecimal("50.00"),
                "SALES_ORDER", 1001L, "SO-202610-001", warehouseStaff))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không đủ để trừ xuất kho");
    }

    @Test
    @DisplayName("S6-04 AC3: Trừ tồn lô không tồn tại -> lỗi NOT_FOUND")
    void deductLotStock_nonExistingLot_throwsNotFound() {
        when(productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(20L, 10L, "LOT-UNKNOWN"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productLotService.deductLotStock(
                20L, 10L, "LOT-UNKNOWN", new BigDecimal("10.00"),
                "SALES_ORDER", 1001L, "SO-202610-001", warehouseStaff))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không tìm thấy lô");
    }
}
