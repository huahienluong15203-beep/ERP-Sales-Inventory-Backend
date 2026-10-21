package com.erp.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditLogInterceptorTest {

    @Test
    void previewEndpointsAreReadOnly() {
        assertThat(AuditLogInterceptor.isReadOnlyPost("/api/orders/preview")).isTrue();
        assertThat(AuditLogInterceptor.isReadOnlyPost("/api/orders/preview/")).isTrue();
        assertThat(AuditLogInterceptor.isReadOnlyPost("/api/admin/products/import/preview")).isTrue();
        // S5-02: xem trước đặt lại đơn cũ chỉ tính thử
        assertThat(AuditLogInterceptor.isReadOnlyPost("/api/portal/orders/300/reorder-preview")).isTrue();
    }

    @Test
    void realChangesAreStillAudited() {
        assertThat(AuditLogInterceptor.isReadOnlyPost("/api/orders/drafts")).isFalse();
        assertThat(AuditLogInterceptor.isReadOnlyPost("/api/orders/12")).isFalse();
        assertThat(AuditLogInterceptor.isReadOnlyPost("/api/price-lists/3/items")).isFalse();
    }
}
