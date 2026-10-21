package com.erp.backend.config;

import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.Region;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.RegionRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dữ liệu mẫu kho, địa bàn và tồn kho để test (chỉ tạo nếu chưa có).
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
    private final UserRepository userRepository;
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

        // Khởi tạo tồn kho mẫu cho các sản phẩm trong hệ thống
        seedInventories();

        // Khởi tạo định mức tồn tối thiểu mẫu để test cảnh báo đứt hàng
        seedMinStockThresholds();

        // Gắn kho mẫu cho các tài khoản nhân sự kho nếu chưa được gán
        seedUserWarehouseAssignments();

        // Khởi tạo và đồng bộ dữ liệu mẫu luân chuyển kho & tuổi hàng (S8-07)
        seedAgingTestData();
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
        if (inventoryRepository.count() > 0) {
            return;
        }
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

    private void seedUserWarehouseAssignments() {
        try {
            List<Long> whIds = jdbcTemplate.query(
                    "SELECT id FROM warehouses WHERE UPPER(code) = 'WH-MB01' LIMIT 1",
                    (rs, rowNum) -> rs.getLong("id"));
            if (!whIds.isEmpty()) {
                Long whId = whIds.get(0);
                for (String username : List.of("wh_staff", "wh_manager")) {
                    List<Long> uIds = jdbcTemplate.query(
                            "SELECT id FROM users WHERE username = ?",
                            (rs, rowNum) -> rs.getLong("id"),
                            username);
                    if (!uIds.isEmpty()) {
                        Long uId = uIds.get(0);
                        Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM user_warehouses WHERE user_id = ?",
                                Integer.class,
                                uId);
                        if (count != null && count == 0) {
                            jdbcTemplate.update(
                                    "INSERT INTO user_warehouses (user_id, warehouse_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                                    uId,
                                    whId);
                            log.info("Đã gắn kho mặc định WH-MB01 cho tài khoản {}", username);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Không thể tự động gắn kho mặc định qua SQL: {}", e.getMessage());
        }
    }

    /**
     * Khởi tạo dữ liệu mẫu đa dạng cho báo cáo luân chuyển kho & tuổi hàng (S8-07)
     * Đảm bảo có đầy đủ các nhóm: Nhanh (<=30 ngày), Bình thường (31-60 ngày),
     * Chậm luân chuyển (61-90 ngày), và Chôn vốn / Vốn đọng nguy hiểm (>90 ngày).
     */
    private void seedAgingTestData() {
        try {
            // Cập nhật ngày tạo của một số lô hàng để tạo đa dạng nhóm tuổi hàng
            jdbcTemplate.update("UPDATE product_lots SET created_at = NOW() - INTERVAL '105 days', manufacture_date = CURRENT_DATE - INTERVAL '120 days' WHERE id = 5 OR (product_id = 3 AND warehouse_id = 1)");
            jdbcTemplate.update("UPDATE product_lots SET created_at = NOW() - INTERVAL '125 days', manufacture_date = CURRENT_DATE - INTERVAL '140 days' WHERE id = 3 OR (product_id = 2 AND warehouse_id = 4)");
            jdbcTemplate.update("UPDATE product_lots SET created_at = NOW() - INTERVAL '72 days', manufacture_date = CURRENT_DATE - INTERVAL '90 days' WHERE id = 6 OR (product_id = 3 AND warehouse_id = 3)");
            jdbcTemplate.update("UPDATE product_lots SET created_at = NOW() - INTERVAL '85 days', manufacture_date = CURRENT_DATE - INTERVAL '100 days' WHERE id = 8 OR (product_id = 6 AND warehouse_id = 4)");
            jdbcTemplate.update("UPDATE product_lots SET created_at = NOW() - INTERVAL '45 days', manufacture_date = CURRENT_DATE - INTERVAL '60 days' WHERE id = 1 OR (product_id = 2 AND warehouse_id = 2)");
            jdbcTemplate.update("UPDATE product_lots SET created_at = NOW() - INTERVAL '50 days', manufacture_date = CURRENT_DATE - INTERVAL '65 days' WHERE id = 4 OR (product_id = 3 AND warehouse_id = 5)");

            // Cập nhật tồn kho và thẻ kho cho các SKU không quản lý lô
            // 1. SP-COCA-330 (id=1) tại Kho Miền Trung (id=4): Chôn vốn (>90 ngày, 110 ngày)
            jdbcTemplate.update("UPDATE inventories SET created_at = NOW() - INTERVAL '110 days', updated_at = NOW() - INTERVAL '110 days' WHERE product_id = 1 AND warehouse_id = 4");
            jdbcTemplate.update("UPDATE stock_movements SET movement_at = NOW() - INTERVAL '110 days' WHERE product_id = 1 AND warehouse_id = 4");

            // 2. SP-AQUAFINA-500 (id=4) tại Kho Miền Trung (id=4): Chậm luân chuyển (61-90 ngày, 78 ngày)
            jdbcTemplate.update("UPDATE inventories SET created_at = NOW() - INTERVAL '78 days', updated_at = NOW() - INTERVAL '78 days' WHERE product_id = 4 AND warehouse_id = 4");
            jdbcTemplate.update("UPDATE stock_movements SET movement_at = NOW() - INTERVAL '78 days' WHERE product_id = 4 AND warehouse_id = 4");

            // 3. SP-RED-BULL-250 (id=5) tại Kho Tổng Hà Nội (id=1): Bình thường (31-60 ngày, 40 ngày)
            jdbcTemplate.update("UPDATE inventories SET created_at = NOW() - INTERVAL '40 days', updated_at = NOW() - INTERVAL '40 days' WHERE product_id = 5 AND warehouse_id = 1");
            jdbcTemplate.update("UPDATE stock_movements SET movement_at = NOW() - INTERVAL '40 days' WHERE product_id = 5 AND warehouse_id = 1");

            // 4. SP-PEPSI-330 (id=2) tại Kho Tổng Miền Bắc (id=3): Chậm luân chuyển (61-90 ngày, 68 ngày)
            jdbcTemplate.update("UPDATE inventories SET created_at = NOW() - INTERVAL '68 days', updated_at = NOW() - INTERVAL '68 days' WHERE product_id = 2 AND warehouse_id = 3");
            jdbcTemplate.update("UPDATE stock_movements SET movement_at = NOW() - INTERVAL '68 days' WHERE product_id = 2 AND warehouse_id = 3");

            log.info("Khởi tạo và đồng bộ dữ liệu mẫu luân chuyển kho & tuổi hàng (S8-07) thành công.");
        } catch (Exception e) {
            log.warn("Không thể seed dữ liệu aging test: {}", e.getMessage());
        }
    }
}
