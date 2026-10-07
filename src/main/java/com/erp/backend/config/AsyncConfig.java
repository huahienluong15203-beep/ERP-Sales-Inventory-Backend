package com.erp.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Bật xử lý bất đồng bộ (chạy ngầm) cho các tác vụ chậm như gửi email.
 * Các phương thức gắn @Async sẽ chạy trên bộ luồng mặc định Spring Boot tạo sẵn,
 * nhờ đó API trả kết quả ngay cho người dùng, không phải đợi máy chủ Gmail.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
