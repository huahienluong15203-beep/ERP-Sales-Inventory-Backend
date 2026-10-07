package com.erp.backend.service;

import com.erp.backend.dto.product.ProductImportPreviewResponse;
import com.erp.backend.dto.product.ProductImportRowDto;
import com.erp.backend.dto.product.ProductImportSummaryResponse;
import com.erp.backend.entity.Product;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test ProductExcelImportService - S2-08 Nhập danh mục sản phẩm từ Excel")
class ProductExcelImportServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductExcelImportService productExcelImportService;

    private byte[] createTestExcelBytes(List<List<Object>> dataRows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("DanhMucSanPham");
            Row header = sheet.createRow(0);
            String[] headers = {
                    "STT", "Mã SKU (*)", "Tên sản phẩm (*)", "Đơn vị tính cơ sở (*)",
                    "Nhóm hàng", "Quy cách đóng gói", "Giá vốn (VNĐ)",
                    "Mã vạch (Barcode)", "Trạng thái", "Mô tả"
            };
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }

            for (int r = 0; r < dataRows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> cells = dataRows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    Object val = cells.get(c);
                    if (val instanceof Number) {
                        row.createCell(c).setCellValue(((Number) val).doubleValue());
                    } else if (val != null) {
                        row.createCell(c).setCellValue(val.toString());
                    } else {
                        row.createCell(c).setCellValue("");
                    }
                }
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("S2-08 AC1: Tạo tệp mẫu Excel chuẩn có đủ 2 sheet và hướng dẫn")
    void generateTemplate_Success() throws IOException {
        byte[] bytes = productExcelImportService.generateTemplate();

        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isGreaterThan(0);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetName(0)).isEqualTo("DanhMucSanPham");
            assertThat(wb.getSheetName(1)).isEqualTo("HuongDan_QuyDinh");

            Sheet sheet1 = wb.getSheetAt(0);
            Row header = sheet1.getRow(0);
            assertThat(header.getCell(1).getStringCellValue()).contains("Mã SKU");
            assertThat(header.getCell(2).getStringCellValue()).contains("Tên sản phẩm");
            assertThat(header.getCell(3).getStringCellValue()).contains("Đơn vị tính cơ sở");
        }
    }

    @Test
    @DisplayName("S2-08 AC2: Xem trước và phân loại chính xác CREATE cho SKU mới và UPDATE cho SKU đã có")
    void previewImport_MarksCreateAndUpdateAccurately() throws IOException {
        List<List<Object>> rows = List.of(
                List.of(1, "SP-OLD-01", "Sản phẩm cũ", "Lon", "Nước ngọt", "Thùng 24 lon", 200000, "1111", "ACTIVE", "Cũ"),
                List.of(2, "SP-NEW-02", "Sản phẩm mới", "Chai", "Bia", "Lốc 6 chai", 150000, "2222", "ACTIVE", "Mới")
        );

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "san_pham.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // Giả lập SKU 'SP-OLD-01' đã tồn tại trong DB
        when(productRepository.findExistingSkus(anyCollection())).thenReturn(Set.of("SP-OLD-01"));

        ProductImportPreviewResponse response = productExcelImportService.previewImport(file);

        assertThat(response).isNotNull();
        assertThat(response.getTotalRows()).isEqualTo(2);
        assertThat(response.getValidRows()).isEqualTo(2);
        assertThat(response.getInvalidRows()).isEqualTo(0);
        assertThat(response.getUpdateCount()).isEqualTo(1);
        assertThat(response.getCreateCount()).isEqualTo(1);

        ProductImportRowDto oldRow = response.getRows().get(0);
        assertThat(oldRow.getSku()).isEqualTo("SP-OLD-01");
        assertThat(oldRow.getAction()).isEqualTo("UPDATE");
        assertThat(oldRow.isUpdate()).isTrue();
        assertThat(oldRow.isValid()).isTrue();

        ProductImportRowDto newRow = response.getRows().get(1);
        assertThat(newRow.getSku()).isEqualTo("SP-NEW-02");
        assertThat(newRow.getAction()).isEqualTo("CREATE");
        assertThat(newRow.isUpdate()).isFalse();
        assertThat(newRow.isValid()).isTrue();
    }

    @Test
    @DisplayName("S2-08 AC1: Xem trước báo lỗi chi tiết theo từng dòng khi dữ liệu sai quy chuẩn")
    void previewImport_ReportsErrorsPerRow() throws IOException {
        List<List<Object>> rows = List.of(
                // Dòng 1: Thiếu SKU
                List.of(1, "", "Sản phẩm A", "Lon", "Đồ uống", "", 100000, "", "ACTIVE", ""),
                // Dòng 2: Thiếu Tên
                List.of(2, "SP-002", "", "Chai", "Đồ uống", "", 100000, "", "ACTIVE", ""),
                // Dòng 3: Thiếu Đơn vị tính cơ sở
                List.of(3, "SP-003", "Sản phẩm C", "", "Đồ uống", "", 100000, "", "ACTIVE", ""),
                // Dòng 4: Giá vốn âm
                List.of(4, "SP-004", "Sản phẩm D", "Lon", "Đồ uống", "", -50000, "", "ACTIVE", ""),
                // Dòng 5: Trạng thái không hợp lệ
                List.of(5, "SP-005", "Sản phẩm E", "Lon", "Đồ uống", "", 50000, "", "PENDING", "")
        );

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "san_pham.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(productRepository.findExistingSkus(anyCollection())).thenReturn(Set.of());

        ProductImportPreviewResponse response = productExcelImportService.previewImport(file);

        assertThat(response.getTotalRows()).isEqualTo(5);
        assertThat(response.getValidRows()).isEqualTo(0);
        assertThat(response.getInvalidRows()).isEqualTo(5);

        assertThat(response.getRows().get(0).getErrors()).anyMatch(e -> e.contains("Mã SKU không được để trống"));
        assertThat(response.getRows().get(1).getErrors()).anyMatch(e -> e.contains("Tên sản phẩm không được để trống"));
        assertThat(response.getRows().get(2).getErrors()).anyMatch(e -> e.contains("Đơn vị tính cơ sở không được để trống"));
        assertThat(response.getRows().get(3).getErrors()).anyMatch(e -> e.contains("Giá vốn không được là số âm"));
        assertThat(response.getRows().get(4).getErrors()).anyMatch(e -> e.contains("Trạng thái"));
    }

    @Test
    @DisplayName("S2-08 AC2: Thực thi nhập dữ liệu thành công - cập nhật sản phẩm cũ và thêm mới sản phẩm mới")
    void executeImport_UpsertSuccess() throws IOException {
        Product existingProduct = Product.builder()
                .id(1L)
                .sku("SP-COCA")
                .name("Coca cũ")
                .baseUnit("Lon")
                .costPrice(BigDecimal.valueOf(180000))
                .build();

        List<List<Object>> rows = List.of(
                List.of(1, "SP-COCA", "Coca-Cola Mới Cập Nhật", "Lon", "Nước ngọt", "Thùng 24", 210000, "893", "ACTIVE", "Cập nhật giá"),
                List.of(2, "SP-PEPSI", "Pepsi Mới Toanh", "Chai", "Nước ngọt", "Lốc 6", 150000, "894", "ACTIVE", "Mới")
        );

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(productRepository.findExistingSkus(anyCollection())).thenReturn(Set.of("SP-COCA"));
        when(productRepository.findBySkuIn(anyCollection())).thenReturn(List.of(existingProduct));

        ProductImportSummaryResponse summary = productExcelImportService.executeImport(file);

        assertThat(summary).isNotNull();
        assertThat(summary.getTotalRows()).isEqualTo(2);
        assertThat(summary.getSuccessCount()).isEqualTo(2);
        assertThat(summary.getUpdatedCount()).isEqualTo(1);
        assertThat(summary.getCreatedCount()).isEqualTo(1);
        assertThat(summary.getErrorCount()).isEqualTo(0);

        // Kiểm tra đối tượng cũ đã được cập nhật giá trị mới
        assertThat(existingProduct.getName()).isEqualTo("Coca-Cola Mới Cập Nhật");
        assertThat(existingProduct.getCostPrice()).isEqualByComparingTo("210000");

        verify(productRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    @DisplayName("S2-08: Thực thi bỏ qua dòng lỗi và nhập các dòng hợp lệ còn lại")
    void executeImport_SkipsInvalidRows() throws IOException {
        List<List<Object>> rows = List.of(
                List.of(1, "SP-VALID", "Sản phẩm hợp lệ", "Lon", "Nhóm 1", "", 100000, "", "ACTIVE", ""),
                List.of(2, "", "Sản phẩm lỗi thiếu SKU", "Lon", "Nhóm 1", "", 100000, "", "ACTIVE", "")
        );

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(productRepository.findExistingSkus(anyCollection())).thenReturn(Set.of());
        when(productRepository.findBySkuIn(anyCollection())).thenReturn(List.of());

        ProductImportSummaryResponse summary = productExcelImportService.executeImport(file);

        assertThat(summary.getTotalRows()).isEqualTo(2);
        assertThat(summary.getSuccessCount()).isEqualTo(1);
        assertThat(summary.getCreatedCount()).isEqualTo(1);
        assertThat(summary.getUpdatedCount()).isEqualTo(0);
        assertThat(summary.getErrorCount()).isEqualTo(1);
        assertThat(summary.getErrorRows()).hasSize(1);
        assertThat(summary.getErrorRows().get(0).getRowNumber()).isEqualTo(3); // dòng số 3 trên Excel
    }

    @Test
    @DisplayName("S2-08: Báo lỗi khi tệp rỗng hoặc sai định dạng không phải .xlsx")
    void validateFile_ThrowsWhenInvalid() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.xlsx", "text/plain", new byte[0]);
        assertThatThrownBy(() -> productExcelImportService.previewImport(emptyFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Vui lòng chọn tệp Excel");

        MockMultipartFile wrongExtFile = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> productExcelImportService.previewImport(wrongExtFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chỉ hỗ trợ tệp định dạng Excel");
    }

    @Test
    @DisplayName("S2-08: Thử nghiệm tải và nhập dữ liệu lớn 5.000 SKU sản phẩm mượt mà theo Batch")
    void executeImport_5000Products_HandlesGracefully() throws IOException {
        List<List<Object>> rows = new ArrayList<>(5000);
        for (int i = 1; i <= 5000; i++) {
            rows.add(List.of(
                    i,
                    "SKU-" + String.format("%05d", i),
                    "Sản phẩm thử nghiệm số " + i,
                    "Hộp",
                    "Đồ tiêu dùng",
                    "Thùng 12 hộp",
                    100000 + i * 10,
                    "8930000" + String.format("%05d", i),
                    "ACTIVE",
                    "Ghi chú " + i
            ));
        }

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "5000_products.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // 1.000 SKU đầu tiên đã tồn tại trong DB, 4.000 SKU còn lại là mới
        Set<String> existingSkus = new HashSet<>();
        List<Product> existingProducts = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            String sku = "SKU-" + String.format("%05d", i);
            existingSkus.add(sku);
            existingProducts.add(Product.builder().sku(sku).name("Old " + i).baseUnit("Hộp").build());
        }

        when(productRepository.findExistingSkus(anyCollection())).thenReturn(existingSkus);
        when(productRepository.findBySkuIn(anyCollection())).thenReturn(existingProducts);

        ProductImportSummaryResponse summary = productExcelImportService.executeImport(file);

        assertThat(summary).isNotNull();
        assertThat(summary.getTotalRows()).isEqualTo(5000);
        assertThat(summary.getSuccessCount()).isEqualTo(5000);
        assertThat(summary.getUpdatedCount()).isEqualTo(1000);
        assertThat(summary.getCreatedCount()).isEqualTo(4000);
        assertThat(summary.getErrorCount()).isEqualTo(0);

        // 5000 sản phẩm lưu theo batch 500 -> gọi saveAll chính xác 10 lần
        verify(productRepository, times(10)).saveAll(anyList());
    }

    @Test
    @DisplayName("S208-01: Dòng có Mô tả > 1000 ký tự được ghi nhận vào errorRows, các dòng hợp lệ vẫn nhập thành công")
    void executeImport_S208_01_DescriptionExceeds1000Chars_RejectedAsErrorRow() throws IOException {
        String longDescription = "A".repeat(1001); // 1001 ký tự
        List<List<Object>> rows = List.of(
                List.of(1, "SP-VALID-01", "Sản phẩm hợp lệ", "Lon", "Đồ uống", "Thùng 24", 200000, "111", "ACTIVE", "Mô tả ngắn"),
                List.of(2, "SP-ERR-DESC", "Sản phẩm lỗi mô tả dài", "Chai", "Đồ uống", "Lốc 6", 150000, "222", "ACTIVE", longDescription)
        );

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "test_desc.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        when(productRepository.findExistingSkus(anyCollection())).thenReturn(Set.of());
        when(productRepository.findBySkuIn(anyCollection())).thenReturn(List.of());

        ProductImportSummaryResponse summary = productExcelImportService.executeImport(file);

        assertThat(summary).isNotNull();
        assertThat(summary.getTotalRows()).isEqualTo(2);
        assertThat(summary.getSuccessCount()).isEqualTo(1);
        assertThat(summary.getCreatedCount()).isEqualTo(1);
        assertThat(summary.getErrorCount()).isEqualTo(1);
        assertThat(summary.getErrorRows()).hasSize(1);
        assertThat(summary.getErrorRows().get(0).getSku()).isEqualTo("SP-ERR-DESC");
        assertThat(summary.getErrorRows().get(0).getErrors())
                .anyMatch(err -> err.contains("Mô tả / Ghi chú không được vượt quá 1000 ký tự"));

        // Chỉ có 1 sản phẩm hợp lệ được lưu
        verify(productRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("S208-02: SKU không phân biệt hoa thường - DB có SP-COCA-330, file có sp-coca-330 nhận diện là UPDATE")
    void previewAndExecute_S208_02_CaseInsensitiveSkuMatching() throws IOException {
        List<List<Object>> rows = List.of(
                List.of(1, "sp-coca-330", "Coca-Cola lon 330ml", "Lon", "Nước ngọt", "Thùng 24", 215000, "893", "ACTIVE", "Cập nhật chữ thường")
        );

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "test_sku_case.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // Giả lập DB trả về SKU dạng UPPERCASE từ query findExistingSkus
        when(productRepository.findExistingSkus(anyCollection())).thenReturn(Set.of("SP-COCA-330"));

        ProductImportPreviewResponse preview = productExcelImportService.previewImport(file);

        assertThat(preview.getTotalRows()).isEqualTo(1);
        assertThat(preview.getCreateCount()).isEqualTo(0);
        assertThat(preview.getUpdateCount()).isEqualTo(1);
        assertThat(preview.getRows().get(0).getAction()).isEqualTo("UPDATE");
        assertThat(preview.getRows().get(0).isUpdate()).isTrue();

        // Kiểm tra tiếp khi Execute
        Product existingProduct = Product.builder()
                .id(10L)
                .sku("SP-COCA-330")
                .name("Coca cũ")
                .baseUnit("Lon")
                .costPrice(BigDecimal.valueOf(180000))
                .status("ACTIVE")
                .build();

        when(productRepository.findBySkuIn(anyCollection())).thenReturn(List.of(existingProduct));

        ProductImportSummaryResponse summary = productExcelImportService.executeImport(file);

        assertThat(summary.getSuccessCount()).isEqualTo(1);
        assertThat(summary.getUpdatedCount()).isEqualTo(1);
        assertThat(summary.getCreatedCount()).isEqualTo(0);
        assertThat(existingProduct.getName()).isEqualTo("Coca-Cola lon 330ml");
        assertThat(existingProduct.getCostPrice()).isEqualByComparingTo("215000");
    }

    @Test
    @DisplayName("S208-03: Cập nhật sản phẩm để trống Giá vốn và Trạng thái phải giữ nguyên giá trị cũ")
    void executeImport_S208_03_EmptyPriceAndStatusPreservesExistingData() throws IOException {
        // Excel để trống Giá vốn và Trạng thái cho SP-COCA-330
        List<List<Object>> rows = List.of(
                List.of(1, "SP-COCA-330", "Coca Tên Mới", "Lon", "Nước ngọt", "Thùng 24", "", "", "", "Cập nhật tên")
        );

        byte[] excelBytes = createTestExcelBytes(rows);
        MockMultipartFile file = new MockMultipartFile("file", "test_keep_data.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        Product existingProduct = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Coca Tên Cũ")
                .baseUnit("Lon")
                .costPrice(BigDecimal.valueOf(210000))
                .status("INACTIVE")
                .build();

        when(productRepository.findExistingSkus(anyCollection())).thenReturn(Set.of("SP-COCA-330"));
        when(productRepository.findBySkuIn(anyCollection())).thenReturn(List.of(existingProduct));

        ProductImportSummaryResponse summary = productExcelImportService.executeImport(file);

        assertThat(summary.getSuccessCount()).isEqualTo(1);
        assertThat(summary.getUpdatedCount()).isEqualTo(1);
        // Tên được cập nhật
        assertThat(existingProduct.getName()).isEqualTo("Coca Tên Mới");
        // Giá vốn và Trạng thái giữ nguyên giá trị cũ, KHÔNG bị ghi đè thành 0 và ACTIVE
        assertThat(existingProduct.getCostPrice()).isEqualByComparingTo("210000");
        assertThat(existingProduct.getStatus()).isEqualTo("INACTIVE");
    }
}
