package com.erp.backend.service;

import com.erp.backend.dto.inventory.aging.*;
import com.erp.backend.entity.*;
import com.erp.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * S8-07: Dịch vụ tính toán báo cáo tồn kho và vòng quay hàng hóa (Aging Report).
 * - Thuật toán tính số ngày lưu kho bình quân của từng SKU.
 * - Chỉ ra hàng chậm luân chuyển (> 60 ngày) và hàng chôn vốn (> 90 ngày).
 * - Tổng hợp giá trị tồn theo kho và theo nhóm hàng.
 * - Xuất file Excel báo cáo chi tiết.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAgingReportService {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final ProductLotRepository productLotRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductCategoryRepository categoryRepository;

    Clock clock = Clock.system(VN_ZONE);

    /**
     * Lấy báo cáo tồn kho và tuổi hàng theo các tiêu chí lọc.
     */
    @Transactional(readOnly = true)
    public InventoryAgingReportResponse getAgingReport(
            String warehouseIdStr,
            String categoryIdStr,
            String agingBracketFilter,
            String keyword) {

        LocalDate today = LocalDate.now(clock);

        Long filterWhId = parseLongOrNull(warehouseIdStr);
        Long filterCatId = parseLongOrNull(categoryIdStr);

        // 1. Lấy danh sách tồn kho phù hợp
        List<Inventory> inventoryList;
        if (filterWhId != null) {
            inventoryList = inventoryRepository.findByWarehouse_Id(filterWhId);
        } else {
            inventoryList = inventoryRepository.findAll();
        }

        // Lọc các bản ghi có tồn kho thực tế > 0
        List<InventoryAgingItemResponse> items = new ArrayList<>();

        for (Inventory inv : inventoryList) {
            BigDecimal qty = inv.getPhysicalStock() != null ? inv.getPhysicalStock() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            Product product = inv.getProduct();
            Warehouse warehouse = inv.getWarehouse();
            if (product == null || warehouse == null) {
                continue;
            }

            // Lọc theo nhóm hàng nếu có
            ProductCategory cat = product.getProductCategory();
            if (filterCatId != null) {
                if (cat == null || !filterCatId.equals(cat.getId())) {
                    continue;
                }
            }

            // Lọc theo từ khóa tìm kiếm (SKU hoặc Tên sản phẩm)
            if (StringUtils.hasText(keyword)) {
                String kw = keyword.trim().toLowerCase();
                boolean matchSku = product.getSku() != null && product.getSku().toLowerCase().contains(kw);
                boolean matchName = product.getName() != null && product.getName().toLowerCase().contains(kw);
                if (!matchSku && !matchName) {
                    continue;
                }
            }

            // Tính giá vốn và tổng giá trị tồn
            BigDecimal costPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
            BigDecimal totalValue = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

            // Thuật toán tính số ngày lưu kho bình quân (Aging calculation)
            long avgDays = calculateAverageAgingDays(product, warehouse, inv, today);

            String bracket = determineAgingBracket(avgDays);
            String speed = determineTurnoverSpeed(avgDays);

            // Lọc theo nhóm tuổi hàng nếu có
            if (StringUtils.hasText(agingBracketFilter) && !"ALL".equalsIgnoreCase(agingBracketFilter)) {
                if (!agingBracketFilter.equalsIgnoreCase(bracket)) {
                    continue;
                }
            }

            String lastMovement = inv.getUpdatedAt() != null ? inv.getUpdatedAt().format(DATE_TIME_FORMATTER) : null;
            String categoryName = cat != null ? cat.getName() : (product.getCategory() != null ? product.getCategory() : "Chưa phân loại");

            items.add(InventoryAgingItemResponse.builder()
                    .id(inv.getId())
                    .productId(product.getId())
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .categoryName(categoryName)
                    .warehouseId(warehouse.getId())
                    .warehouseCode(warehouse.getCode())
                    .warehouseName(warehouse.getName())
                    .baseUnit(product.getBaseUnit())
                    .quantity(qty.setScale(2, RoundingMode.HALF_UP))
                    .costPrice(costPrice.setScale(2, RoundingMode.HALF_UP))
                    .totalValue(totalValue)
                    .averageAgingDays(avgDays)
                    .agingBracket(bracket)
                    .turnoverSpeed(speed)
                    .lastMovementDate(lastMovement)
                    .build());
        }

        // Sắp xếp mặt hàng có số ngày tồn lâu nhất lên đầu để dễ nhận diện hàng đọng vốn
        items.sort(Comparator.comparing(InventoryAgingItemResponse::getAverageAgingDays).reversed()
                .thenComparing(InventoryAgingItemResponse::getTotalValue, Comparator.reverseOrder()));

        // 2. Tính toán tổng hợp Summary
        InventoryAgingSummaryResponse summary = calculateSummary(items);

        return InventoryAgingReportResponse.builder()
                .items(items)
                .summary(summary)
                .build();
    }

    /**
     * Thuật toán tính số ngày lưu kho bình quân của sản phẩm tại kho.
     * - Ưu tiên tính bình quân gia quyền theo số lượng tồn của các lô hàng còn tồn.
     * - Nếu không quản lý theo lô hoặc không còn lô, tính theo ngày tạo tồn kho / cập nhật gần nhất.
     */
    public long calculateAverageAgingDays(Product product, Warehouse warehouse, Inventory inventory, LocalDate today) {
        List<ProductLot> lots = productLotRepository.findByProduct_IdAndWarehouse_Id(product.getId(), warehouse.getId());

        List<ProductLot> activeLots = lots.stream()
                .filter(l -> l.getQuantity() != null && l.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (!activeLots.isEmpty()) {
            BigDecimal totalWeightedDays = BigDecimal.ZERO;
            BigDecimal totalLotQty = BigDecimal.ZERO;

            for (ProductLot lot : activeLots) {
                LocalDate refDate = lot.getManufactureDate();
                if (refDate == null && lot.getCreatedAt() != null) {
                    refDate = lot.getCreatedAt().toLocalDate();
                }
                if (refDate == null) {
                    refDate = today;
                }

                long days = Math.max(0, ChronoUnit.DAYS.between(refDate, today));
                BigDecimal lotDays = BigDecimal.valueOf(days);

                totalWeightedDays = totalWeightedDays.add(lot.getQuantity().multiply(lotDays));
                totalLotQty = totalLotQty.add(lot.getQuantity());
            }

            if (totalLotQty.compareTo(BigDecimal.ZERO) > 0) {
                return totalWeightedDays.divide(totalLotQty, 0, RoundingMode.HALF_UP).longValue();
            }
        }

        // Fallback: Tính từ ngày tạo hoặc cập nhật của bản ghi tồn kho
        LocalDate refDate = today;
        if (inventory.getCreatedAt() != null) {
            refDate = inventory.getCreatedAt().toLocalDate();
        } else if (product.getCreatedAt() != null) {
            refDate = product.getCreatedAt().toLocalDate();
        }

        return Math.max(0, ChronoUnit.DAYS.between(refDate, today));
    }

    public static String determineAgingBracket(long days) {
        if (days <= 30) return "0_30";
        if (days <= 60) return "31_60";
        if (days <= 90) return "61_90";
        return "OVER_90";
    }

    public static String determineTurnoverSpeed(long days) {
        if (days <= 30) return "FAST";
        if (days <= 60) return "NORMAL";
        if (days <= 90) return "SLOW";
        return "STAGNANT";
    }

    private InventoryAgingSummaryResponse calculateSummary(List<InventoryAgingItemResponse> items) {
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal stagnantValue = BigDecimal.ZERO;
        int slowMovingCount = 0;
        BigDecimal totalWeightedDays = BigDecimal.ZERO;
        BigDecimal totalQtyForWeight = BigDecimal.ZERO;

        for (InventoryAgingItemResponse item : items) {
            totalValue = totalValue.add(item.getTotalValue());

            if (item.getAverageAgingDays() > 60) {
                slowMovingCount++;
            }

            if (item.getAverageAgingDays() > 90) {
                stagnantValue = stagnantValue.add(item.getTotalValue());
            }

            totalWeightedDays = totalWeightedDays.add(item.getQuantity().multiply(BigDecimal.valueOf(item.getAverageAgingDays())));
            totalQtyForWeight = totalQtyForWeight.add(item.getQuantity());
        }

        double slowRatio = items.isEmpty() ? 0.0 :
                Math.round(((double) slowMovingCount / items.size() * 100.0) * 10.0) / 10.0;

        double avgSystemDays = totalQtyForWeight.compareTo(BigDecimal.ZERO) > 0 ?
                Math.round((totalWeightedDays.divide(totalQtyForWeight, 1, RoundingMode.HALF_UP).doubleValue()) * 10.0) / 10.0 : 0.0;

        return InventoryAgingSummaryResponse.builder()
                .totalInventoryValue(totalValue.setScale(2, RoundingMode.HALF_UP))
                .slowMovingCount(slowMovingCount)
                .slowMovingRatio(slowRatio)
                .stagnantValue(stagnantValue.setScale(2, RoundingMode.HALF_UP))
                .averageSystemDays(avgSystemDays)
                .build();
    }

    /**
     * Thống kê tổng giá trị tồn theo từng kho hàng.
     */
    @Transactional(readOnly = true)
    public List<InventoryAgingWarehouseValueResponse> getSummaryByWarehouse() {
        List<Inventory> all = inventoryRepository.findAll();
        Map<Long, List<Inventory>> byWh = all.stream()
                .filter(i -> i.getWarehouse() != null && i.getPhysicalStock() != null && i.getPhysicalStock().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(i -> i.getWarehouse().getId()));

        List<InventoryAgingWarehouseValueResponse> result = new ArrayList<>();
        for (Map.Entry<Long, List<Inventory>> entry : byWh.entrySet()) {
            List<Inventory> invs = entry.getValue();
            Warehouse wh = invs.get(0).getWarehouse();

            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalVal = BigDecimal.ZERO;

            for (Inventory inv : invs) {
                BigDecimal q = inv.getPhysicalStock();
                BigDecimal cost = inv.getProduct() != null && inv.getProduct().getCostPrice() != null ? inv.getProduct().getCostPrice() : BigDecimal.ZERO;
                totalQty = totalQty.add(q);
                totalVal = totalVal.add(q.multiply(cost));
            }

            result.add(InventoryAgingWarehouseValueResponse.builder()
                    .warehouseId(wh.getId())
                    .warehouseCode(wh.getCode())
                    .warehouseName(wh.getName())
                    .skuCount(invs.size())
                    .totalQuantity(totalQty.setScale(2, RoundingMode.HALF_UP))
                    .totalValue(totalVal.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        result.sort(Comparator.comparing(InventoryAgingWarehouseValueResponse::getTotalValue).reversed());
        return result;
    }

    /**
     * Thống kê tổng giá trị tồn theo từng nhóm hàng (Category).
     */
    @Transactional(readOnly = true)
    public List<InventoryAgingCategoryValueResponse> getSummaryByCategory() {
        List<Inventory> all = inventoryRepository.findAll();
        Map<String, List<Inventory>> byCat = all.stream()
                .filter(i -> i.getProduct() != null && i.getPhysicalStock() != null && i.getPhysicalStock().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(i -> {
                    ProductCategory c = i.getProduct().getProductCategory();
                    return c != null ? String.valueOf(c.getId()) : "UNCLASSIFIED";
                }));

        List<InventoryAgingCategoryValueResponse> result = new ArrayList<>();
        for (Map.Entry<String, List<Inventory>> entry : byCat.entrySet()) {
            List<Inventory> invs = entry.getValue();
            Product p0 = invs.get(0).getProduct();
            ProductCategory cat = p0.getProductCategory();

            Long catId = cat != null ? cat.getId() : null;
            String catCode = cat != null ? cat.getCode() : "KHAC";
            String catName = cat != null ? cat.getName() : "Chưa phân loại";

            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalVal = BigDecimal.ZERO;

            for (Inventory inv : invs) {
                BigDecimal q = inv.getPhysicalStock();
                BigDecimal cost = inv.getProduct().getCostPrice() != null ? inv.getProduct().getCostPrice() : BigDecimal.ZERO;
                totalQty = totalQty.add(q);
                totalVal = totalVal.add(q.multiply(cost));
            }

            result.add(InventoryAgingCategoryValueResponse.builder()
                    .categoryId(catId)
                    .categoryCode(catCode)
                    .categoryName(catName)
                    .skuCount(invs.size())
                    .totalQuantity(totalQty.setScale(2, RoundingMode.HALF_UP))
                    .totalValue(totalVal.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        result.sort(Comparator.comparing(InventoryAgingCategoryValueResponse::getTotalValue).reversed());
        return result;
    }

    /**
     * Xuất file Excel báo cáo luân chuyển kho (.xlsx) chuẩn Apache POI.
     */
    @Transactional(readOnly = true)
    public byte[] exportToExcel(String warehouseId, String categoryId, String agingBracket, String keyword) throws IOException {
        InventoryAgingReportResponse report = getAgingReport(warehouseId, categoryId, agingBracket, keyword);
        List<InventoryAgingItemResponse> items = report.getItems();

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Bao_cao_luan_chuyen_kho");

            // Style Header
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // Style tiền tệ
            DataFormat format = workbook.createDataFormat();
            CellStyle currencyStyle = workbook.createCellStyle();
            currencyStyle.setDataFormat(format.getFormat("#,##0"));

            String[] columns = {
                    "STT", "Mã SKU", "Tên sản phẩm", "Nhóm hàng", "Kho lưu trữ",
                    "Đơn vị tính", "Số lượng tồn", "Giá vốn (VNĐ)", "Tổng giá trị tồn (VNĐ)",
                    "Số ngày lưu kho bình quân", "Tốc độ luân chuyển", "Nhóm tuổi hàng"
            };

            Row headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(24);
            for (int col = 0; col < columns.length; col++) {
                Cell cell = headerRow.createCell(col);
                cell.setCellValue(columns[col]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (InventoryAgingItemResponse item : items) {
                Row row = sheet.createRow(rowIdx);
                row.createCell(0).setCellValue(rowIdx);
                row.createCell(1).setCellValue(item.getProductSku());
                row.createCell(2).setCellValue(item.getProductName());
                row.createCell(3).setCellValue(item.getCategoryName());
                row.createCell(4).setCellValue(item.getWarehouseName());
                row.createCell(5).setCellValue(item.getBaseUnit());
                row.createCell(6).setCellValue(item.getQuantity().doubleValue());

                Cell costCell = row.createCell(7);
                costCell.setCellValue(item.getCostPrice().doubleValue());
                costCell.setCellStyle(currencyStyle);

                Cell valCell = row.createCell(8);
                valCell.setCellValue(item.getTotalValue().doubleValue());
                valCell.setCellStyle(currencyStyle);

                row.createCell(9).setCellValue(item.getAverageAgingDays());
                row.createCell(10).setCellValue(mapSpeedLabel(item.getTurnoverSpeed()));
                row.createCell(11).setCellValue(mapBracketLabel(item.getAgingBracket()));

                rowIdx++;
            }

            for (int col = 0; col < columns.length; col++) {
                sheet.autoSizeColumn(col);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static String mapSpeedLabel(String speed) {
        if ("FAST".equalsIgnoreCase(speed)) return "Nhanh (0-30 ngày)";
        if ("NORMAL".equalsIgnoreCase(speed)) return "Bình thường (31-60 ngày)";
        if ("SLOW".equalsIgnoreCase(speed)) return "Chậm luân chuyển (61-90 ngày)";
        return "Chôn vốn (> 90 ngày)";
    }

    private static String mapBracketLabel(String bracket) {
        if ("0_30".equalsIgnoreCase(bracket)) return "0 - 30 ngày";
        if ("31_60".equalsIgnoreCase(bracket)) return "31 - 60 ngày";
        if ("61_90".equalsIgnoreCase(bracket)) return "61 - 90 ngày";
        return "Trên 90 ngày";
    }

    private static Long parseLongOrNull(String str) {
        if (!StringUtils.hasText(str) || "ALL".equalsIgnoreCase(str.trim())) {
            return null;
        }
        try {
            return Long.parseLong(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
