package com.erp.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Quy tắc dự án: tồn kho không được âm (CHECK qty >= 0 ở DB, ngoài khoá dòng ở code).
 * Dự án dùng ddl-auto=update nên Hibernate không tự thêm CHECK cho bảng đã có -> thêm lúc khởi động nếu chưa có.
 * Dữ liệu cũ đang vi phạm thì chỉ cảnh báo, không làm sập ứng dụng. Chỉ chạy với PostgreSQL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryConstraintInitializer implements ApplicationRunner {

    static final String CONSTRAINT = "chk_inventory_non_negative";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String product = jdbcTemplate.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            if (product == null || !product.toLowerCase().contains("postgres")) {
                return;
            }
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from pg_constraint where conname = ?", Integer.class, CONSTRAINT);
            if (exists != null && exists > 0) {
                return;
            }
            Integer table = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.tables where table_name = 'inventories'", Integer.class);
            if (table == null || table == 0) {
                return;
            }
            jdbcTemplate.execute("alter table inventories add constraint " + CONSTRAINT
                    + " check (physical_stock >= 0 and reserved_stock >= 0)");
            log.info("Đã thêm ràng buộc {} (tồn thực tế, giữ chỗ không âm) cho bảng inventories", CONSTRAINT);
        } catch (DataAccessException e) {
            log.warn("Chưa thêm được ràng buộc tồn không âm ({}): {}", CONSTRAINT, e.getMostSpecificCause().getMessage());
        }
    }
}
