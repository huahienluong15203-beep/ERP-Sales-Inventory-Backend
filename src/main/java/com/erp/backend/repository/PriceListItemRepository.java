package com.erp.backend.repository;

import com.erp.backend.entity.CustomerGroup;
import com.erp.backend.entity.PriceListItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {

    /**
     * S2-10: Tìm dòng giá đang áp dụng cho nhóm khách hàng tại một ngày.
     * Nếu nhiều bảng giá cùng hiệu lực thì lấy bảng có ngày bắt đầu mới nhất, rồi phiên bản cao nhất.
     */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p "
            + "WHERE p.status = 'ACTIVE' AND p.customerGroup = :customerGroup "
            + "AND p.startDate <= :date AND (p.endDate IS NULL OR p.endDate >= :date) "
            + "AND UPPER(i.productSku) = UPPER(:sku) "
            + "ORDER BY p.startDate DESC, p.version DESC, p.id DESC")
    List<PriceListItem> findEffective(@Param("customerGroup") CustomerGroup customerGroup,
                                      @Param("sku") String sku,
                                      @Param("date") LocalDate date,
                                      Pageable pageable);

    /**
     * Lấy các sản phẩm trong bảng giá, kèm lọc nhanh theo mã SKU hoặc tên sản phẩm.
     */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.product prod "
            + "WHERE i.priceList.id = :priceListId "
            + "AND (:keyword IS NULL OR :keyword = '' "
            + "     OR UPPER(i.productSku) LIKE UPPER(CONCAT('%', :keyword, '%')) "
            + "     OR UPPER(i.productName) LIKE UPPER(CONCAT('%', :keyword, '%'))) "
            + "ORDER BY i.productSku ASC")
    List<PriceListItem> findByPriceListIdAndKeyword(@Param("priceListId") Long priceListId,
                                                   @Param("keyword") String keyword,
                                                   Pageable pageable);
}
