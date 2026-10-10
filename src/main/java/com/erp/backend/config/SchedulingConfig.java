package com.erp.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** S5-06: Bật các tác vụ định kỳ (tự nhả giữ chỗ tồn của đơn quá hạn). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
