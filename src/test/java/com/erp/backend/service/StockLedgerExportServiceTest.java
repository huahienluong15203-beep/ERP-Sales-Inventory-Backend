package com.erp.backend.service;

import com.erp.backend.dto.inventory.StockLedgerCriteria;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** S8-08: Xuất Excel sổ tồn kho theo bộ lọc, giữ 3 cột tồn, tên tệp có mã kho và ngày. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class StockLedgerExportServiceTest {

    @Mock private StockLedgerService stockLedgerService;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductCategoryRepository categoryRepository;

    @InjectMocks private StockLedgerExportService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 9, 30);

    private final Warehouse hn = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
    private final UserDetailsImpl manager = actor(5, "ROLE_WH_MANAGER");

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
    }

    private Inventory inv(long id, String sku, String physical, String reserved) {
        ProductCategory cat = ProductCategory.builder().id(5L).name("Nước ngọt").level(2).build();
        Product p = Product.builder().id(id).sku(sku).name("Sản phẩm " + sku).baseUnit("Lon").status("ACTIVE")
                .productCategory(cat).build();
        return Inventory.builder().id(id * 10).warehouse(hn).product(p)
                .physicalStock(new BigDecimal(physical)).reservedStock(new BigDecimal(reserved)).build();
    }

    private static Workbook open(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("S8-08: Xuất đúng bộ lọc, đủ 3 cột tồn + dòng tổng, tên tệp TonKho_WH-MB01_20261015.xlsx")
    void export_filteredWarehouse() throws IOException {
        StockLedgerCriteria c = new StockLedgerCriteria(1L, Set.of(1L), null, "IN_STOCK", "coca");
        when(stockLedgerService.criteria(1L, null, "IN_STOCK", "coca", manager)).thenReturn(c);
        when(inventoryRepository.count(any(Specification.class))).thenReturn(2L);
        when(inventoryRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(inv(10, "SP-COCA", "1000", "50.5"), inv(11, "SP-COCA-ZERO", "20", "0")));
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(hn));

        StockLedgerExportService.ExportFile file = service.export(1L, null, "IN_STOCK", "coca", "physicalStock,desc", manager);

        assertThat(file.fileName()).isEqualTo("TonKho_WH-MB01_20261015.xlsx");
        try (Workbook wb = open(file.content())) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).contains("SỔ TỒN KHO");
            assertThat(s.getRow(1).getCell(0).getStringCellValue())
                    .contains("WH-MB01").contains("Còn hàng").contains("coca");
            Row header = s.getRow(StockLedgerExportService.HEADER_ROW);
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("Tồn thực tế");
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("Đang giữ chỗ");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("Tồn khả dụng");
            Row first = s.getRow(StockLedgerExportService.HEADER_ROW + 1);
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo("SP-COCA");
            assertThat(first.getCell(7).getNumericCellValue()).isEqualTo(1000.0);
            assertThat(first.getCell(8).getNumericCellValue()).isEqualTo(50.5);
            assertThat(first.getCell(9).getNumericCellValue()).isEqualTo(949.5);
            Row total = s.getRow(StockLedgerExportService.HEADER_ROW + 3);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("TỔNG CỘNG");
            assertThat(total.getCell(7).getNumericCellValue()).isEqualTo(1020.0);
            assertThat(total.getCell(8).getNumericCellValue()).isEqualTo(50.5);
            assertThat(total.getCell(9).getNumericCellValue()).isEqualTo(969.5);
        }
        // Dùng đúng cách sắp xếp đang xem trên màn hình
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(inventoryRepository).findAll(any(Specification.class), sort.capture());
        assertThat(sort.getValue().getOrderFor("physicalStock").isDescending()).isTrue();
    }

    @Test
    @DisplayName("S8-08: Không chọn kho -> tên tệp TonKho_TatCaKho_<ngày>; không có dòng nào vẫn tạo tệp có tiêu đề")
    void export_allWarehouses_empty() throws IOException {
        when(stockLedgerService.criteria(null, null, null, null, manager))
                .thenReturn(new StockLedgerCriteria(null, null, null, null, null));
        when(inventoryRepository.count(any(Specification.class))).thenReturn(0L);

        StockLedgerExportService.ExportFile file = service.export(null, null, null, null, null, manager);

        assertThat(file.fileName()).isEqualTo("TonKho_TatCaKho_20261015.xlsx");
        try (Workbook wb = open(file.content())) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(StockLedgerExportService.HEADER_ROW).getCell(0).getStringCellValue()).isEqualTo("STT");
            assertThat(s.getRow(StockLedgerExportService.HEADER_ROW + 1).getCell(0).getStringCellValue()).isEqualTo("TỔNG CỘNG");
        }
        verify(inventoryRepository, never()).findAll(any(Specification.class), any(Sort.class));
    }

    @Test
    @DisplayName("S8-08: NV kho xuất kho không được gắn -> 403, không truy vấn tồn")
    void export_otherWarehouse_forbidden() {
        UserDetailsImpl staff = actor(6, "ROLE_WAREHOUSE");
        when(stockLedgerService.criteria(2L, null, null, null, staff))
                .thenReturn(new StockLedgerCriteria(2L, Set.of(1L), null, null, null));

        assertThatThrownBy(() -> service.export(2L, null, null, null, null, staff))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "EXPORT_WAREHOUSE_NOT_ASSIGNED");
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("S8-08: Quá 50.000 dòng -> 400 gợi ý lọc nhỏ lại, không nạp dữ liệu")
    void export_tooLarge_badRequest() {
        when(stockLedgerService.criteria(any(), any(), any(), any(), eq(manager)))
                .thenReturn(new StockLedgerCriteria(null, null, null, null, null));
        when(inventoryRepository.count(any(Specification.class))).thenReturn(50_001L);

        assertThatThrownBy(() -> service.export(null, null, null, null, null, manager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "EXPORT_TOO_LARGE");
        verify(inventoryRepository, never()).findAll(any(Specification.class), any(Sort.class));
    }

    @Test
    @DisplayName("S8-08: Mã kho có ký tự lạ được lọc khỏi tên tệp (không chèn được đường dẫn / header)")
    void fileName_sanitized() {
        Warehouse w = Warehouse.builder().code("WH/../\"MB 01").build();
        assertThat(StockLedgerExportService.fileName(w, NOW)).isEqualTo("TonKho_WHMB01_20261015.xlsx");
    }
}
