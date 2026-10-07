package com.erp.backend.service;

import com.erp.backend.entity.Product;

import java.math.BigDecimal;

/** S3-02: Một thay đổi giá vừa xảy ra trong bảng giá, dùng để ghi lịch sử. */
record PriceChange(Product product, String changeType,
                   BigDecimal oldPrice, BigDecimal newPrice,
                   BigDecimal oldFloorPrice, BigDecimal newFloorPrice) {
}
