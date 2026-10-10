package com.erp.backend.service;

import com.erp.backend.dto.stocktake.StockTakeCountRequest;
import com.erp.backend.dto.stocktake.StockTakeCreateRequest;
import com.erp.backend.dto.stocktake.StockTakeResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.StockTakeRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockTakeServiceTest {

    @Mock private StockTakeRepository stockTakeRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private StockLedgerService stockLedgerService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private StockTakeService service;

    private final UserDetailsImpl staff = actor(5, "ROLE_WAREHOUSE");
    private final UserDetailsImpl manager = actor(6, "ROLE_WH_MANAGER");
    private final Warehouse hn = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
    private final ProductCategory drinks = ProductCategory.builder().id(2L).name("Nước ngọt").level(2).build();
    private final ProductCategory spices = ProductCategory.builder().id(9L).name("Gia vị").level(1).build();
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca").baseUnit("Lon").status("ACTIVE")
            .productCategory(drinks).build();
    private final Product pepsi = Product.builder().id(11L).sku("SP-PEPSI").name("Pepsi").baseUnit("Lon").status("ACTIVE")
            .productCategory(drinks).build();
    private final Product salt = Product.builder().id(12L).sku("SP-MUOI").name("Muối").baseUnit("Gói").status("ACTIVE")
            .productCategory(spices).build();
    private final Product oldItem = Product.builder().id(13L).sku("SP-CU").name("Hàng cũ").baseUnit("Lon").status("INACTIVE")
            .productCategory(drinks).build();

    @BeforeEach
    void setUp() {
        lenient().when(stockTakeRepository.save(any(StockTake.class))).thenAnswer(inv -> inv.getArgument(0));
        // QL kho / NV kho trong test đều được xem kho này
        lenient().when(stockLedgerService.allowedWarehouseIds(any())).thenReturn(null);
    }

    private Inventory inv(Product p, String physical) {
        return Inventory.builder().id(100L + p.getId()).warehouse(hn).product(p)
                .physicalStock(new BigDecimal(physical)).reservedStock(BigDecimal.ZERO).build();
    }

    private StockTake draft(String... lines) {
        StockTake st = StockTake.builder().id(1L).code("KK261010-AAAA").warehouse(hn).status(StockTake.STATUS_DRAFT).build();
        long id = 1;
        for (int i = 0; i < lines.length; i += 3) {
            Product p = "SP-COCA".equals(lines[i]) ? coca : pepsi;
            StockTakeLine l = StockTakeLine.builder().id(id++).product(p).sku(p.getSku()).productName(p.getName())
                    .baseUnit("Lon").systemQuantity(new BigDecimal(lines[i + 1]))
                    .countedQuantity(lines[i + 2] == null ? null : new BigDecimal(lines[i + 2]))
                    .difference(lines[i + 2] == null ? null : new BigDecimal(lines[i + 2]).subtract(new BigDecimal(lines[i + 1])))
                    .build();
            st.addLine(l);
        }
        return st;
    }

    @Test
    @DisplayName("S5-08: Tạo phiếu theo nhóm hàng: chụp tồn sổ lúc tạo, chỉ hàng đang kinh doanh thuộc nhóm (gồm nhóm con)")
    void create_snapshotByCategory() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(hn));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(drinks));
        when(stockLedgerService.categoryWithDescendants(2L)).thenReturn(Set.of(2L));
        when(stockTakeRepository.existsByWarehouse_IdAndCategory_IdAndStatus(1L, 2L, StockTake.STATUS_DRAFT)).thenReturn(false);
        when(inventoryRepository.findByWarehouse_Id(1L)).thenReturn(List.of(inv(pepsi, "20"), inv(coca, "100"),
                inv(salt, "50"), inv(oldItem, "7")));
        StockTakeCreateRequest req = new StockTakeCreateRequest();
        req.setWarehouseId(1L);
        req.setCategoryId(2L);

        StockTakeResponse res = service.create(req, staff);

        assertThat(res.code()).startsWith("KK");
        assertThat(res.status()).isEqualTo(StockTake.STATUS_DRAFT);
        assertThat(res.lines()).extracting(StockTakeResponse.Line::sku).containsExactly("SP-COCA", "SP-PEPSI");
        assertThat(res.lines().get(0).systemQuantity()).isEqualByComparingTo("100");
        assertThat(res.lines().get(0).countedQuantity()).isNull();
    }

    @Test
    @DisplayName("S5-08: Đã có phiếu đang kiểm kê cùng kho cùng phạm vi -> 409")
    void create_duplicateDraft_conflict() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(hn));
        when(stockTakeRepository.existsByWarehouse_IdAndCategoryIsNullAndStatus(1L, StockTake.STATUS_DRAFT)).thenReturn(true);
        StockTakeCreateRequest req = new StockTakeCreateRequest();
        req.setWarehouseId(1L);

        assertThatThrownBy(() -> service.create(req, staff))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STOCKTAKE_IN_PROGRESS");
    }

    @Test
    @DisplayName("S5-08: Nhập số đếm -> hệ thống tự tính chênh lệch từng dòng")
    void counts_computeDifference() {
        StockTake st = draft("SP-COCA", "100", null, "SP-PEPSI", "20", null);
        when(stockTakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        StockTakeCountRequest req = new StockTakeCountRequest();
        StockTakeCountRequest.Line a = new StockTakeCountRequest.Line();
        a.setLineId(1L);
        a.setCountedQuantity(new BigDecimal("97"));
        StockTakeCountRequest.Line b = new StockTakeCountRequest.Line();
        b.setLineId(2L);
        b.setCountedQuantity(new BigDecimal("22"));
        req.setLines(List.of(a, b));

        StockTakeResponse res = service.updateCounts(1L, req, staff);

        assertThat(res.lines().get(0).difference()).isEqualByComparingTo("-3");
        assertThat(res.lines().get(1).difference()).isEqualByComparingTo("2");
        assertThat(res.countedCount()).isEqualTo(2);
        assertThat(res.differenceCount()).isEqualTo(2);
        assertThat(res.totalSurplus()).isEqualByComparingTo("2");
        assertThat(res.totalShortage()).isEqualByComparingTo("-3");
    }

    @Test
    @DisplayName("S5-08: Số đếm âm -> 400")
    void counts_negative_badRequest() {
        when(stockTakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(draft("SP-COCA", "100", null)));
        StockTakeCountRequest req = new StockTakeCountRequest();
        StockTakeCountRequest.Line a = new StockTakeCountRequest.Line();
        a.setLineId(1L);
        a.setCountedQuantity(new BigDecimal("-1"));
        req.setLines(List.of(a));

        assertThatThrownBy(() -> service.updateCounts(1L, req, staff))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNTED_QUANTITY");
    }

    @Test
    @DisplayName("S5-08: Chốt -> tồn = tồn hiện tại + (đếm - sổ lúc chụp), ghi nhật ký trước / sau, phiếu chuyển Đã chốt")
    void close_adjustsByDifference() {
        StockTake st = draft("SP-COCA", "100", "97", "SP-PEPSI", "20", "20");
        when(stockTakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        // Sau lúc chụp có thêm 10 lon được nhập: tồn hiện tại 110 -> sau chốt 107
        Inventory cocaStock = inv(coca, "110");
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(cocaStock));

        StockTakeResponse res = service.close(1L, "  Kiểm kê cuối tháng  ", manager);

        assertThat(cocaStock.getPhysicalStock()).isEqualByComparingTo("107");
        verify(inventoryRepository, never()).findByWarehouseIdAndProductIdForUpdate(1L, 11L);
        assertThat(res.status()).isEqualTo(StockTake.STATUS_CLOSED);
        assertThat(res.closeReason()).isEqualTo("Kiểm kê cuối tháng");
        assertThat(res.closedByUsername()).isEqualTo("user6");
        verify(auditLogService).record(eq(AuditModule.INVENTORY), eq("STOCKTAKE_ADJUST"), eq("INVENTORY"), eq(110L),
                eq("WH-MB01:SP-COCA"), eq("110"), eq("107"), contains("KK261010-AAAA"), eq(manager));
    }

    @Test
    @DisplayName("S5-08: Chốt không nhập lý do -> 400, không đụng tồn")
    void close_withoutReason_badRequest() {
        assertThatThrownBy(() -> service.close(1L, "  ", manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("S5-08: Chỉ Quản lý kho được chốt (NV kho -> bị chặn)")
    void close_byStaff_blocked() {
        assertThatThrownBy(() -> service.close(1L, "Cuối tháng", staff))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STOCKTAKE_CLOSE_NOT_ALLOWED");
        verifyNoInteractions(inventoryRepository, stockTakeRepository);
    }

    @Test
    @DisplayName("S5-08: Còn dòng chưa đếm -> 409")
    void close_notCounted_conflict() {
        when(stockTakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(draft("SP-COCA", "100", "97", "SP-PEPSI", "20", null)));

        assertThatThrownBy(() -> service.close(1L, "Cuối tháng", manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STOCKTAKE_NOT_COUNTED");
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("S5-08: Điều chỉnh làm tồn âm -> 409, không dòng nào bị điều chỉnh, không ghi nhật ký")
    void close_negativeStock_rollsBackAll() {
        StockTake st = draft("SP-COCA", "100", "110", "SP-PEPSI", "20", "0");
        when(stockTakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        Inventory cocaStock = inv(coca, "100");
        // Pepsi đã xuất bớt sau lúc chụp: còn 5, đếm 0 so với sổ 20 -> 5 - 20 = -15
        Inventory pepsiStock = inv(pepsi, "5");
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(cocaStock));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 11L)).thenReturn(Optional.of(pepsiStock));

        assertThatThrownBy(() -> service.close(1L, "Cuối tháng", manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NEGATIVE_STOCK");
        assertThat(cocaStock.getPhysicalStock()).isEqualByComparingTo("100");
        verify(inventoryRepository, never()).save(any(Inventory.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("S5-08: Phiếu đã chốt không sửa được")
    void closedStockTake_locked() {
        StockTake st = draft("SP-COCA", "100", "97");
        st.setStatus(StockTake.STATUS_CLOSED);
        when(stockTakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        StockTakeCountRequest req = new StockTakeCountRequest();
        StockTakeCountRequest.Line a = new StockTakeCountRequest.Line();
        a.setLineId(1L);
        a.setCountedQuantity(BigDecimal.ONE);
        req.setLines(List.of(a));

        assertThatThrownBy(() -> service.updateCounts(1L, req, staff))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STOCKTAKE_LOCKED");
    }

    @Test
    @DisplayName("S5-08: NV kho xem phiếu của kho mình không được gắn -> 404")
    void otherWarehouse_hidden() {
        when(stockLedgerService.allowedWarehouseIds(staff)).thenReturn(Set.of(3L));
        when(stockTakeRepository.findById(1L)).thenReturn(Optional.of(draft("SP-COCA", "100", null)));

        assertThatThrownBy(() -> service.get(1L, staff))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("S5-08: Huỷ phiếu nháp bắt buộc lý do")
    void cancel_requiresReason() {
        assertThatThrownBy(() -> service.cancel(1L, null, manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");

        StockTake st = draft("SP-COCA", "100", null);
        when(stockTakeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(st));
        ArgumentCaptor<StockTake> saved = ArgumentCaptor.forClass(StockTake.class);

        service.cancel(1L, "Tạo nhầm kho", manager);

        verify(stockTakeRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(StockTake.STATUS_CANCELLED);
    }
}
