package com.erp.backend.service;

import com.erp.backend.dto.product.ProductImportPreviewResponse;
import com.erp.backend.dto.product.ProductImportRowDto;
import com.erp.backend.dto.product.ProductImportSummaryResponse;
import com.erp.backend.entity.Product;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service xử lý Nhập danh mục sản phẩm hàng loạt từ Excel (S2-08).
 * Đáp ứng yêu cầu:
 * - Tải tệp mẫu Excel (.xlsx) chuẩn hóa, có sẵn sheet hướng dẫn và quy chuẩn.
 * - Xem trước và báo lỗi theo từng dòng trước khi nhập.
 * - SKU đã tồn tại thì CẬP NHẬT thay vì tạo mới, có đánh dấu rõ ràng trong bản xem trước (AC2).
 * - Tối ưu hóa hiệu năng, xử lý mượt mà danh mục lớn lên tới 5.000 SKU.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductExcelImportService {

    private final ProductRepository productRepository;

    private static final Pattern SKU_PATTERN = Pattern.compile("^[a-zA-Z0-9._\\-\\s/]{2,50}$");
    private static final int BATCH_SIZE = 500;

    /**
     * S2-08 AC1: Tạo tệp Excel mẫu chứa cấu trúc cột chuẩn và hướng dẫn nhập liệu.
     */
    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Định dạng tiêu đề cột
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            CellStyle textStyle = workbook.createCellStyle();
            DataFormat dataFormat = workbook.createDataFormat();
            textStyle.setDataFormat(dataFormat.getFormat("@"));

            CellStyle numberStyle = workbook.createCellStyle();
            numberStyle.setDataFormat(dataFormat.getFormat("#,##0"));

            // 1. Sheet "DanhMucSanPham"
            Sheet sheet1 = workbook.createSheet("DanhMucSanPham");
            String[] headers = {
                    "STT",
                    "Mã SKU (*)",
                    "Tên sản phẩm (*)",
                    "Đơn vị tính cơ sở (*)",
                    "Nhóm hàng",
                    "Quy cách đóng gói",
                    "Giá vốn (VNĐ)",
                    "Mã vạch (Barcode)",
                    "Trạng thái (ACTIVE / INACTIVE)",
                    "Mô tả / Ghi chú"
            };

            Row headerRow = sheet1.createRow(0);
            headerRow.setHeightInPoints(24);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Dữ liệu mẫu minh họa
            Object[][] sampleData = {
                    {1, "SP-COCA-330", "Nước ngọt Coca-Cola lon 330ml", "Lon", "Nước giải khát có gas", "Thùng 24 lon", 210000, "8934567890123", "ACTIVE", "Nước giải khát Coca-Cola chính hãng"},
                    {2, "SP-PEPSI-330", "Nước ngọt Pepsi lon 330ml", "Lon", "Nước giải khát có gas", "Thùng 24 lon", 205000, "8934567890124", "ACTIVE", "Nước ngọt vị Cola truyền thống"},
                    {3, "SP-HEINEKEN-CAN", "Bia Heineken lon 330ml", "Lon", "Bia & Đồ uống có cồn", "Thùng 24 lon", 410000, "8934567890125", "ACTIVE", "Bia cao cấp Hà Lan"},
                    {4, "SP-AQUAFINA-500", "Nước tinh khiết Aquafina 500ml", "Chai", "Nước tinh khiết", "Lốc 6 chai", 30000, "8934567890126", "ACTIVE", "Nước uống đóng chai tiệt trùng"},
                    {5, "SP-RED-BULL-250", "Nước tăng lực Red Bull lon 250ml", "Lon", "Nước tăng lực", "Khay 24 lon", 250000, "8934567890127", "ACTIVE", "Nước tăng lực bò húc Thái Lan"}
            };

            for (int r = 0; r < sampleData.length; r++) {
                Row row = sheet1.createRow(r + 1);
                for (int c = 0; c < sampleData[r].length; c++) {
                    Cell cell = row.createCell(c);
                    Object val = sampleData[r][c];
                    if (val instanceof Number) {
                        cell.setCellStyle(numberStyle);
                        cell.setCellValue(((Number) val).doubleValue());
                    } else {
                        cell.setCellStyle(textStyle);
                        cell.setCellValue(val != null ? val.toString() : "");
                    }
                }
            }

            // Đặt độ rộng cột vừa vặn
            int[] colWidths = {8, 22, 38, 20, 25, 22, 18, 22, 28, 35};
            for (int i = 0; i < colWidths.length; i++) {
                sheet1.setColumnWidth(i, colWidths[i] * 256);
            }

            // 2. Sheet "HuongDan_QuyDinh"
            Sheet sheet2 = workbook.createSheet("HuongDan_QuyDinh");
            Row guideHeader = sheet2.createRow(0);
            Cell gCell = guideHeader.createCell(0);
            gCell.setCellValue("HƯỚNG DẪN QUY CHUẨN NHẬP DANH MỤC SẢN PHẨM TỪ TỆP EXCEL (S2-08)");
            gCell.setCellStyle(headerStyle);

            List<String> guidelines = List.of(
                    "1. Các cột có dấu (*) là thông tin bắt buộc phải có dữ liệu.",
                    "2. Mã SKU (*): Mã nhận diện duy nhất của sản phẩm trong hệ thống (2 - 50 ký tự).",
                    "3. QUY TẮC CẬP NHẬT SKU (S2-08 AC2):",
                    "   - Nếu Mã SKU CHƯA TỒN TẠI trong hệ thống -> Hệ thống sẽ TẠO MỚI (đánh dấu: CREATE).",
                    "   - Nếu Mã SKU ĐÃ TỒN TẠI trong hệ thống -> Hệ thống sẽ CẬP NHẬT thông tin mới từ tệp Excel (đánh dấu: UPDATE).",
                    "4. Tên sản phẩm (*): Tên hiển thị đầy đủ của mặt hàng (tối đa 200 ký tự).",
                    "5. Đơn vị tính cơ sở (*): Đơn vị nhỏ nhất phục vụ theo dõi tồn kho và xuất nhập hàng (vd: Lon, Chai, Hộp, Gói, Cái, Kg...).",
                    "6. Quy cách đóng gói: Diễn giải cách đóng thùng/lốc phục vụ quy đổi đơn vị (vd: Thùng 24 lon, Thùng 12 hộp).",
                    "7. Giá vốn (VNĐ): Là số tiền >= 0. Nếu để trống, hệ thống mặc định giá vốn là 0 VNĐ.",
                    "8. Mã vạch: Mã vạch sản phẩm (EAN-13, Barcode...) dùng khi quét máy quét mã vạch (tùy chọn).",
                    "9. Trạng thái: ACTIVE (Đang kinh doanh) hoặc INACTIVE (Ngừng kinh doanh). Mặc định là ACTIVE nếu để trống.",
                    "10. Hệ thống hỗ trợ xử lý mượt mà lên tới 5.000 mã hàng mỗi lần nhập.",
                    "11. Các dòng có dữ liệu lỗi sẽ được báo cáo chi tiết và bỏ qua, những dòng hợp lệ vẫn sẽ được nhập/cập nhật thành công."
            );

            for (int i = 0; i < guidelines.size(); i++) {
                Row row = sheet2.createRow(i + 2);
                row.createCell(0).setCellValue(guidelines.get(i));
            }
            sheet2.setColumnWidth(0, 85 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Lỗi khi tạo tệp mẫu Excel sản phẩm", e);
            throw new RuntimeException("Không thể tạo tệp mẫu Excel sản phẩm: " + e.getMessage(), e);
        }
    }

    /**
     * S2-08 AC1 & AC2: Xem trước dữ liệu, kiểm tra hợp lệ từng dòng,
     * đánh dấu rõ ràng dòng nào là CREATE và dòng nào là UPDATE.
     */
    @Transactional(readOnly = true)
    public ProductImportPreviewResponse previewImport(MultipartFile file) {
        validateFile(file);

        List<ProductImportRowDto> parsedRows = parseAndValidateRows(file);

        int totalRows = parsedRows.size();
        int validRows = (int) parsedRows.stream().filter(ProductImportRowDto::isValid).count();
        int invalidRows = totalRows - validRows;

        int createCount = (int) parsedRows.stream()
                .filter(r -> r.isValid() && "CREATE".equalsIgnoreCase(r.getAction()))
                .count();

        int updateCount = (int) parsedRows.stream()
                .filter(r -> r.isValid() && "UPDATE".equalsIgnoreCase(r.getAction()))
                .count();

        return ProductImportPreviewResponse.builder()
                .totalRows(totalRows)
                .validRows(validRows)
                .invalidRows(invalidRows)
                .createCount(createCount)
                .updateCount(updateCount)
                .rows(parsedRows)
                .build();
    }

    /**
     * S2-08: Thực thi nhập dữ liệu hàng loạt từ Excel.
     * Cập nhật nếu SKU đã tồn tại, tạo mới nếu SKU chưa có. Bỏ qua dòng lỗi và tổng kết.
     */
    @Transactional
    public ProductImportSummaryResponse executeImport(MultipartFile file) {
        validateFile(file);

        List<ProductImportRowDto> parsedRows = parseAndValidateRows(file);

        List<ProductImportRowDto> validRows = parsedRows.stream()
                .filter(ProductImportRowDto::isValid)
                .toList();

        List<ProductImportRowDto> errorRows = parsedRows.stream()
                .filter(r -> !r.isValid())
                .toList();

        if (validRows.isEmpty()) {
            return ProductImportSummaryResponse.builder()
                    .totalRows(parsedRows.size())
                    .successCount(0)
                    .createdCount(0)
                    .updatedCount(0)
                    .errorCount(errorRows.size())
                    .importedAt(LocalDateTime.now())
                    .message("Không có dòng dữ liệu hợp lệ nào để nhập.")
                    .errorRows(errorRows)
                    .build();
        }

        // Lấy tất cả SKU hợp lệ để load sẵn từ DB trong 1 query (tránh N+1)
        Set<String> skusToProcess = validRows.stream()
                .map(r -> r.getSku().trim().toUpperCase())
                .collect(Collectors.toSet());

        Map<String, Product> existingProductMap = productRepository.findBySkuIn(skusToProcess).stream()
                .collect(Collectors.toMap(p -> p.getSku().toUpperCase(), p -> p, (p1, p2) -> p1));

        int createdCount = 0;
        int updatedCount = 0;
        List<Product> productsToSave = new ArrayList<>();

        for (ProductImportRowDto row : validRows) {
            String skuKey = row.getSku().trim().toUpperCase();
            Product product = existingProductMap.get(skuKey);

            if (product != null) {
                // S2-08 AC2: SKU đã tồn tại -> CẬP NHẬT
                product.setName(row.getName().trim());
                product.setBaseUnit(row.getBaseUnit().trim());
                if (StringUtils.hasText(row.getCategory())) {
                    product.setCategory(row.getCategory().trim());
                }
                if (StringUtils.hasText(row.getPackaging())) {
                    product.setPackaging(row.getPackaging().trim());
                }
                // S208-03: Chỉ cập nhật khi ô Giá vốn có dữ liệu trên Excel, để trống thì giữ nguyên giá cũ
                if (row.getCostPrice() != null) {
                    product.setCostPrice(row.getCostPrice());
                }
                if (StringUtils.hasText(row.getBarcode())) {
                    product.setBarcode(row.getBarcode().trim());
                }
                // S208-03: Chỉ cập nhật khi ô Trạng thái có dữ liệu trên Excel, để trống thì giữ nguyên trạng thái cũ
                if (StringUtils.hasText(row.getStatus())) {
                    product.setStatus(row.getStatus().trim().toUpperCase());
                }
                if (StringUtils.hasText(row.getDescription())) {
                    product.setDescription(row.getDescription().trim());
                }
                productsToSave.add(product);
                updatedCount++;
            } else {
                // S2-08 AC2: SKU chưa có -> TẠO MỚI (chuẩn hóa SKU chữ hoa)
                Product newProd = Product.builder()
                        .sku(row.getSku().trim().toUpperCase())
                        .name(row.getName().trim())
                        .baseUnit(row.getBaseUnit().trim())
                        .category(StringUtils.hasText(row.getCategory()) ? row.getCategory().trim() : null)
                        .packaging(StringUtils.hasText(row.getPackaging()) ? row.getPackaging().trim() : null)
                        .costPrice(row.getCostPrice() != null ? row.getCostPrice() : BigDecimal.ZERO)
                        .barcode(StringUtils.hasText(row.getBarcode()) ? row.getBarcode().trim() : null)
                        .status(StringUtils.hasText(row.getStatus()) ? row.getStatus().trim().toUpperCase() : "ACTIVE")
                        .description(StringUtils.hasText(row.getDescription()) ? row.getDescription().trim() : null)
                        .build();

                productsToSave.add(newProd);
                existingProductMap.put(skuKey, newProd); // Cập nhật map để nếu lặp lại trong file thì không bị insert 2 lần
                createdCount++;
            }
        }

        // Lưu trữ theo batch tối ưu hiệu năng với danh mục lớn 5.000 SKU
        for (int i = 0; i < productsToSave.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, productsToSave.size());
            productRepository.saveAll(productsToSave.subList(i, end));
        }

        int successCount = createdCount + updatedCount;
        String message = String.format("Nhập dữ liệu thành công: %d sản phẩm (Tạo mới: %d, Cập nhật: %d). Bỏ qua %d dòng lỗi.",
                successCount, createdCount, updatedCount, errorRows.size());

        log.info("S2-08 Hoàn tất import danh mục sản phẩm: total={}, success={}, created={}, updated={}, errors={}",
                parsedRows.size(), successCount, createdCount, updatedCount, errorRows.size());

        return ProductImportSummaryResponse.builder()
                .totalRows(parsedRows.size())
                .successCount(successCount)
                .createdCount(createdCount)
                .updatedCount(updatedCount)
                .errorCount(errorRows.size())
                .importedAt(LocalDateTime.now())
                .message(message)
                .errorRows(errorRows)
                .build();
    }

    /**
     * Đọc tệp Excel, trích xuất dữ liệu và kiểm tra lỗi từng dòng.
     */
    private List<ProductImportRowDto> parseAndValidateRows(MultipartFile file) {
        List<ProductImportRowDto> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw BusinessException.badRequest("EMPTY_WORKBOOK", "Tệp Excel không chứa bất kỳ trang tính (sheet) nào.");
            }

            Sheet sheet = workbook.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            if (lastRowNum < 1) {
                throw BusinessException.badRequest("NO_DATA_ROW", "Tệp Excel không có dòng dữ liệu nào sau dòng tiêu đề.");
            }

            // Thu thập trước tất cả SKU trong file để kiểm tra trùng trong DB qua 1 lần query (chuẩn hóa UPPERCASE để không phân biệt hoa thường)
            List<String> skusInFile = new ArrayList<>();
            for (int r = 1; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row, formatter)) continue;
                String sku = clean(formatter.formatCellValue(row.getCell(1)));
                if (StringUtils.hasText(sku)) {
                    skusInFile.add(sku.trim().toUpperCase());
                }
            }

            // Truy vấn 1 lần duy nhất danh sách các SKU đã tồn tại trong DB không phân biệt hoa thường (Bulk query)
            Set<String> existingSkusInDb = skusInFile.isEmpty() ? Set.of() :
                    productRepository.findExistingSkus(skusInFile).stream()
                            .map(String::toUpperCase)
                            .collect(Collectors.toSet());

            Set<String> seenSkusInFile = new HashSet<>();

            for (int r = 1; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row, formatter)) {
                    continue;
                }

                int rowNumber = r + 1; // Số dòng thực tế trên Excel (bắt đầu từ 1, dòng 1 là header)
                List<String> errors = new ArrayList<>();

                String sku = clean(formatter.formatCellValue(row.getCell(1)));
                String name = clean(formatter.formatCellValue(row.getCell(2)));
                String baseUnit = clean(formatter.formatCellValue(row.getCell(3)));
                String category = clean(formatter.formatCellValue(row.getCell(4)));
                String packaging = clean(formatter.formatCellValue(row.getCell(5)));
                String rawCostPrice = clean(formatter.formatCellValue(row.getCell(6)));
                String barcode = clean(formatter.formatCellValue(row.getCell(7)));
                String rawStatus = clean(formatter.formatCellValue(row.getCell(8)));
                String description = clean(formatter.formatCellValue(row.getCell(9)));

                // 1. Kiểm tra Mã SKU (*)
                if (!StringUtils.hasText(sku)) {
                    errors.add("Mã SKU không được để trống.");
                } else if (sku.length() < 2 || sku.length() > 50) {
                    errors.add("Mã SKU phải có độ dài từ 2 đến 50 ký tự.");
                } else if (!SKU_PATTERN.matcher(sku).matches()) {
                    errors.add("Mã SKU chứa ký tự không hợp lệ. Chỉ chấp nhận chữ cái, số, gạch ngang, gạch dưới, dấu chấm.");
                } else {
                    String skuUpper = sku.trim().toUpperCase();
                    if (!seenSkusInFile.add(skuUpper)) {
                        errors.add("Mã SKU '" + sku + "' bị trùng lặp nhiều lần trong chính tệp Excel này.");
                    }
                }

                // 2. Kiểm tra Tên sản phẩm (*)
                if (!StringUtils.hasText(name)) {
                    errors.add("Tên sản phẩm không được để trống.");
                } else if (name.length() > 200) {
                    errors.add("Tên sản phẩm không được vượt quá 200 ký tự.");
                }

                // 3. Kiểm tra Đơn vị tính cơ sở (*)
                if (!StringUtils.hasText(baseUnit)) {
                    errors.add("Đơn vị tính cơ sở không được để trống.");
                } else if (baseUnit.length() > 30) {
                    errors.add("Đơn vị tính cơ sở không được vượt quá 30 ký tự.");
                }

                // S208-01: Kiểm tra độ dài các trường văn bản theo CSDL
                if (StringUtils.hasText(category) && category.length() > 100) {
                    errors.add("Nhóm hàng không được vượt quá 100 ký tự.");
                }
                if (StringUtils.hasText(packaging) && packaging.length() > 100) {
                    errors.add("Quy cách đóng gói không được vượt quá 100 ký tự.");
                }
                if (StringUtils.hasText(barcode) && barcode.length() > 50) {
                    errors.add("Mã vạch không được vượt quá 50 ký tự.");
                }
                if (StringUtils.hasText(description) && description.length() > 1000) {
                    errors.add("Mô tả / Ghi chú không được vượt quá 1000 ký tự (độ dài hiện tại: " + description.length() + ").");
                }

                // 4. S208-03: Kiểm tra Giá vốn (VNĐ) - null nếu để trống trong file Excel
                BigDecimal costPrice = null;
                if (StringUtils.hasText(rawCostPrice)) {
                    try {
                        String cleanedPrice = rawCostPrice.replaceAll("[,\\s]", "");
                        BigDecimal parsedPrice = new BigDecimal(cleanedPrice);
                        if (parsedPrice.compareTo(BigDecimal.ZERO) < 0) {
                            errors.add("Giá vốn không được là số âm (giá trị hiện tại: " + rawCostPrice + ").");
                        } else {
                            costPrice = parsedPrice;
                        }
                    } catch (NumberFormatException e) {
                        errors.add("Giá vốn '" + rawCostPrice + "' không đúng định dạng số hợp lệ.");
                    }
                }

                // 5. S208-03: Kiểm tra Trạng thái - null nếu để trống trong file Excel
                String status = null;
                if (StringUtils.hasText(rawStatus)) {
                    String st = rawStatus.toUpperCase();
                    if (!"ACTIVE".equals(st) && !"INACTIVE".equals(st)) {
                        errors.add("Trạng thái '" + rawStatus + "' không hợp lệ (chỉ chấp nhận ACTIVE hoặc INACTIVE).");
                    } else {
                        status = st;
                    }
                }

                // 6. S2-08 AC2 & S208-02: Xác định hành động (CREATE vs UPDATE) không phân biệt hoa thường
                boolean isUpdate = false;
                String action = "CREATE";
                if (StringUtils.hasText(sku)) {
                    if (existingSkusInDb.contains(sku.trim().toUpperCase())) {
                        isUpdate = true;
                        action = "UPDATE";
                    }
                }

                boolean valid = errors.isEmpty();

                result.add(ProductImportRowDto.builder()
                        .rowNumber(rowNumber)
                        .sku(sku)
                        .name(name)
                        .category(category)
                        .baseUnit(baseUnit)
                        .packaging(packaging)
                        .costPrice(costPrice)
                        .barcode(barcode)
                        .status(status)
                        .description(description)
                        .action(action)
                        .isUpdate(isUpdate)
                        .valid(valid)
                        .errors(errors)
                        .build());
            }

        } catch (Exception e) {
            log.error("Lỗi khi đọc file Excel sản phẩm", e);
            throw BusinessException.badRequest("INVALID_EXCEL_FORMAT", "File không đúng định dạng Excel (.xlsx) hoặc bị hư hỏng: " + e.getMessage());
        }

        return result;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("FILE_EMPTY", "Vui lòng chọn tệp Excel để tải lên.");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls"))) {
            throw BusinessException.badRequest("INVALID_FILE_FORMAT", "Hệ thống chỉ hỗ trợ tệp định dạng Excel (.xlsx).");
        }
    }

    private boolean isRowEmpty(Row row, DataFormatter formatter) {
        for (int c = 1; c <= 9; c++) {
            Cell cell = row.getCell(c);
            if (cell != null && StringUtils.hasText(formatter.formatCellValue(cell))) {
                return false;
            }
        }
        return true;
    }

    private String clean(String text) {
        return text != null ? text.trim() : "";
    }
}
