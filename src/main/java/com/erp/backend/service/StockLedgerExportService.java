package com.erp.backend.service;

import com.erp.backend.dto.inventory.StockLedgerCriteria;
import com.erp.backend.dto.inventory.StockLedgerRow;
import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.ProductCategory;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.InventorySpecifications;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * S8-08: Xuất Excel sổ tồn kho đúng bộ lọc đang xem (kho, nhóm hàng, trạng thái tồn, từ khoá, sắp xếp).
 * - Giữ đủ 3 cột tồn (ĐVT cơ sở): thực tế / đang giữ chỗ / khả dụng, kèm dòng tổng.
 * - Tên tệp có mã kho và ngày xuất: TonKho_WH-MB01_20261015.xlsx (không chọn kho: TonKho_TatCaKho_...).
 * - Cùng phạm vi xem với màn sổ tồn: NV kho chỉ xuất được kho mình được gắn. Không có giá vốn.
 */
@Service
@RequiredArgsConstructor
public class StockLedgerExportService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Giới hạn số dòng để không làm treo máy chủ khi xuất (sổ tồn = số SKU x số kho nên thực tế nhỏ hơn nhiều). */
    static final long MAX_EXPORT_ROWS = 50_000;
    static final String[] HEADERS = {"STT", "Mã kho", "Tên kho", "Mã SKU", "Tên sản phẩm", "Nhóm hàng", "ĐVT cơ sở",
            "Tồn thực tế", "Đang giữ chỗ", "Tồn khả dụng", "Trạng thái tồn", "Cập nhật lúc"};
    static final int HEADER_ROW = 4;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockLedgerService stockLedgerService;
    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductCategoryRepository categoryRepository;

    Clock clock = Clock.system(VN_ZONE);

    public record ExportFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public ExportFile export(Long warehouseId, Long categoryId, String stockStatus, String keyword, String sort,
                             UserDetailsImpl actor) {
        StockLedgerCriteria criteria = stockLedgerService.criteria(warehouseId, categoryId, stockStatus, keyword, actor);
        if (warehouseId != null && criteria.allowedWarehouseIds() != null
                && !criteria.allowedWarehouseIds().contains(warehouseId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "EXPORT_WAREHOUSE_NOT_ASSIGNED",
                    "Bạn không được phân công kho này nên không xuất được sổ tồn", "warehouseId");
        }
        Specification<Inventory> spec = InventorySpecifications.ledger(criteria);
        long count = inventoryRepository.count(spec);
        if (count > MAX_EXPORT_ROWS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EXPORT_TOO_LARGE",
                    "Kết quả có " + count + " dòng, vượt giới hạn " + MAX_EXPORT_ROWS
                            + " dòng mỗi lần xuất. Hãy lọc theo kho hoặc nhóm hàng.", null);
        }
        List<StockLedgerRow> rows = new ArrayList<>();
        if (count > 0) {
            for (Inventory i : inventoryRepository.findAll(spec, StockLedgerService.sort(sort))) {
                rows.add(StockLedgerService.toRow(i));
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Warehouse warehouse = warehouseId != null ? warehouseRepository.findById(warehouseId).orElse(null) : null;
        String filter = filterText(warehouse, categoryId, criteria.stockStatus(), criteria.keyword());
        byte[] content = build(rows, filter, now, actor != null ? actor.getUsername() : null);
        return new ExportFile(fileName(warehouse, now), content);
    }

    /** TonKho_<mã kho>_<yyyyMMdd>.xlsx; mã kho chỉ giữ chữ, số, gạch để tên tệp an toàn. */
    static String fileName(Warehouse warehouse, LocalDateTime now) {
        String code = warehouse != null && warehouse.getCode() != null
                ? warehouse.getCode().replaceAll("[^A-Za-z0-9_-]", "") : "";
        if (code.isEmpty()) {
            code = "TatCaKho";
        }
        return "TonKho_" + code + "_" + now.format(FILE_DATE) + ".xlsx";
    }

    private String filterText(Warehouse warehouse, Long categoryId, String stockStatus, String keyword) {
        List<String> parts = new ArrayList<>();
        parts.add("Kho: " + (warehouse != null ? warehouse.getCode() + " - " + warehouse.getName() : "Tất cả kho được xem"));
        if (categoryId != null) {
            String name = categoryRepository.findById(categoryId).map(ProductCategory::getName).orElse("#" + categoryId);
            parts.add("Nhóm hàng: " + name + " (gồm nhóm con)");
        }
        if (stockStatus != null) {
            parts.add("Trạng thái: " + StockLedgerService.STATUS_LABELS.getOrDefault(stockStatus, stockStatus));
        }
        if (keyword != null) {
            parts.add("Từ khoá: " + keyword);
        }
        return String.join(" · ", parts);
    }

    static byte[] build(List<StockLedgerRow> rows, String filter, LocalDateTime exportedAt, String username) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("SoTonKho");
            DataFormat fmt = wb.createDataFormat();

            Font bold = wb.createFont();
            bold.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle title = wb.createCellStyle();
            title.setFont(titleFont);
            CellStyle header = wb.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            border(header);
            CellStyle text = wb.createCellStyle();
            border(text);
            CellStyle number = wb.createCellStyle();
            number.setDataFormat(fmt.getFormat("#,##0.####"));
            border(number);
            CellStyle totalLabel = wb.createCellStyle();
            totalLabel.setFont(bold);
            border(totalLabel);
            CellStyle totalNumber = wb.createCellStyle();
            totalNumber.setFont(bold);
            totalNumber.setDataFormat(fmt.getFormat("#,##0.####"));
            border(totalNumber);

            Row r0 = sheet.createRow(0);
            cell(r0, 0, "SỔ TỒN KHO 3 CỘT (THEO ĐƠN VỊ CƠ SỞ)", title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));
            cell(sheet.createRow(1), 0, filter, null);
            cell(sheet.createRow(2), 0, "Xuất lúc: " + exportedAt.format(DATE_TIME)
                    + (username != null ? " · Người xuất: " + username : "") + " · Số dòng: " + rows.size(), null);

            Row h = sheet.createRow(HEADER_ROW);
            for (int i = 0; i < HEADERS.length; i++) {
                cell(h, i, HEADERS[i], header);
            }

            BigDecimal totalPhysical = BigDecimal.ZERO;
            BigDecimal totalReserved = BigDecimal.ZERO;
            BigDecimal totalAvailable = BigDecimal.ZERO;
            int r = HEADER_ROW + 1;
            int stt = 1;
            for (StockLedgerRow row : rows) {
                Row x = sheet.createRow(r++);
                Cell sttCell = x.createCell(0);
                sttCell.setCellValue(stt++);
                sttCell.setCellStyle(text);
                cell(x, 1, row.warehouseCode(), text);
                cell(x, 2, row.warehouseName(), text);
                cell(x, 3, row.sku(), text);
                cell(x, 4, row.productName(), text);
                cell(x, 5, row.categoryName(), text);
                cell(x, 6, row.baseUnit(), text);
                num(x, 7, row.physicalStock(), number);
                num(x, 8, row.reservedStock(), number);
                num(x, 9, row.availableStock(), number);
                cell(x, 10, row.stockStatusLabel(), text);
                cell(x, 11, row.updatedAt() != null ? row.updatedAt().format(DATE_TIME) : "", text);
                totalPhysical = totalPhysical.add(nz(row.physicalStock()));
                totalReserved = totalReserved.add(nz(row.reservedStock()));
                totalAvailable = totalAvailable.add(nz(row.availableStock()));
            }
            Row t = sheet.createRow(r);
            cell(t, 0, "TỔNG CỘNG", totalLabel);
            for (int i = 1; i <= 6; i++) {
                cell(t, i, "", totalLabel);
            }
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 6));
            num(t, 7, totalPhysical, totalNumber);
            num(t, 8, totalReserved, totalNumber);
            num(t, 9, totalAvailable, totalNumber);
            cell(t, 10, "", totalLabel);
            cell(t, 11, "", totalLabel);

            int[] widths = {6, 12, 24, 16, 36, 20, 12, 14, 14, 14, 18, 18};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, HEADER_ROW + 1);
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW + rows.size(), 0, HEADERS.length - 1));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED",
                    "Không tạo được tệp Excel, vui lòng thử lại", null);
        }
    }

    private static void border(CellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    /**
     * Số lượng ghi dạng số (Excel cộng / lọc được), không phải chữ. Thư viện Excel chỉ nhận double nên đổi ở bước
     * hiển thị cuối cùng; mọi phép tính (dòng tổng) vẫn làm bằng BigDecimal.
     */
    private static void num(Row row, int col, BigDecimal value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(nz(value).doubleValue());
        c.setCellStyle(style);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
