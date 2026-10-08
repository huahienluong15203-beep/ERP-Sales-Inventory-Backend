package com.erp.backend.repository;

import com.erp.backend.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long>, JpaSpecificationExecutor<SalesOrder> {

    boolean existsByCode(String code);

    /** Đại lý đã có đơn hàng (kể cả đơn nháp) thì không được xoá hồ sơ. */
    boolean existsByCustomer_Id(Long customerId);

    /** S4-02: Tổng tiền các đơn đang tính vào công nợ của đại lý. */
    @Query("select coalesce(sum(o.totalAmount), 0) from SalesOrder o "
            + "where o.customer.id = :customerId and o.status in :statuses")
    BigDecimal sumTotalAmountByCustomerAndStatuses(@Param("customerId") Long customerId,
                                                   @Param("statuses") Collection<String> statuses);

    /** S4-02: Tổng tiền các đơn nợ đã quá hạn (duyệt trước mốc dueBefore). */
    @Query("select coalesce(sum(o.totalAmount), 0) from SalesOrder o "
            + "where o.customer.id = :customerId and o.status in :statuses and o.approvedAt < :dueBefore")
    BigDecimal sumOverdueAmount(@Param("customerId") Long customerId,
                                @Param("statuses") Collection<String> statuses,
                                @Param("dueBefore") LocalDateTime dueBefore);

    long countByCustomer_IdAndStatusInAndApprovedAtBefore(Long customerId, Collection<String> statuses,
                                                          LocalDateTime dueBefore);

    /** S4-02: Đơn nợ quá hạn lâu nhất (để báo số ngày quá hạn). */
    Optional<SalesOrder> findFirstByCustomer_IdAndStatusInAndApprovedAtBeforeOrderByApprovedAtAsc(
            Long customerId, Collection<String> statuses, LocalDateTime dueBefore);
}
