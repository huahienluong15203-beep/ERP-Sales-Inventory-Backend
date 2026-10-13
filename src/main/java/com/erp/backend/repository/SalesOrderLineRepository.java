package com.erp.backend.repository;

import com.erp.backend.entity.SalesOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Set;

@Repository
public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, Long> {

    @Query("SELECT COUNT(l) FROM SalesOrderLine l WHERE l.priceList.id = :priceListId AND l.order.status IN :confirmedStatuses")
    long countByPriceListIdAndOrderStatuses(@Param("priceListId") Long priceListId,
                                            @Param("confirmedStatuses") Collection<String> confirmedStatuses);

    /** S3-01: chính sách chiết khấu đã được áp vào dòng đơn hàng nào chưa. */
    boolean existsByDiscountPolicyCodeIgnoreCase(String discountPolicyCode);

    @Modifying
    @Query("UPDATE SalesOrderLine l SET l.priceList = null WHERE l.priceList.id = :priceListId")
    void clearPriceListReference(@Param("priceListId") Long priceListId);

    @Query("SELECT DISTINCT l.priceList.id FROM SalesOrderLine l WHERE l.priceList.id IS NOT NULL AND l.order.status IN :confirmedStatuses")
    Set<Long> findPriceListIdsWithConfirmedOrders(@Param("confirmedStatuses") Collection<String> confirmedStatuses);
}
