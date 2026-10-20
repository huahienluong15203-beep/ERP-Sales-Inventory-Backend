package com.erp.backend.service;

import com.erp.backend.dto.inventory.stockcard.StockCardResponse;
import com.erp.backend.dto.inventory.stockcard.StockCardRow;
import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.StockMovement;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.StockMovementRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** S6-02: Ghi thẻ kho cùng transaction và xem thẻ kho theo kho / sản phẩm / thời gian. */
@ExtendWith(MockitoExtension.class)
class StockMovementServiceTest {

    @Mock private StockMovementRepository movementRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StockLedgerService stockLedgerService;

    @InjectMocks private StockMovementService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 30);

    private final Warehouse hn = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca lon").baseUnit("Lon").build();
    private final UserDetailsImpl staff = actor(5, "ROLE_WAREHOUSE");

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
    }

    private Inventory inv(String physical) {
        return Inventory.builder().id(100L).warehouse(hn).product(coca)
                .physicalStock(new BigDecimal(physical)).reservedStock(BigDecimal.ZERO).build();
    }

    private StockMovement movement(long id, String type, String qty, String balance, LocalDateTime at) {
        return StockMovement.builder().id(id).warehouse(hn).warehouseCode("WH-MB01").product(coca).productSku("SP-COCA")
                .productName("Coca lon").baseUnit("Lon").movementType(type).quantity(new BigDecimal(qty))
                .balanceAfter(new BigDecimal(balance)).docType(StockMovement.DOC_GOODS_RECEIPT).docId(7L)
                .docCode("PNK-202610-001").movementAt(at).build();
    }

    // ======================= GHI THẺ KHO =======================

    @Test
    @DisplayName("S6-02: Ghi thẻ kho lấy số dư sau nghiệp vụ = tồn thực tế hiện tại, kèm chứng từ, người làm, giờ VN")
    void record_savesBalanceAfterAndDocument() {
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(i -> i.getArgument(0));

        StockMovement m = service.record(inv("290"), StockMovement.TYPE_RECEIPT, new BigDecimal("240"),
                StockMovement.DOC_GOODS_RECEIPT, 20L, "PNK-202610-010", "Nhập kho từ NCC A", staff);

        assertThat(m.getBalanceAfter()).isEqualByComparingTo("290");
        assertThat(m.getQuantity()).isEqualByComparingTo("240");
        assertThat(m.getWarehouseCode()).isEqualTo("WH-MB01");
        assertThat(m.getProductSku()).isEqualTo("SP-COCA");
        assertThat(m.getBaseUnit()).isEqualTo("Lon");
        assertThat(m.getDocId()).isEqualTo(20L);
        assertThat(m.getDocCode()).isEqualTo("PNK-202610-010");
        assertThat(m.getActorUsername()).isEqualTo(staff.getUsername());
        assertThat(m.getMovementAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("S6-02: Số lượng 0 (vd nhận chuyển kho 0) -> không ghi dòng thẻ kho rỗng")
    void record_zeroQuantity_skipped() {
        assertThat(service.record(inv("10"), StockMovement.TYPE_TRANSFER_IN, BigDecimal.ZERO,
                StockMovement.DOC_STOCK_TRANSFER, 1L, "TRF-1", null, staff)).isNull();
        verifyNoInteractions(movementRepository);
    }

    @Test
    @DisplayName("S6-02: Diễn giải quá dài được cắt còn 500 ký tự (không lỗi khi lưu)")
    void record_longNote_truncated() {
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(i -> i.getArgument(0));

        StockMovement m = service.record(inv("5"), StockMovement.TYPE_STOCKTAKE_ADJUST, new BigDecimal("-1"),
                StockMovement.DOC_STOCK_TAKE, 3L, "KK-1", "x".repeat(800), null);

        assertThat(m.getNote()).hasSize(500);
        assertThat(m.getActorId()).isNull();
    }

    @Test
    @DisplayName("S6-02: Gắn id chứng từ cho các dòng ghi trước khi chứng từ được lưu; bỏ qua null, không ghi đè id đã có")
    void linkDocument_setsMissingIdsOnly() {
        StockMovement a = StockMovement.builder().build();
        StockMovement b = StockMovement.builder().docId(99L).build();

        service.linkDocument(new ArrayList<>(Arrays.asList(a, null, b)), 50L);

        assertThat(a.getDocId()).isEqualTo(50L);
        assertThat(b.getDocId()).isEqualTo(99L);
    }

    // ======================= XEM THẺ KHO =======================

    @Test
    @DisplayName("S6-02: 1 SKU tại 1 kho -> các dòng theo thời gian, tách cột Nhập / Xuất, số dư đầu kỳ / cuối kỳ")
    @SuppressWarnings("unchecked")
    void stockCard_singleSku_openingAndClosing() {
        when(warehouseRepository.existsById(1L)).thenReturn(true);
        when(productRepository.existsById(10L)).thenReturn(true);
        when(stockLedgerService.allowedWarehouseIds(staff)).thenReturn(Set.of(1L));
        LocalDateTime d1 = LocalDateTime.of(2026, 10, 2, 8, 0);
        LocalDateTime d2 = LocalDateTime.of(2026, 10, 3, 9, 0);
        List<StockMovement> rows = List.of(
                movement(1, StockMovement.TYPE_RECEIPT, "240", "290", d1),
                movement(2, StockMovement.TYPE_SALES_DISPATCH, "-40", "250", d2));
        when(movementRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 50), 2));
        LocalDateTime start = LocalDate.of(2026, 10, 1).atStartOfDay();
        LocalDateTime end = LocalDate.of(2026, 11, 1).atStartOfDay();
        when(movementRepository.findLatestBefore(eq(1L), eq(10L), eq(start), any(Pageable.class)))
                .thenReturn(List.of(movement(0, StockMovement.TYPE_RECEIPT, "50", "50", LocalDateTime.of(2026, 9, 30, 8, 0))));
        when(movementRepository.sumIn(1L, 10L, start, end)).thenReturn(new BigDecimal("240.0000"));
        when(movementRepository.sumOut(1L, 10L, start, end)).thenReturn(new BigDecimal("-40.0000"));
        when(movementRepository.findLatestInRange(eq(1L), eq(10L), eq(start), eq(end), any(Pageable.class)))
                .thenReturn(List.of(rows.get(1)));
        when(inventoryRepository.findByWarehouse_IdAndProduct_Id(1L, 10L)).thenReturn(Optional.of(inv("250")));

        StockCardResponse res = service.stockCard(1L, 10L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                null, 0, 50, staff);

        assertThat(res.openingBalance()).isEqualByComparingTo("50");
        assertThat(res.totalIn()).isEqualByComparingTo("240");
        assertThat(res.totalOut()).isEqualByComparingTo("40");
        assertThat(res.closingBalance()).isEqualByComparingTo("250");
        assertThat(res.currentPhysicalStock()).isEqualByComparingTo("250");
        List<StockCardRow> content = res.movements().content();
        assertThat(content).hasSize(2);
        assertThat(content.get(0).quantityIn()).isEqualByComparingTo("240");
        assertThat(content.get(0).quantityOut()).isEqualByComparingTo("0");
        assertThat(content.get(0).movementTypeLabel()).isEqualTo("Nhập kho");
        assertThat(content.get(1).quantityIn()).isEqualByComparingTo("0");
        assertThat(content.get(1).quantityOut()).isEqualByComparingTo("40");
        assertThat(content.get(1).balanceAfter()).isEqualByComparingTo("250");
        assertThat(content.get(1).docCode()).isEqualTo("PNK-202610-001");

        // Sắp theo thời gian mới nhất lên trước (DESC) rồi id
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(movementRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("movementAt").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    @DisplayName("S6-02: Không có nghiệp vụ trong kỳ -> số dư cuối kỳ = đầu kỳ")
    @SuppressWarnings("unchecked")
    void stockCard_noMovementInRange_closingEqualsOpening() {
        when(warehouseRepository.existsById(1L)).thenReturn(true);
        when(productRepository.existsById(10L)).thenReturn(true);
        when(stockLedgerService.allowedWarehouseIds(any())).thenReturn(null);
        when(movementRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        when(movementRepository.findLatestBefore(eq(1L), eq(10L), any(), any(Pageable.class)))
                .thenReturn(List.of(movement(0, StockMovement.TYPE_RECEIPT, "50", "70", LocalDateTime.of(2026, 9, 1, 8, 0))));
        when(movementRepository.sumIn(eq(1L), eq(10L), any(), any())).thenReturn(BigDecimal.ZERO);
        when(movementRepository.sumOut(eq(1L), eq(10L), any(), any())).thenReturn(BigDecimal.ZERO);
        when(movementRepository.findLatestInRange(eq(1L), eq(10L), any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(inventoryRepository.findByWarehouse_IdAndProduct_Id(1L, 10L)).thenReturn(Optional.empty());

        StockCardResponse res = service.stockCard(1L, 10L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                null, 0, 50, actor(1, "ROLE_ADMIN"));

        assertThat(res.openingBalance()).isEqualByComparingTo("70");
        assertThat(res.closingBalance()).isEqualByComparingTo("70");
        assertThat(res.currentPhysicalStock()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("S6-02: Không chọn ngày -> mặc định 30 ngày gần nhất; không chọn đủ kho + SKU -> không tính số dư")
    @SuppressWarnings("unchecked")
    void stockCard_defaultRange_noBalances() {
        when(stockLedgerService.allowedWarehouseIds(any())).thenReturn(null);
        when(movementRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        StockCardResponse res = service.stockCard(null, null, null, null, null, 0, 0, actor(1, "ROLE_ACCOUNTANT"));

        assertThat(res.toDate()).isEqualTo(NOW.toLocalDate());
        assertThat(res.fromDate()).isEqualTo(NOW.toLocalDate().minusDays(29));
        assertThat(res.openingBalance()).isNull();
        assertThat(res.closingBalance()).isNull();
        verify(movementRepository, never()).findLatestBefore(any(), any(), any(), any());
    }

    @Test
    @DisplayName("S6-02: NV kho xem thẻ kho của kho không được gắn -> 403, không truy vấn dữ liệu")
    void stockCard_otherWarehouse_forbidden() {
        when(warehouseRepository.existsById(2L)).thenReturn(true);
        when(stockLedgerService.allowedWarehouseIds(staff)).thenReturn(Set.of(1L));

        assertThatThrownBy(() -> service.stockCard(2L, null, null, null, null, 0, 50, staff))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STOCK_CARD_WAREHOUSE_NOT_ASSIGNED");
        verifyNoInteractions(movementRepository);
    }

    @Test
    @DisplayName("S6-02: NV kho chưa được gắn kho nào -> danh sách rỗng (không lộ dữ liệu kho khác)")
    void stockCard_noAssignedWarehouse_empty() {
        when(stockLedgerService.allowedWarehouseIds(staff)).thenReturn(Set.of());

        StockCardResponse res = service.stockCard(null, null, null, null, null, 0, 50, staff);

        assertThat(res.movements().content()).isEmpty();
        verifyNoInteractions(movementRepository);
    }

    @Test
    @DisplayName("S6-02: Ngày bắt đầu sau ngày kết thúc / quá 1 năm / loại nghiệp vụ lạ -> 400")
    void stockCard_invalidFilters_badRequest() {
        assertThatThrownBy(() -> service.stockCard(null, null, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1),
                null, 0, 50, staff))
                .hasFieldOrPropertyWithValue("code", "INVALID_DATE_RANGE");
        assertThatThrownBy(() -> service.stockCard(null, null, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 1),
                null, 0, 50, staff))
                .hasFieldOrPropertyWithValue("code", "DATE_RANGE_TOO_LONG");
        assertThatThrownBy(() -> service.stockCard(null, null, null, null, "XOA_HANG", 0, 50, staff))
                .hasFieldOrPropertyWithValue("code", "INVALID_MOVEMENT_TYPE");
        verifyNoInteractions(movementRepository);
    }

    @Test
    @DisplayName("S6-02: Kho / sản phẩm không tồn tại -> 404")
    void stockCard_unknownWarehouseOrProduct_notFound() {
        when(warehouseRepository.existsById(9L)).thenReturn(false);
        assertThatThrownBy(() -> service.stockCard(9L, null, null, null, null, 0, 50, staff))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("kho");

        when(productRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.stockCard(null, 99L, null, null, null, 0, 50, staff))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sản phẩm");
    }
}
