package com.erp.backend.service;

import com.erp.backend.dto.product.ProductImportPreviewResponse;
import com.erp.backend.dto.product.ProductImportRowDto;
import com.erp.backend.dto.product.ProductImportSummaryResponse;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductCategory;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductCategoryRepository;
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
import java.text.Normalizer;
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
 * - Bổ sung cây phân cấp ngành hàng / nhóm hàng / phân nhóm và tự động gán vào cây (Phần 3).
 * - Ghi đè (đổi cấp cây phân cấp) khi sản phẩm trùng thông tin thay vì báo lỗi dòng (Phần 4).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductExcelImportService {

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;

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
                    "Ngành hàng (Cấp 1)",
                    "Nhóm hàng (Cấp 2)",
                    "Phân nhóm (Cấp 3)",
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

            // Dữ liệu mẫu minh họa có đầy đủ 3 cấp cây phân cấp nhóm hàng
            Object[][] sampleData = {
                    {1, "SP-COCA-330", "Nước ngọt Coca-Cola lon 330ml", "Lon", "Đồ uống", "Nước giải khát", "Có ga", "Thùng 24 lon", 210000, "8934567890123", "ACTIVE", "Nước giải khát Coca-Cola chính hãng"},
                    {2, "SP-PEPSI-330", "Nước ngọt Pepsi lon 330ml", "Lon", "Đồ uống", "Nước giải khát", "Có ga", "Thùng 24 lon", 205000, "8934567890124", "ACTIVE", "Nước ngọt vị Cola truyền thống"},
                    {3, "SP-HEINEKEN-CAN", "Bia Heineken lon 330ml", "Lon", "Đồ uống", "Bia & Đồ uống có cồn", "Bia lon", "Thùng 24 lon", 410000, "8934567890125", "ACTIVE", "Bia cao cấp Hà Lan"},
                    {4, "SP-AQUAFINA-500", "Nước tinh khiết Aquafina 500ml", "Chai", "Đồ uống", "Nước tinh khiết", "Nước suối", "Lốc 6 chai", 30000, "8934567890126", "ACTIVE", "Nước uống đóng chai tiệt trùng"},
                    {5, "SP-RED-BULL-250", "Nước tăng lực Red Bull lon 250ml", "Lon", "Đồ uống", "Nước tăng lực", "Tăng lực lon", "Khay 24 lon", 250000, "8934567890127", "ACTIVE", "Nước tăng lực bò húc Thái Lan"}
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
            int[] colWidths = {8, 22, 38, 20, 25, 25, 25, 22, 18, 22, 28, 35};
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
                    "3. CÂY PHÂN CẤP NHÓM HÀNG (S2-06 & S2-08):",
                    "   - Cột 'Ngành hàng (Cấp 1)': Cấp gốc cao nhất của danh mục hàng (vd: Đồ uống, Bánh kẹo, Gia vị...).",
                    "   - Cột 'Nhóm hàng (Cấp 2)': Nhóm hàng trực thuộc ngành hàng (vd: Nước giải khát, Bia & Đồ uống có cồn...).",
                    "   - Cột 'Phân nhóm (Cấp 3)': Phân nhóm chi tiết (vd: Có ga, Nước suối...).",
                    "   - Dữ liệu sẽ tự động đồng bộ và hiển thị trên Cây phân cấp (trang Quản lý nhóm hàng), không cần chọn thủ công từng sản phẩm.",
                    "4. QUY TẮC CẬP NHẬT & GHI ĐÈ CẤP CÂY PHÂN CẤP (AC2 & AC4):",
                    "   - Nếu Mã SKU chưa tồn tại trong hệ thống -> Hệ thống sẽ TẠO MỚI và gán vào nhánh cây phân cấp tương ứng.",
                    "   - Nếu Mã SKU đã tồn tại (hoặc lặp lại trong tệp) nhưng khác cấp cây -> Tự động GHI ĐÈ, đổi level cây phân cấp của sản phẩm thay vì báo lỗi dòng.",
                    "5. Tên sản phẩm (*): Tên hiển thị đầy đủ của mặt hàng (tối đa 200 ký tự).",
                    "6. Đơn vị tính cơ sở (*): Đơn vị nhỏ nhất phục vụ theo dõi tồn kho và xuất nhập hàng (vd: Lon, Chai, Hộp, Gói, Cái, Kg...).",
                    "7. Quy cách đóng gói: Diễn giải cách đóng thùng/lốc phục vụ quy đổi đơn vị (vd: Thùng 24 lon, Thùng 12 hộp).",
                    "8. Giá vốn (VNĐ): Là số tiền >= 0. Nếu để trống, hệ thống mặc định giá vốn là 0 VNĐ (sản phẩm cập nhật sẽ giữ nguyên giá cũ).",
                    "9. Mã vạch: Mã vạch sản phẩm (EAN-13, Barcode...) dùng khi quét máy quét mã vạch (tùy chọn).",
                    "10. Trạng thái: ACTIVE (Đang kinh doanh) hoặc INACTIVE (Ngừng kinh doanh). Mặc định là ACTIVE nếu để trống.",
                    "11. Hệ thống hỗ trợ xử lý mượt mà lên tới 5.000 mã hàng mỗi lần nhập theo cơ chế Batch.",
                    "12. Các dòng có dữ liệu lỗi sẽ được báo cáo chi tiết và bỏ qua, những dòng hợp lệ vẫn sẽ được nhập/cập nhật thành công."
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

        // Nạp và cache cây nhóm hàng để tra cứu hoặc tự tạo nhánh phân cấp (Phần 3)
        Map<String, ProductCategory> categoryCache = new HashMap<>();
        List<ProductCategory> existingCategories = productCategoryRepository.findAllByOrderByLevelAscNameAsc();
        for (ProductCategory c : existingCategories) {
            Long parentId = c.getParent() != null ? c.getParent().getId() : null;
            categoryCache.put(c.getLevel() + ":" + (parentId == null ? "null" : parentId) + ":" + c.getName().trim().toLowerCase(), c);
            categoryCache.put("code:" + c.getCode().toUpperCase(), c);
            categoryCache.putIfAbsent("name:" + c.getName().trim().toLowerCase(), c);
        }

        int createdCount = 0;
        int updatedCount = 0;
        Map<String, Product> productsToSaveMap = new LinkedHashMap<>();

        for (ProductImportRowDto row : validRows) {
            String skuKey = row.getSku().trim().toUpperCase();
            Product product = existingProductMap.get(skuKey);

            ProductCategory targetCategory = resolveOrCreateCategory(row.getDepartment(), row.getCategory(), row.getSubCategory(), categoryCache);

            if (product != null) {
                // S2-08 AC2 & Phần 4: SKU đã tồn tại hoặc dòng sau trong file trùng SKU -> CẬP NHẬT / GHI ĐÈ (đổi level cây phân cấp)
                product.setName(row.getName().trim());
                product.setBaseUnit(row.getBaseUnit().trim());
                if (targetCategory != null) {
                    product.setProductCategory(targetCategory);
                    product.setCategory(targetCategory.getName());
                } else if (StringUtils.hasText(row.getCategory())) {
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

                if (!productsToSaveMap.containsKey(skuKey)) {
                    updatedCount++;
                }
                productsToSaveMap.put(skuKey, product);
            } else {
                // S2-08 AC2: SKU chưa có -> TẠO MỚI (chuẩn hóa SKU chữ hoa, gán trực tiếp vào cây phân cấp)
                Product newProd = Product.builder()
                        .sku(skuKey)
                        .name(row.getName().trim())
                        .baseUnit(row.getBaseUnit().trim())
                        .productCategory(targetCategory)
                        .category(targetCategory != null ? targetCategory.getName() : (StringUtils.hasText(row.getCategory()) ? row.getCategory().trim() : null))
                        .packaging(StringUtils.hasText(row.getPackaging()) ? row.getPackaging().trim() : null)
                        .costPrice(row.getCostPrice() != null ? row.getCostPrice() : BigDecimal.ZERO)
                        .barcode(StringUtils.hasText(row.getBarcode()) ? row.getBarcode().trim() : null)
                        .status(StringUtils.hasText(row.getStatus()) ? row.getStatus().trim().toUpperCase() : "ACTIVE")
                        .description(StringUtils.hasText(row.getDescription()) ? row.getDescription().trim() : null)
                        .build();

                productsToSaveMap.put(skuKey, newProd);
                existingProductMap.put(skuKey, newProd); // Cập nhật map để nếu lặp lại trong file thì ghi đè (Phần 4)
                createdCount++;
            }
        }

        // Lưu trữ theo batch tối ưu hiệu năng với danh mục lớn 5.000 SKU
        List<Product> productsToSave = new ArrayList<>(productsToSaveMap.values());
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

            // Phát hiện vị trí các cột động dựa trên tiêu đề dòng 0
            Row headerRow = sheet.getRow(0);
            int colSku = 1;
            int colName = 2;
            int colBaseUnit = 3;
            int colDepartment = -1;
            int colCategory = 4;
            int colSubCategory = -1;
            int colPackaging = 5;
            int colCostPrice = 6;
            int colBarcode = 7;
            int colStatus = 8;
            int colDescription = 9;

            if (headerRow != null) {
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    if (cell == null) continue;
                    String title = clean(formatter.formatCellValue(cell)).toLowerCase();
                    if (title.contains("sku")) {
                        colSku = c;
                    } else if (title.contains("tên") && !title.contains("nhóm")) {
                        colName = c;
                    } else if (title.contains("đơn vị") || title.contains("dvt")) {
                        colBaseUnit = c;
                    } else if (title.contains("ngành") || title.contains("cấp 1")) {
                        colDepartment = c;
                    } else if (title.contains("phân nhóm") || title.contains("cấp 3")) {
                        colSubCategory = c;
                    } else if (title.contains("nhóm") || title.contains("cấp 2")) {
                        colCategory = c;
                    } else if (title.contains("quy cách") || title.contains("đóng gói")) {
                        colPackaging = c;
                    } else if (title.contains("giá")) {
                        colCostPrice = c;
                    } else if (title.contains("vạch") || title.contains("barcode")) {
                        colBarcode = c;
                    } else if (title.contains("trạng thái")) {
                        colStatus = c;
                    } else if (title.contains("mô tả") || title.contains("ghi chú")) {
                        colDescription = c;
                    }
                }
            }

            // Thu thập trước tất cả SKU trong file để kiểm tra trùng trong DB qua 1 lần query (Bulk query)
            List<String> skusInFile = new ArrayList<>();
            for (int r = 1; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row, formatter)) continue;
                String sku = colSku >= 0 ? clean(formatter.formatCellValue(row.getCell(colSku))) : "";
                if (StringUtils.hasText(sku)) {
                    skusInFile.add(sku.trim().toUpperCase());
                }
            }

            // Truy vấn 1 lần duy nhất danh sách các SKU đã tồn tại trong DB không phân biệt hoa thường
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

                int rowNumber = r + 1; // Số dòng thực tế trên Excel
                List<String> errors = new ArrayList<>();

                String sku = colSku >= 0 ? clean(formatter.formatCellValue(row.getCell(colSku))) : "";
                String name = colName >= 0 ? clean(formatter.formatCellValue(row.getCell(colName))) : "";
                String baseUnit = colBaseUnit >= 0 ? clean(formatter.formatCellValue(row.getCell(colBaseUnit))) : "";
                String rawDepartment = colDepartment >= 0 ? clean(formatter.formatCellValue(row.getCell(colDepartment))) : "";
                String rawCategory = colCategory >= 0 ? clean(formatter.formatCellValue(row.getCell(colCategory))) : "";
                String rawSubCategory = colSubCategory >= 0 ? clean(formatter.formatCellValue(row.getCell(colSubCategory))) : "";
                String packaging = colPackaging >= 0 ? clean(formatter.formatCellValue(row.getCell(colPackaging))) : "";
                String rawCostPrice = colCostPrice >= 0 ? clean(formatter.formatCellValue(row.getCell(colCostPrice))) : "";
                String barcode = colBarcode >= 0 ? clean(formatter.formatCellValue(row.getCell(colBarcode))) : "";
                String rawStatus = colStatus >= 0 ? clean(formatter.formatCellValue(row.getCell(colStatus))) : "";
                String description = colDescription >= 0 ? clean(formatter.formatCellValue(row.getCell(colDescription))) : "";

                // Xử lý phân cấp ngành hàng - nhóm hàng - phân nhóm
                String department = rawDepartment;
                String category = rawCategory;
                String subCategory = rawSubCategory;

                // Nếu người dùng gộp dạng "Đồ uống > Nước giải khát > Có ga" trong ô nhóm hàng
                if (!StringUtils.hasText(department) && !StringUtils.hasText(subCategory) && StringUtils.hasText(category)) {
                    if (category.contains(">") || category.contains("/")) {
                        String[] parts = category.split("[>/]");
                        if (parts.length >= 3) {
                            department = parts[0].trim();
                            category = parts[1].trim();
                            subCategory = parts[2].trim();
                        } else if (parts.length == 2) {
                            department = parts[0].trim();
                            category = parts[1].trim();
                        }
                    }
                }

                List<String> pathParts = new ArrayList<>();
                if (StringUtils.hasText(department)) pathParts.add(department);
                if (StringUtils.hasText(category)) pathParts.add(category);
                if (StringUtils.hasText(subCategory)) pathParts.add(subCategory);

                String categoryPath = String.join(" > ", pathParts);
                int categoryLevel = pathParts.isEmpty() ? 1 : pathParts.size();
                String displayCategory = StringUtils.hasText(subCategory) ? subCategory :
                        (StringUtils.hasText(category) ? category : department);

                boolean isUpdate = false;
                String action = "CREATE";
                boolean levelChanged = false;

                // 1. Kiểm tra Mã SKU (*)
                if (!StringUtils.hasText(sku)) {
                    errors.add("Mã SKU không được để trống.");
                } else if (sku.length() < 2 || sku.length() > 50) {
                    errors.add("Mã SKU phải có độ dài từ 2 đến 50 ký tự.");
                } else if (!SKU_PATTERN.matcher(sku).matches()) {
                    errors.add("Mã SKU chứa ký tự không hợp lệ. Chỉ chấp nhận chữ cái, số, gạch ngang, gạch dưới, dấu chấm.");
                } else {
                    String skuUpper = sku.trim().toUpperCase();
                    // S2-08 & Phần 4: Nếu SKU đã tồn tại trong DB hoặc lặp lại trong file -> Đổi thành UPDATE / Ghi đè cấp cây thay vì báo lỗi dòng
                    if (!seenSkusInFile.add(skuUpper)) {
                        isUpdate = true;
                        action = "UPDATE";
                        levelChanged = true;
                    } else if (existingSkusInDb.contains(skuUpper)) {
                        isUpdate = true;
                        action = "UPDATE";
                        levelChanged = true;
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
                if (StringUtils.hasText(displayCategory) && displayCategory.length() > 100) {
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

                boolean valid = errors.isEmpty();

                result.add(ProductImportRowDto.builder()
                        .rowNumber(rowNumber)
                        .sku(sku)
                        .name(name)
                        .category(displayCategory)
                        .department(department)
                        .subCategory(subCategory)
                        .categoryPath(categoryPath)
                        .categoryLevel(categoryLevel)
                        .levelChanged(levelChanged)
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

    /**
     * Tự động tìm kiếm hoặc khởi tạo nhánh phân cấp ngành hàng - nhóm hàng - phân nhóm (Phần 3 & Phần 4).
     */
    private ProductCategory resolveOrCreateCategory(String department, String category, String subCategory,
                                                    Map<String, ProductCategory> categoryCache) {
        if (!StringUtils.hasText(department) && !StringUtils.hasText(category) && !StringUtils.hasText(subCategory)) {
            return null;
        }

        ProductCategory currentParent = null;

        // 1. Cấp 1: Ngành hàng
        if (StringUtils.hasText(department)) {
            String deptKey = "1:null:" + department.trim().toLowerCase();
            ProductCategory deptCat = categoryCache.get(deptKey);
            if (deptCat == null) {
                deptCat = categoryCache.get("name:" + department.trim().toLowerCase());
            }
            if (deptCat == null) {
                String code = generateUniqueCategoryCode(department, 1, categoryCache);
                deptCat = ProductCategory.builder()
                        .name(department.trim())
                        .code(code)
                        .level(1)
                        .parent(null)
                        .build();
                deptCat = productCategoryRepository.save(deptCat);
                categoryCache.put(deptKey, deptCat);
                categoryCache.put("code:" + deptCat.getCode().toUpperCase(), deptCat);
                categoryCache.put("name:" + deptCat.getName().trim().toLowerCase(), deptCat);
            }
            currentParent = deptCat;
        }

        // 2. Cấp 2: Nhóm hàng
        if (StringUtils.hasText(category)) {
            Long parentId = currentParent != null ? currentParent.getId() : null;
            String catKey = "2:" + (parentId == null ? "null" : parentId) + ":" + category.trim().toLowerCase();
            ProductCategory catNode = categoryCache.get(catKey);
            if (catNode == null && currentParent == null) {
                catNode = categoryCache.get("name:" + category.trim().toLowerCase());
            }
            if (catNode == null) {
                int level = currentParent != null ? currentParent.getLevel() + 1 : (StringUtils.hasText(department) ? 2 : 1);
                String code = generateUniqueCategoryCode(category, level, categoryCache);
                catNode = ProductCategory.builder()
                        .name(category.trim())
                        .code(code)
                        .level(level)
                        .parent(currentParent)
                        .build();
                catNode = productCategoryRepository.save(catNode);
                categoryCache.put(catKey, catNode);
                categoryCache.put("code:" + catNode.getCode().toUpperCase(), catNode);
                categoryCache.put("name:" + catNode.getName().trim().toLowerCase(), catNode);
            }
            currentParent = catNode;
        }

        // 3. Cấp 3: Phân nhóm
        if (StringUtils.hasText(subCategory)) {
            Long parentId = currentParent != null ? currentParent.getId() : null;
            String subKey = "3:" + (parentId == null ? "null" : parentId) + ":" + subCategory.trim().toLowerCase();
            ProductCategory subNode = categoryCache.get(subKey);
            if (subNode == null && currentParent == null) {
                subNode = categoryCache.get("name:" + subCategory.trim().toLowerCase());
            }
            if (subNode == null) {
                int level = currentParent != null ? currentParent.getLevel() + 1 : 1;
                String code = generateUniqueCategoryCode(subCategory, level, categoryCache);
                subNode = ProductCategory.builder()
                        .name(subCategory.trim())
                        .code(code)
                        .level(level)
                        .parent(currentParent)
                        .build();
                subNode = productCategoryRepository.save(subNode);
                categoryCache.put(subKey, subNode);
                categoryCache.put("code:" + subNode.getCode().toUpperCase(), subNode);
                categoryCache.put("name:" + subNode.getName().trim().toLowerCase(), subNode);
            }
            currentParent = subNode;
        }

        return currentParent;
    }

    private String generateUniqueCategoryCode(String name, int level, Map<String, ProductCategory> categoryCache) {
        String baseSlug = slugify(name);
        if (baseSlug.length() > 22) {
            baseSlug = baseSlug.substring(0, 22);
        }
        String code = baseSlug;
        int counter = 1;
        while (categoryCache.containsKey("code:" + code.toUpperCase()) || productCategoryRepository.existsByCodeIgnoreCase(code)) {
            code = baseSlug + "-" + counter++;
            if (code.length() > 30) {
                code = "CAT-" + (System.currentTimeMillis() % 1000000);
                break;
            }
        }
        return code;
    }

    private String slugify(String input) {
        if (!StringUtils.hasText(input)) return "CAT";
        String unaccented = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        String slug = unaccented.toUpperCase().replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 25) {
            slug = slug.substring(0, 25);
        }
        return slug.isBlank() ? "CAT" : slug;
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
        for (int c = 1; c <= 11; c++) {
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
