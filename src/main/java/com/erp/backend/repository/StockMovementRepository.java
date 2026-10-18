package com.erp.backend.repository;

import com.erp.backend.entity.StockMovement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** S6-02: Thẻ kho (chỉ thêm, không sửa / xoá). */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long>, JpaSpecificationExecutor<StockMovement> {

    /** Dòng thẻ kho gần nhất TRƯỚC mốc :before của 1 SKU tại 1 kho (lấy số dư đầu kỳ). Dùng PageRequest.of(0, 1). */
    @Query("select m from StockMovement m where m.warehouse.id = :warehouseId and m.product.id = :productId "
            + "and m.movementAt < :before order by m.movementAt desc, m.id desc")
    List<StockMovement> findLatestBefore(@Param("warehouseId") Long warehouseId,
                                         @Param("productId") Long productId,
                                         @Param("before") LocalDateTime before,
                                         Pageable pageable);

    /** Dòng thẻ kho gần nhất trong khoảng [from, to) (lấy số dư cuối kỳ). Dùng PageRequest.of(0, 1). */
    @Query("select m from StockMovement m where m.warehouse.id = :warehouseId and m.product.id = :productId "
            + "and m.movementAt >= :from and m.movementAt < :to order by m.movementAt desc, m.id desc")
    List<StockMovement> findLatestInRange(@Param("warehouseId") Long warehouseId,
                                          @Param("productId") Long productId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          Pageable pageable);

    /** Tổng nhập (số dương) trong khoảng [from, to). */
    @Query("select coalesce(sum(m.quantity), 0) from StockMovement m where m.warehouse.id = :warehouseId "
            + "and m.product.id = :productId and m.movementAt >= :from and m.movementAt < :to and m.quantity > 0")
    BigDecimal sumIn(@Param("warehouseId") Long warehouseId, @Param("productId") Long productId,
                     @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Tổng xuất (số âm) trong khoảng [from, to). */
    @Query("select coalesce(sum(m.quantity), 0) from StockMovement m where m.warehouse.id = :warehouseId "
            + "and m.product.id = :productId and m.movementAt >= :from and m.movementAt < :to and m.quantity < 0")
    BigDecimal sumOut(@Param("warehouseId") Long warehouseId, @Param("productId") Long productId,
                      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT sm.warehouse.id, sm.product.id, MAX(sm.movementAt) FROM StockMovement sm WHERE (:warehouseId IS NULL OR sm.warehouse.id = :warehouseId) AND sm.quantity > 0 GROUP BY sm.warehouse.id, sm.product.id")
    List<Object[]> findLatestInboundMovementDates(@Param("warehouseId") Long warehouseId);
}
