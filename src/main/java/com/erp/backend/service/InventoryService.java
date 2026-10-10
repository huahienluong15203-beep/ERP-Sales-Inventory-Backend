package com.erp.backend.service;

import com.erp.backend.dto.inventory.StockInfoDto;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;

    /**
     * S4-03 AC1: Xác định kho hàng phục vụ đại lý theo địa bàn / khu vực hoạt động.
     * Thứ tự ưu tiên:
     * 1. Mã/Tên khu vực (MB, MT, MN)
     * 2. Địa chỉ trụ sở đại lý
     * 3. Kho mặc định hoạt động đầu tiên trong hệ thống
     */
    @Transactional(readOnly = true)
    public Warehouse resolveWarehouseForCustomer(Customer customer) {
        if (customer == null) {
            return getDefaultWarehouse();
        }

        String regionCode = customer.getRegion() != null ? customer.getRegion().getCode() : "";
        String regionName = customer.getRegion() != null ? customer.getRegion().getName() : "";
        String address = customer.getAddress() != null ? customer.getAddress() : "";
        String text = (regionCode + " " + regionName + " " + address).toLowerCase();

        // 1. Miền Trung
        if (text.contains("mt") || text.contains("trung") || text.contains("đà nẵng") || text.contains("huế") || text.contains("quảng")) {
            Optional<Warehouse> wh = warehouseRepository.findByCodeIgnoreCase("WH-MT01")
                    .or(() -> findFirstActiveWarehouseMatching("MT", "Trung"));
            if (wh.isPresent()) return wh.get();
        }

        // 2. Miền Nam
        if (text.contains("mn") || text.contains("nam") || text.contains("hồ chí minh") || text.contains("hcm")
                || text.contains("sài gòn") || text.contains("bình dương") || text.contains("đồng nai") || text.contains("cần thơ")) {
            Optional<Warehouse> wh = warehouseRepository.findByCodeIgnoreCase("WH-MN01")
                    .or(() -> warehouseRepository.findByCodeIgnoreCase("KHO-HCM"))
                    .or(() -> findFirstActiveWarehouseMatching("MN", "Nam", "TP.HCM"));
            if (wh.isPresent()) return wh.get();
        }

        // 3. Miền Bắc (mặc định)
        Optional<Warehouse> northWh = warehouseRepository.findByCodeIgnoreCase("WH-MB01")
                .or(() -> warehouseRepository.findByCodeIgnoreCase("KHO-HN"))
                .or(() -> findFirstActiveWarehouseMatching("MB", "Bắc", "Hà Nội"));

        return northWh.orElseGet(this::getDefaultWarehouse);
    }

    private Optional<Warehouse> findFirstActiveWarehouseMatching(String... keywords) {
        List<Warehouse> activeWarehouses = warehouseRepository.findByStatusOrderByNameAsc("ACTIVE");
        for (Warehouse w : activeWarehouses) {
            String combined = (w.getCode() + " " + w.getName()).toLowerCase();
            for (String kw : keywords) {
                if (combined.contains(kw.toLowerCase())) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    private Warehouse getDefaultWarehouse() {
        return warehouseRepository.findByStatusOrderByNameAsc("ACTIVE").stream()
                .findFirst()
                .orElseGet(() -> warehouseRepository.findAll().stream().findFirst()
                        .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho hàng nào trong hệ thống")));
    }

    /**
     * S4-03 AC2: Tồn khả dụng = Tồn thực tế - Tồn đang giữ chỗ cho đơn khác.
     */
    @Transactional(readOnly = true)
    public StockInfoDto getStockInfo(Warehouse warehouse, Product product) {
        if (warehouse == null || product == null || warehouse.getId() == null || product.getId() == null) {
            return StockInfoDto.of(
                    warehouse != null ? warehouse.getCode() : "WH-MB01",
                    warehouse != null ? warehouse.getName() : "Kho Tổng Miền Bắc",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            );
        }

        Optional<Inventory> opt = inventoryRepository.findByWarehouse_IdAndProduct_Id(warehouse.getId(), product.getId());
        if (opt.isPresent()) {
            Inventory inv = opt.get();
            return StockInfoDto.of(
                    warehouse.getCode(),
                    warehouse.getName(),
                    inv.getPhysicalStock(),
                    inv.getReservedStock(),
                    inv.getAvailableStock()
            );
        }

        return StockInfoDto.of(warehouse.getCode(), warehouse.getName(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * S4-03 AC3 & AC4: Giữ chỗ hàng tồn kho khi chốt đơn hàng (SUBMIT).
     * Áp dụng KHÓA BI QUAN (Pessimistic Write Lock) trên từng dòng tồn kho.
     * Sắp xếp theo productId để chống Deadlock giữa 2 giao dịch đồng thời.
     */
    @Transactional
    public void checkAndReserveStock(SalesOrder order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }

        Warehouse warehouse = resolveWarehouseForCustomer(order.getCustomer());

        // Sắp xếp các dòng hàng theo Product ID tăng dần để ngăn ngừa Deadlock đa bảng
        List<SalesOrderLine> sortedLines = new ArrayList<>(order.getLines());
        sortedLines.sort(Comparator.comparing(l -> l.getProduct().getId()));

        for (SalesOrderLine line : sortedLines) {
            Product product = line.getProduct();
            BigDecimal baseQty = line.getBaseQuantity() != null
                    ? line.getBaseQuantity()
                    : line.getQuantity().multiply(line.getConversionFactor()).setScale(4, RoundingMode.HALF_UP);

            // Khoá bi quan dòng tồn kho của mặt hàng tại kho này
            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElse(null);

            BigDecimal available = inventory != null ? inventory.getAvailableStock() : BigDecimal.ZERO;
            if (inventory == null || baseQty.compareTo(available) > 0) {
                BigDecimal factor = line.getConversionFactor() != null && line.getConversionFactor().signum() > 0
                        ? line.getConversionFactor()
                        : BigDecimal.ONE;
                BigDecimal maxAllowedInUnit = available.compareTo(BigDecimal.ZERO) > 0
                        ? available.divide(factor, 0, RoundingMode.FLOOR)
                        : BigDecimal.ZERO;

                throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                        String.format("Mặt hàng '%s' (%s) tại %s không đủ tồn khả dụng (còn %s %s, yêu cầu %s %s). Vui lòng điều chỉnh lại số lượng.",
                                line.getProductName(),
                                line.getProductSku(),
                                warehouse.getName(),
                                maxAllowedInUnit.toPlainString(),
                                line.getUnitName(),
                                line.getQuantity().stripTrailingZeros().toPlainString(),
                                line.getUnitName()),
                        "lines");
            }

            // Tăng lượng giữ chỗ tương ứng
            inventory.setReservedStock(inventory.getReservedStock().add(baseQty));
            inventoryRepository.save(inventory);
            log.info("S4-03: Đã giữ chỗ {} {} (tổng giữ: {}) cho SKU {} tại kho {}",
                    baseQty, product.getBaseUnit(), inventory.getReservedStock(), product.getSku(), warehouse.getCode());
        }
    }

    /**
     * S4-03: Giải phóng hàng giữ chỗ khi đơn hàng bị Từ chối (REJECTED), Trả lại sửa (RETURNED TO DRAFT), hoặc Hủy.
     */
    @Transactional
    public void releaseReservedStock(SalesOrder order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }

        Warehouse warehouse = resolveWarehouseForCustomer(order.getCustomer());

        List<SalesOrderLine> sortedLines = new ArrayList<>(order.getLines());
        sortedLines.sort(Comparator.comparing(l -> l.getProduct().getId()));

        for (SalesOrderLine line : sortedLines) {
            Product product = line.getProduct();
            BigDecimal baseQty = line.getBaseQuantity() != null
                    ? line.getBaseQuantity()
                    : line.getQuantity().multiply(line.getConversionFactor()).setScale(4, RoundingMode.HALF_UP);

            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElse(null);

            if (inventory != null) {
                BigDecimal currentReserved = inventory.getReservedStock() != null ? inventory.getReservedStock() : BigDecimal.ZERO;
                BigDecimal updated = currentReserved.subtract(baseQty);
                if (updated.compareTo(BigDecimal.ZERO) < 0) {
                    updated = BigDecimal.ZERO;
                }
                inventory.setReservedStock(updated);
                inventoryRepository.save(inventory);
                log.info("S4-03: Đã giải phóng giữ chỗ {} {} (còn giữ: {}) cho SKU {} tại kho {}",
                        baseQty, product.getBaseUnit(), updated, product.getSku(), warehouse.getCode());
            }
        }
    }

    /**
     * S4-06: Xuất kho đơn hàng (DISPATCHED).
     * Trừ lượng tồn thực tế (physicalStock) và giải phóng lượng tồn đang giữ chỗ (reservedStock).
     */
    @Transactional
    public void dispatchReservedStock(SalesOrder order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }

        Warehouse warehouse = resolveWarehouseForCustomer(order.getCustomer());

        List<SalesOrderLine> sortedLines = new ArrayList<>(order.getLines());
        sortedLines.sort(Comparator.comparing(l -> l.getProduct().getId()));

        for (SalesOrderLine line : sortedLines) {
            Product product = line.getProduct();
            BigDecimal baseQty = line.getBaseQuantity() != null
                    ? line.getBaseQuantity()
                    : line.getQuantity().multiply(line.getConversionFactor()).setScale(4, RoundingMode.HALF_UP);

            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElse(null);

            if (inventory != null) {
                // Trừ tồn thực tế
                BigDecimal currentPhysical = inventory.getPhysicalStock() != null ? inventory.getPhysicalStock() : BigDecimal.ZERO;
                BigDecimal updatedPhysical = currentPhysical.subtract(baseQty);
                if (updatedPhysical.compareTo(BigDecimal.ZERO) < 0) {
                    updatedPhysical = BigDecimal.ZERO;
                }
                inventory.setPhysicalStock(updatedPhysical);

                // Giải phóng giữ chỗ
                BigDecimal currentReserved = inventory.getReservedStock() != null ? inventory.getReservedStock() : BigDecimal.ZERO;
                BigDecimal updatedReserved = currentReserved.subtract(baseQty);
                if (updatedReserved.compareTo(BigDecimal.ZERO) < 0) {
                    updatedReserved = BigDecimal.ZERO;
                }
                inventory.setReservedStock(updatedReserved);

                inventoryRepository.save(inventory);
                log.info("S4-06: Đã xuất kho đơn hàng {}: trừ tồn thực tế {} {} (còn {}), giải phóng giữ chỗ (còn {}) cho SKU {} tại kho {}",
                        order.getCode(), baseQty, product.getBaseUnit(), updatedPhysical, updatedReserved, product.getSku(), warehouse.getCode());
            }
        }
    }
}
