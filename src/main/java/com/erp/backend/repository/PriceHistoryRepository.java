package com.erp.backend.repository;

import com.erp.backend.entity.PriceHistory;
import com.erp.backend.entity.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** S3-02: Chỉ dùng để ghi mới và tra cứu. Không có API sửa / xoá lịch sử giá. */
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long>, JpaSpecificationExecutor<PriceHistory> {
    void deleteByPriceList(PriceList priceList);
    void deleteByPriceListId(Long priceListId);
}
