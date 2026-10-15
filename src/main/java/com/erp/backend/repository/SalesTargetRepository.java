package com.erp.backend.repository;

import com.erp.backend.entity.SalesTarget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * S8-04: Chỉ tiêu doanh số + tổng doanh số thực tế theo tháng.
 * Doanh số = tổng tiền các đơn đã duyệt trở đi (không tính nháp, chờ duyệt, huỷ, từ chối), tính theo tháng duyệt
 * (đơn cũ thiếu approvedAt thì lấy ngày tạo); gán cho NV kinh doanh / khu vực đang phụ trách đại lý.
 */
public interface SalesTargetRepository extends JpaRepository<SalesTarget, Long> {

    boolean existsByPeriodMonthAndTargetKey(LocalDate periodMonth, String targetKey);

    @Query("select t from SalesTarget t left join fetch t.salesRep left join fetch t.region "
            + "where t.periodMonth = :period order by t.targetType, t.id")
    List<SalesTarget> findByPeriod(@Param("period") LocalDate period);

    Optional<SalesTarget> findByPeriodMonthAndTargetKey(LocalDate periodMonth, String targetKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SalesTarget t where t.id = :id")
    Optional<SalesTarget> findByIdForUpdate(@Param("id") Long id);

    /** [repId, tổng doanh số] trong [from, to) của các NV kinh doanh. */
    @Query("select c.salesRep.id, coalesce(sum(o.totalAmount), 0) from SalesOrder o join o.customer c "
            + "where o.status in :statuses and coalesce(o.approvedAt, o.createdAt) >= :from "
            + "and coalesce(o.approvedAt, o.createdAt) < :to and c.salesRep.id in :repIds group by c.salesRep.id")
    List<Object[]> sumSalesByRep(@Param("statuses") Collection<String> statuses,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to,
                                 @Param("repIds") Collection<Long> repIds);

    /** [regionId, tổng doanh số] trong [from, to) của các khu vực. */
    @Query("select c.region.id, coalesce(sum(o.totalAmount), 0) from SalesOrder o join o.customer c "
            + "where o.status in :statuses and coalesce(o.approvedAt, o.createdAt) >= :from "
            + "and coalesce(o.approvedAt, o.createdAt) < :to and c.region.id in :regionIds group by c.region.id")
    List<Object[]> sumSalesByRegion(@Param("statuses") Collection<String> statuses,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("regionIds") Collection<Long> regionIds);

    /** Doanh số 1 NV kinh doanh trong [from, to). */
    @Query("select coalesce(sum(o.totalAmount), 0) from SalesOrder o join o.customer c "
            + "where o.status in :statuses and coalesce(o.approvedAt, o.createdAt) >= :from "
            + "and coalesce(o.approvedAt, o.createdAt) < :to and c.salesRep.id = :repId")
    BigDecimal sumSalesOfRep(@Param("statuses") Collection<String> statuses,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             @Param("repId") Long repId);
}
