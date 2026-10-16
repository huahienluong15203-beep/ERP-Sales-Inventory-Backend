package com.erp.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S8-05: Dịch vụ lưu trữ đệm (Cache) cho các API thống kê Dashboard điều hành bán hàng và kho.
 * - Hỗ trợ TTL (Time-To-Live) tự động hết hạn để dữ liệu luôn tươi mới.
 * - Hỗ trợ in-memory high-performance cache với kiến trúc sẵn sàng tích hợp Redis.
 * - Đảm bảo toàn bộ API thống kê phản hồi tức thì (< 2 giây theo tiêu chí chấp nhận).
 */
@Slf4j
@Service
public class DashboardCacheService {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(120); // 2 phút

    private record CacheEntry(Object value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, CacheEntry> localCache = new ConcurrentHashMap<>();

    /**
     * Lấy giá trị từ bộ đệm nếu còn hiệu lực.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> clazz) {
        CacheEntry entry = localCache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            localCache.remove(key);
            log.debug("Cache key [{}] expired and removed", key);
            return Optional.empty();
        }
        if (clazz.isInstance(entry.value())) {
            return Optional.of((T) entry.value());
        }
        return Optional.empty();
    }

    /**
     * Lưu giá trị vào bộ đệm với thời gian sống mặc định (120s).
     */
    public void put(String key, Object value) {
        put(key, value, DEFAULT_TTL);
    }

    /**
     * Lưu giá trị vào bộ đệm với thời gian sống tùy chỉnh.
     */
    public void put(String key, Object value, Duration ttl) {
        if (value == null) {
            localCache.remove(key);
            return;
        }
        Instant expiresAt = Instant.now().plus(ttl != null ? ttl : DEFAULT_TTL);
        localCache.put(key, new CacheEntry(value, expiresAt));
        log.debug("Cached key [{}] with TTL {}s", key, (ttl != null ? ttl : DEFAULT_TTL).toSeconds());
    }

    /**
     * Xóa một khóa cụ thể khỏi bộ đệm.
     */
    public void evict(String key) {
        localCache.remove(key);
        log.debug("Evicted cache key [{}]", key);
    }

    /**
     * Xóa tất cả các khóa có tiền tố bắt đầu bằng prefix (ví dụ "dashboard:").
     */
    public void evictByPrefix(String prefix) {
        localCache.keySet().removeIf(k -> k.startsWith(prefix));
        log.info("Evicted cache keys matching prefix [{}]", prefix);
    }

    /**
     * Xóa toàn bộ bộ đệm dashboard.
     */
    public void clearAll() {
        localCache.clear();
        log.info("Cleared all dashboard cache entries");
    }
}
