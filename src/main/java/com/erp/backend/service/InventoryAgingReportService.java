package com.erp.backend.service;

import com.erp.backend.dto.inventory.aging.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
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
    private static final Set<String> VALID_AGING_BRACKETS = Set.of("ALL", "0_30", "31_60", "61_90", "OVER_90");

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final ProductLotRepository productLotRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductCategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final StockMovementRepository stockMovementRepository;

    Clock clock = Clock.system(VN_ZONE);

    public InventoryAgingReportResponse getAgingReport(
            String warehouseIdStr,
            String categoryIdStr,
            String agingBracketFilter,
            String keyword) {
        return getAgingReport(warehouseIdStr, categoryIdStr, agingBracketFilter, keyword, null);
    }

    /**
     * Lấy báo cáo tồn kho và tuổi hàng theo các tiêu chí lọc.
     */
    @Transactional(readOnly = true)
    public InventoryAgingReportResponse getAgingReport(
            String warehouseIdStr,
            String categoryIdStr,
            String agingBracketFilter,
            String keyword,
            UserDetailsImpl actor) {

        LocalDate today = LocalDate.now(clock);

        // TC807-11: Validate tham số kiểu dữ liệu & giá trị hợp lệ
        Long requestedWhId = parseAndValidateLong(warehouseIdStr, "Mã kho");
        Long filterCatId = parseAndValidateLong(categoryIdStr, "Mã nhóm hàng");
        String validatedBracket = validateAgingBracket(agingBracketFilter);

        // TC807-14: Kiểm tra IDOR quyền kho của nhân viên kho
        Long filterWhId = resolveAndValidateWarehouseId(requestedWhId, actor);

        // TC807-13: Kiểm tra quyền xem giá vốn
        boolean canViewCost = canViewCostPrice(actor);

        // TC807-19: Tối ưu hiệu năng - Lấy dữ liệu với JOIN FETCH tránh N+1
        List<Inventory> inventoryList = inventoryRepository.findAgingInventoryWithProductAndWarehouse(filterWhId);
        if (inventoryList == null || inventoryList.isEmpty()) {
            if (filterWhId != null) {
                inventoryList = inventoryRepository.findByWarehouse_Id(filterWhId);
            }
            if (inventoryList == null || inventoryList.isEmpty()) {
                inventoryList = inventoryRepository.findAll();
            }
        }

        // Tải trước toàn bộ lô active và các đợt nhập mới nhất để tính toán trong bộ nhớ (Batch lookup)
        List<ProductLot> activeLots = productLotRepository.findActiveLotsByWarehouseId(filterWhId);
        if (activeLots == null) {
            activeLots = Collections.emptyList();
        }
        Map<String, List<ProductLot>> lotsByProductAndWarehouse = activeLots.stream()
                .filter(l -> l.getProduct() != null && l.getWarehouse() != null && l.getQuantity() != null && l.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(l -> l.getProduct().getId() + "_" + l.getWarehouse().getId()));

        List<Object[]> movementRows = stockMovementRepository.findLatestInboundMovementDates(filterWhId);
        Map<String, LocalDateTime> latestInboundDates = new HashMap<>();
        if (movementRows != null) {
            for (Object[] row : movementRows) {
                if (row != null && row.length >= 3 && row[0] != null && row[1] != null && row[2] != null) {
                    Long whId = (Long) row[0];
                    Long prodId = (Long) row[1];
                    LocalDateTime dt = (LocalDateTime) row[2];
                    latestInboundDates.put(whId + "_" + prodId, dt);
                }
            }
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

            // Tính giá vốn và tổng giá trị tồn (TC807-13: Ẩn với NV kho / Kế toán)
            BigDecimal costPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
            BigDecimal totalValue = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

            // Thuật toán tính số ngày lưu kho bình quân (Aging calculation)
            String pwKey = warehouse.getId() + "_" + product.getId();
            List<ProductLot> prodLots = lotsByProductAndWarehouse.get(product.getId() + "_" + warehouse.getId());
            LocalDateTime latestInbound = latestInboundDates.get(pwKey);

            long avgDays = calculateAverageAgingDays(product, warehouse, inv, today, prodLots, latestInbound);

            String bracket = determineAgingBracket(avgDays);
            String speed = determineTurnoverSpeed(avgDays);

            // Lọc theo nhóm tuổi hàng nếu có
            if (StringUtils.hasText(validatedBracket) && !"ALL".equalsIgnoreCase(validatedBracket)) {
                if (!validatedBracket.equalsIgnoreCase(bracket)) {
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
                    .costPrice(canViewCost ? costPrice.setScale(2, RoundingMode.HALF_UP) : null)
                    .totalValue(canViewCost ? totalValue : null)
                    .averageAgingDays(avgDays)
                    .agingBracket(bracket)
                    .turnoverSpeed(speed)
                    .lastMovementDate(lastMovement)
                    .build());
        }

        // Sắp xếp mặt hàng có số ngày tồn lâu nhất lên đầu để dễ nhận diện hàng đọng vốn
        items.sort(Comparator.comparing(InventoryAgingItemResponse::getAverageAgingDays).reversed()
                .thenComparing(item -> item.getTotalValue() != null ? item.getTotalValue() : BigDecimal.ZERO, Comparator.reverseOrder()));

        // 2. Tính toán tổng hợp Summary
        InventoryAgingSummaryResponse summary = calculateSummary(items, canViewCost);

        return InventoryAgingReportResponse.builder()
                .items(items)
                .summary(summary)
                .build();
    }

    public long calculateAverageAgingDays(Product product, Warehouse warehouse, Inventory inventory, LocalDate today) {
        return calculateAverageAgingDays(product, warehouse, inventory, today, null, null);
    }

    /**
     * Thuật toán tính số ngày lưu kho bình quân của sản phẩm tại kho.
     * - TC807-02: Ưu tiên tính bình quân gia quyền theo ngày NHẬP KHO thực tế (lot.createdAt).
     * - TC807-03: Nếu không có lô hoặc không quản lý lô, tính theo đợt nhập gần nhất (StockMovement quantity > 0).
     */
    public long calculateAverageAgingDays(Product product, Warehouse warehouse, Inventory inventory, LocalDate today,
                                          List<ProductLot> cachedLots, LocalDateTime cachedLatestInbound) {
        List<ProductLot> lots = cachedLots;
        if (lots == null) {
            lots = productLotRepository.findByProduct_IdAndWarehouse_Id(product.getId(), warehouse.getId());
        }

        List<ProductLot> activeLots = lots.stream()
                .filter(l -> l.getQuantity() != null && l.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (!activeLots.isEmpty()) {
            BigDecimal totalWeightedDays = BigDecimal.ZERO;
            BigDecimal totalLotQty = BigDecimal.ZERO;

            for (ProductLot lot : activeLots) {
                // TC807-02: Tuổi hàng tính từ ngày NHẬP KHO thực tế (createdAt của lô)
                LocalDate refDate = null;
                if (lot.getCreatedAt() != null) {
                    refDate = lot.getCreatedAt().toLocalDate();
                } else if (lot.getManufactureDate() != null) {
                    refDate = lot.getManufactureDate();
                } else {
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

        // TC807-03: SKU không quản lý lô hoặc không còn lô active:
        // Tính theo đợt nhập lại mới nhất (StockMovement quantity > 0)
        LocalDate refDate = null;
        if (cachedLatestInbound != null) {
            refDate = cachedLatestInbound.toLocalDate();
        } else {
            // Thử tìm đợt nhập gần nhất nếu chưa cache
            List<Object[]> rows = stockMovementRepository.findLatestInboundMovementDates(warehouse.getId());
            if (rows != null) {
                for (Object[] r : rows) {
                    if (r != null && r.length >= 3 && warehouse.getId().equals(r[0]) && product.getId().equals(r[1]) && r[2] != null) {
                        refDate = ((LocalDateTime) r[2]).toLocalDate();
                        break;
                    }
                }
            }
        }

        if (refDate == null) {
            if (inventory.getUpdatedAt() != null && inventory.getCreatedAt() != null && !inventory.getUpdatedAt().equals(inventory.getCreatedAt())) {
                refDate = inventory.getUpdatedAt().toLocalDate();
            } else if (inventory.getCreatedAt() != null) {
                refDate = inventory.getCreatedAt().toLocalDate();
            } else if (product.getCreatedAt() != null) {
                refDate = product.getCreatedAt().toLocalDate();
            } else {
                refDate = today;
            }
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

    private InventoryAgingSummaryResponse calculateSummary(List<InventoryAgingItemResponse> items, boolean canViewCost) {
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal stagnantValue = BigDecimal.ZERO;
        int slowMovingCount = 0;
        BigDecimal totalWeightedDays = BigDecimal.ZERO;
        BigDecimal totalQtyForWeight = BigDecimal.ZERO;

        for (InventoryAgingItemResponse item : items) {
            if (canViewCost && item.getTotalValue() != null) {
                totalValue = totalValue.add(item.getTotalValue());
            }

            if (item.getAverageAgingDays() > 60) {
                slowMovingCount++;
            }

            if (item.getAverageAgingDays() > 90) {
                if (canViewCost && item.getTotalValue() != null) {
                    stagnantValue = stagnantValue.add(item.getTotalValue());
                }
            }

            if (item.getQuantity() != null) {
                totalWeightedDays = totalWeightedDays.add(item.getQuantity().multiply(BigDecimal.valueOf(item.getAverageAgingDays())));
                totalQtyForWeight = totalQtyForWeight.add(item.getQuantity());
            }
        }

        double slowRatio = items.isEmpty() ? 0.0 :
                Math.round(((double) slowMovingCount / items.size() * 100.0) * 10.0) / 10.0;

        double avgSystemDays = totalQtyForWeight.compareTo(BigDecimal.ZERO) > 0 ?
                Math.round((totalWeightedDays.divide(totalQtyForWeight, 1, RoundingMode.HALF_UP).doubleValue()) * 10.0) / 10.0 : 0.0;

        return InventoryAgingSummaryResponse.builder()
                .totalInventoryValue(canViewCost ? totalValue.setScale(2, RoundingMode.HALF_UP) : null)
                .slowMovingCount(slowMovingCount)
                .slowMovingRatio(slowRatio)
                .stagnantValue(canViewCost ? stagnantValue.setScale(2, RoundingMode.HALF_UP) : null)
                .averageSystemDays(avgSystemDays)
                .build();
    }

    public List<InventoryAgingWarehouseValueResponse> getSummaryByWarehouse() {
        return getSummaryByWarehouse(null);
    }

    /**
     * Thống kê tổng giá trị tồn theo từng kho hàng.
     */
    @Transactional(readOnly = true)
    public List<InventoryAgingWarehouseValueResponse> getSummaryByWarehouse(UserDetailsImpl actor) {
        boolean canViewCost = canViewCostPrice(actor);
        Long allowedWhId = resolveAndValidateWarehouseId(null, actor);

        List<Inventory> all;
        if (allowedWhId != null) {
            all = inventoryRepository.findByWarehouse_Id(allowedWhId);
        } else {
            all = inventoryRepository.findAll();
        }

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
                    .totalValue(canViewCost ? totalVal.setScale(2, RoundingMode.HALF_UP) : null)
                    .build());
        }

        result.sort(Comparator.comparing(
                r -> r.getTotalValue() != null ? r.getTotalValue() : BigDecimal.ZERO,
                Comparator.reverseOrder()));
        return result;
    }

    public List<InventoryAgingCategoryValueResponse> getSummaryByCategory() {
        return getSummaryByCategory(null);
    }

    /**
     * Thống kê tổng giá trị tồn theo từng nhóm hàng (Category).
     */
    @Transactional(readOnly = true)
    public List<InventoryAgingCategoryValueResponse> getSummaryByCategory(UserDetailsImpl actor) {
        boolean canViewCost = canViewCostPrice(actor);
        Long allowedWhId = resolveAndValidateWarehouseId(null, actor);

        List<Inventory> all;
        if (allowedWhId != null) {
            all = inventoryRepository.findByWarehouse_Id(allowedWhId);
        } else {
            all = inventoryRepository.findAll();
        }

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
                BigDecimal cost = inv.getProduct() != null && inv.getProduct().getCostPrice() != null ? inv.getProduct().getCostPrice() : BigDecimal.ZERO;
                totalQty = totalQty.add(q);
                totalVal = totalVal.add(q.multiply(cost));
            }

            result.add(InventoryAgingCategoryValueResponse.builder()
                    .categoryId(catId)
                    .categoryCode(catCode)
                    .categoryName(catName)
                    .skuCount(invs.size())
                    .totalQuantity(totalQty.setScale(2, RoundingMode.HALF_UP))
                    .totalValue(canViewCost ? totalVal.setScale(2, RoundingMode.HALF_UP) : null)
                    .build());
        }

        result.sort(Comparator.comparing(
                r -> r.getTotalValue() != null ? r.getTotalValue() : BigDecimal.ZERO,
                Comparator.reverseOrder()));
        return result;
    }

    public byte[] exportToExcel(String warehouseId, String categoryId, String agingBracket, String keyword) throws IOException {
        return exportToExcel(warehouseId, categoryId, agingBracket, keyword, null);
    }

    /**
     * Xuất file Excel báo cáo luân chuyển kho (.xlsx) chuẩn Apache POI.
     * TC807-13: Ẩn giá vốn đối với nhân viên kho / kế toán.
     */
    @Transactional(readOnly = true)
    public byte[] exportToExcel(String warehouseId, String categoryId, String agingBracket, String keyword, UserDetailsImpl actor) throws IOException {
        InventoryAgingReportResponse report = getAgingReport(warehouseId, categoryId, agingBracket, keyword, actor);
        List<InventoryAgingItemResponse> items = report.getItems();
        boolean canViewCost = canViewCostPrice(actor);

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

            CellStyle centerStyle = workbook.createCellStyle();
            centerStyle.setAlignment(HorizontalAlignment.CENTER);

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
                Cell valCell = row.createCell(8);

                if (canViewCost && item.getCostPrice() != null && item.getTotalValue() != null) {
                    costCell.setCellValue(item.getCostPrice().doubleValue());
                    costCell.setCellStyle(currencyStyle);

                    valCell.setCellValue(item.getTotalValue().doubleValue());
                    valCell.setCellStyle(currencyStyle);
                } else {
                    costCell.setCellValue("---");
                    costCell.setCellStyle(centerStyle);

                    valCell.setCellValue("---");
                    valCell.setCellStyle(centerStyle);
                }

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

    /**
     * TC807-13: Quyền xem giá vốn hàng hóa.
     * Thủ kho (ROLE_WAREHOUSE) và Kế toán (ROLE_ACCOUNTANT) không được xem giá vốn và tổng giá trị tồn kho.
     */
    public boolean canViewCostPrice(UserDetailsImpl actor) {
        if (actor == null) {
            return true;
        }
        boolean isWhStaff = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_WAREHOUSE"));
        boolean isAccountant = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ACCOUNTANT"));
        boolean isPrivileged = actor.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ADMIN") ||
                a.getAuthority().equals("ROLE_SALES_MANAGER") ||
                a.getAuthority().equals("ROLE_WH_MANAGER"));

        if (isWhStaff || isAccountant) {
            return isPrivileged;
        }
        return true;
    }

    /**
     * TC807-14: Kiểm tra và ràng buộc quyền truy cập theo kho (IDOR prevention).
     */
    private Long resolveAndValidateWarehouseId(Long filterWhId, UserDetailsImpl actor) {
        if (actor == null) {
            return filterWhId;
        }
        boolean isWarehouseStaff = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_WAREHOUSE"));
        boolean isWhManager = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_WH_MANAGER"));
        boolean isAdmin = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if (isWarehouseStaff && !isAdmin) {
            User user = userRepository.findById(actor.getId()).orElse(null);
            Set<Warehouse> userWhs = user != null && user.getWarehouses() != null ? user.getWarehouses() : Collections.emptySet();
            if (userWhs.isEmpty()) {
                throw BusinessException.forbidden("ACCESS_DENIED", "Tài khoản nhân viên kho chưa được gán kho quản lý");
            }
            Set<Long> assignedIds = userWhs.stream().map(Warehouse::getId).collect(Collectors.toSet());
            if (filterWhId != null && !assignedIds.contains(filterWhId)) {
                throw BusinessException.forbidden("ACCESS_DENIED", "Bạn không có quyền truy cập dữ liệu của kho hàng này");
            }
            if (filterWhId == null) {
                return userWhs.iterator().next().getId();
            }
            return filterWhId;
        }

        if (isWhManager && !isAdmin) {
            User user = userRepository.findById(actor.getId()).orElse(null);
            Set<Warehouse> userWhs = user != null && user.getWarehouses() != null ? user.getWarehouses() : Collections.emptySet();
            if (!userWhs.isEmpty()) {
                Set<Long> assignedIds = userWhs.stream().map(Warehouse::getId).collect(Collectors.toSet());
                if (filterWhId != null && !assignedIds.contains(filterWhId)) {
                    throw BusinessException.forbidden("ACCESS_DENIED", "Bạn không có quyền truy cập dữ liệu của kho hàng này");
                }
            }
        }

        return filterWhId;
    }

    /**
     * TC807-11: Validate tham số kiểu số nguyên dương, ném 400 Bad Request nếu định dạng sai.
     */
    private Long parseAndValidateLong(String str, String fieldName) {
        if (!StringUtils.hasText(str) || "ALL".equalsIgnoreCase(str.trim())) {
            return null;
        }
        try {
            return Long.parseLong(str.trim());
        } catch (NumberFormatException e) {
            throw BusinessException.badRequest("INVALID_PARAM", fieldName + " không hợp lệ: " + str);
        }
    }

    /**
     * TC807-11: Validate nhóm tuổi hàng hợp lệ.
     */
    private String validateAgingBracket(String bracket) {
        if (!StringUtils.hasText(bracket) || "ALL".equalsIgnoreCase(bracket.trim())) {
            return "ALL";
        }
        String b = bracket.trim().toUpperCase();
        if (!VALID_AGING_BRACKETS.contains(b)) {
            throw BusinessException.badRequest("INVALID_PARAM", "Nhóm tuổi hàng không hợp lệ: " + bracket);
        }
        return b;
    }
}
