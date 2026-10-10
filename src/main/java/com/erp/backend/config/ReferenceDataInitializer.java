package com.erp.backend.config;

import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.Region;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.RegionRepository;
import com.erp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dữ liệu mẫu kho, địa bàn và tồn kho để test S1-09, S4-03 (chỉ tạo nếu chưa có).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceDataInitializer implements CommandLineRunner {

    private final WarehouseRepository warehouseRepository;
    private final RegionRepository regionRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final com.erp.backend.repository.MinStockThresholdRepository minStockThresholdRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        // Tự động kích hoạt extension unaccent trong PostgreSQL để hỗ trợ tìm kiếm tiếng Việt không dấu
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS unaccent;");
            log.info("PostgreSQL extension 'unaccent' đã được kích hoạt thành công.");
        } catch (Exception e) {
            log.warn("Không thể kích hoạt extension unaccent trên cơ sở dữ liệu: {}", e.getMessage());
        }

        // Kho chuẩn theo mã nghiệp vụ miền Bắc, Trung, Nam và kho cũ
        seedWarehouse("WH-MB01", "Kho Tổng Miền Bắc", "KCN Tiên Sơn, Bắc Ninh");
        seedWarehouse("WH-MT01", "Kho Miền Trung", "KCN Hòa Khánh, Đà Nẵng");
        seedWarehouse("WH-MN01", "Kho Tổng Miền Nam", "KCN Tân Bình, TP.HCM");
        seedWarehouse("KHO-HN", "Kho Tổng Hà Nội", "KCN Tiên Sơn, Bắc Ninh");
        seedWarehouse("KHO-HCM", "Kho Tổng TP.HCM", "KCN Tân Bình, TP.HCM");

        seedRegion("MB", "Miền Bắc");
        seedRegion("MT", "Miền Trung");
        seedRegion("MN", "Miền Nam");

        // S4-03: Khởi tạo tồn kho mẫu cho các sản phẩm trong hệ thống
        seedInventories();

        // S5-09: Khởi tạo định mức tồn tối thiểu mẫu để test cảnh báo đứt hàng
        seedMinStockThresholds();
    }

    private void seedWarehouse(String code, String name, String address) {
        if (!warehouseRepository.existsByCode(code)) {
            warehouseRepository.save(Warehouse.builder().code(code).name(name).address(address).build());
        }
    }

    private void seedRegion(String code, String name) {
        if (!regionRepository.existsByCode(code)) {
            regionRepository.save(Region.builder().code(code).name(name).build());
        }
    }

    private void seedInventories() {
        List<Warehouse> warehouses = warehouseRepository.findAll();
        List<Product> products = productRepository.findAll();

        for (Warehouse wh : warehouses) {
            for (Product p : products) {
                if (inventoryRepository.findByWarehouseAndProduct(wh, p).isEmpty()) {
                    String sku = p.getSku() != null ? p.getSku().toUpperCase() : "";
                    BigDecimal physical;
                    BigDecimal reserved;

                    if (sku.contains("HEINEKEN")) {
                        // Tồn ít (khả dụng 10) để test 2 người chốt đơn đồng thời & test chặn vượt tồn
                        physical = new BigDecimal("15");
                        reserved = new BigDecimal("5");
                    } else if (sku.contains("PEPSI")) {
                        // Hết hàng (khả dụng 0) để test chặn ngay lập tức
                        physical = new BigDecimal("20");
                        reserved = new BigDecimal("20");
                    } else if (sku.contains("SAIGON")) {
                        // Khả dụng 500
                        physical = new BigDecimal("550");
                        reserved = new BigDecimal("50");
                    } else {
                        // Tồn dồi dào
                        physical = new BigDecimal("1000");
                        reserved = BigDecimal.ZERO;
                    }

                    inventoryRepository.save(Inventory.builder()
                            .warehouse(wh)
                            .product(p)
                            .physicalStock(physical)
                            .reservedStock(reserved)
                            .build());
                }
            }
        }
    }

    private void seedMinStockThresholds() {
        if (minStockThresholdRepository.count() > 0) {
            return;
        }

        List<Warehouse> warehouses = warehouseRepository.findAll();
        List<Product> products = productRepository.findAll();

        for (Warehouse wh : warehouses) {
            for (Product p : products) {
                String sku = p.getSku() != null ? p.getSku().toUpperCase() : "";
                BigDecimal threshold = null;

                if (sku.contains("PEPSI")) {
                    threshold = new BigDecimal("100"); // Tồn 20 < 100 -> CRITICAL
                } else if (sku.contains("HEINEKEN")) {
                    threshold = new BigDecimal("50");  // Tồn 15 < 50 -> CRITICAL
                } else if (sku.contains("SAIGON")) {
                    threshold = new BigDecimal("400"); // Tồn 550 >= 400 -> SAFE
                }

                if (threshold != null) {
                    minStockThresholdRepository.save(com.erp.backend.entity.MinStockThreshold.builder()
                            .warehouse(wh)
                            .product(p)
                            .minThreshold(threshold)
                            .build());
                }
            }
        }
    }
}
