package com.erp.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hibernate tạo CHECK (module in ('INVENTORY', ...)) cho cột enum audit_logs.module lúc tạo bảng, nhưng với
 * ddl-auto=update thì không cập nhật lại khi thêm phân hệ mới (vd ORDER) -> ghi nhật ký phân hệ mới sẽ lỗi.
 * Lúc khởi động bỏ ràng buộc cũ đó (giá trị cột vẫn do enum AuditModule ở code kiểm soát). Chỉ chạy với PostgreSQL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditModuleConstraintInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String product = jdbcTemplate.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            if (product == null || !product.toLowerCase().contains("postgres")) {
                return;
            }
            Boolean tableExists = jdbcTemplate.queryForObject(
                    "select to_regclass('audit_logs') is not null", Boolean.class);
            if (!Boolean.TRUE.equals(tableExists)) {
                return;
            }
            List<String> names = jdbcTemplate.queryForList(
                    "select conname from pg_constraint where conrelid = 'audit_logs'::regclass and contype = 'c' "
                            + "and pg_get_constraintdef(oid) ilike '%module%'", String.class);
            for (String name : names) {
                jdbcTemplate.execute("alter table audit_logs drop constraint \"" + name.replace("\"", "") + "\"");
                log.info("Đã bỏ ràng buộc {} trên audit_logs.module để ghi được phân hệ mới (ORDER)", name);
            }
        } catch (DataAccessException e) {
            log.warn("Chưa cập nhật được ràng buộc audit_logs.module: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
