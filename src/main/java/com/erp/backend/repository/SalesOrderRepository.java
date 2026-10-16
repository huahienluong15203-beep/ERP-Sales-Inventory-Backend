package com.erp.backend.repository;

import com.erp.backend.dto.customer.PurchasedLastPrice;
import com.erp.backend.dto.customer.PurchasedProductAggregate;
import com.erp.backend.dto.customer.PurchasedUnitUsage;
import com.erp.backend.entity.SalesOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long>, JpaSpecificationExecutor<SalesOrder>,
        SalesOrderTotalsRepository {

    boolean existsByCode(String code);

    /** S4-05: Khoá dòng đơn khi chốt / duyệt để 2 người thao tác cùng lúc không ghi đè nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<SalesOrder> findByIdForUpdate(@Param("id") Long id);

    /** Đại lý đã có đơn hàng (kể cả đơn nháp) thì không được xoá hồ sơ. */
    boolean existsByCustomer_Id(Long customerId);

    /**
     * S4-02 / S7-07: Tổng tiền các đơn đang tính vào công nợ nhưng CHƯA xuất hoá đơn.
     * Đơn đã có hoá đơn thì công nợ tính theo số còn nợ của hoá đơn (đã trừ tiền thu), xem InvoiceRepository.
     */
    @Query("select coalesce(sum(o.totalAmount), 0) from SalesOrder o "
            + "where o.customer.id = :customerId and o.status in :statuses and o.invoiceId is null")
    BigDecimal sumTotalAmountByCustomerAndStatuses(@Param("customerId") Long customerId,
                                                   @Param("statuses") Collection<String> statuses);

    /** S4-02 / S7-07: Như trên (đơn chưa xuất hoá đơn) theo danh sách đại lý (nạp công nợ hàng loạt cho danh sách). */
    @Query("select o.customer.id, coalesce(sum(o.totalAmount), 0) from SalesOrder o "
            + "where o.customer.id in :customerIds and o.status in :statuses and o.invoiceId is null group by o.customer.id")
    List<Object[]> sumTotalAmountByCustomerIdsAndStatuses(@Param("customerIds") Collection<Long> customerIds,
                                                           @Param("statuses") Collection<String> statuses);

    /** S4-02 / S7-07: Tổng tiền các đơn nợ CHƯA xuất hoá đơn đã quá hạn (duyệt trước mốc dueBefore). */
    @Query("select coalesce(sum(o.totalAmount), 0) from SalesOrder o "
            + "where o.customer.id = :customerId and o.status in :statuses and o.approvedAt < :dueBefore "
            + "and o.invoiceId is null")
    BigDecimal sumOverdueAmount(@Param("customerId") Long customerId,
                                @Param("statuses") Collection<String> statuses,
                                @Param("dueBefore") LocalDateTime dueBefore);

    long countByCustomer_IdAndStatusInAndApprovedAtBeforeAndInvoiceIdIsNull(Long customerId, Collection<String> statuses,
                                                                            LocalDateTime dueBefore);

    /** S4-02: Đơn nợ (chưa xuất hoá đơn) quá hạn lâu nhất (để báo số ngày quá hạn). */
    Optional<SalesOrder> findFirstByCustomer_IdAndStatusInAndApprovedAtBeforeAndInvoiceIdIsNullOrderByApprovedAtAsc(
            Long customerId, Collection<String> statuses, LocalDateTime dueBefore);

    /** S5-06: Đơn đang giữ chỗ tồn đã quá hạn (đơn quá hạn lâu nhất lên trước). */
    @Query("select o.id from SalesOrder o where o.status in :statuses and o.reservationStatus = :reservationStatus "
            + "and o.reservationExpiresAt < :now order by o.reservationExpiresAt asc, o.id asc")
    List<Long> findExpiredReservationOrderIds(@Param("statuses") Collection<String> statuses,
                                              @Param("reservationStatus") String reservationStatus,
                                              @Param("now") LocalDateTime now,
                                              Pageable pageable);

    // ======================= S4-04: LỊCH SỬ MUA HÀNG CỦA ĐẠI LÝ =======================
    // Mốc thời gian của đơn = approvedAt (ngày duyệt), đơn cũ thiếu approvedAt thì dùng createdAt.

    /** S4-04: Số đơn đã mua của đại lý từ mốc :from. */
    @Query("select count(o) from SalesOrder o where o.customer.id = :customerId and o.status in :statuses "
            + "and coalesce(o.approvedAt, o.createdAt) >= :from")
    long countPurchasedOrders(@Param("customerId") Long customerId,
                              @Param("statuses") Collection<String> statuses,
                              @Param("from") LocalDateTime from);

    /** S4-04: Tổng tiền phải thu các đơn đã mua của đại lý từ mốc :from. */
    @Query("select coalesce(sum(o.totalAmount), 0) from SalesOrder o where o.customer.id = :customerId "
            + "and o.status in :statuses and coalesce(o.approvedAt, o.createdAt) >= :from")
    BigDecimal sumPurchasedAmount(@Param("customerId") Long customerId,
                                  @Param("statuses") Collection<String> statuses,
                                  @Param("from") LocalDateTime from);

    /** S4-04: Tổng hợp theo sản phẩm: tổng SL cơ sở, số đơn, lần mua gần nhất. */
    @Query("select new com.erp.backend.dto.customer.PurchasedProductAggregate("
            + "l.product.id, sum(l.baseQuantity), count(distinct o.id), max(coalesce(o.approvedAt, o.createdAt))) "
            + "from SalesOrderLine l join l.order o "
            + "where o.customer.id = :customerId and o.status in :statuses "
            + "and coalesce(o.approvedAt, o.createdAt) >= :from "
            + "group by l.product.id")
    List<PurchasedProductAggregate> aggregatePurchasedProducts(@Param("customerId") Long customerId,
                                                               @Param("statuses") Collection<String> statuses,
                                                               @Param("from") LocalDateTime from);

    /** S4-04: Số dòng đã đặt theo từng ĐVT của các sản phẩm (chọn ĐVT hay dùng nhất). */
    @Query("select new com.erp.backend.dto.customer.PurchasedUnitUsage("
            + "l.product.id, l.unitName, l.conversionFactor, count(l.id), sum(l.baseQuantity)) "
            + "from SalesOrderLine l join l.order o "
            + "where o.customer.id = :customerId and o.status in :statuses "
            + "and coalesce(o.approvedAt, o.createdAt) >= :from and l.product.id in :productIds "
            + "group by l.product.id, l.unitName, l.conversionFactor")
    List<PurchasedUnitUsage> purchasedUnitUsages(@Param("customerId") Long customerId,
                                                 @Param("statuses") Collection<String> statuses,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("productIds") Collection<Long> productIds);

    /**
     * S4-04: Các dòng thuộc đơn mua gần nhất của từng sản phẩm (để lấy đơn giá lần trước).
     * Một sản phẩm có thể ra nhiều dòng (nhiều dòng trong cùng đơn / 2 đơn cùng thời điểm) -> service chọn 1.
     */
    @Query("select new com.erp.backend.dto.customer.PurchasedLastPrice("
            + "l.product.id, o.id, l.id, coalesce(o.approvedAt, o.createdAt), l.unitName, l.conversionFactor, "
            + "l.unitPrice, l.pricePerUnit) "
            + "from SalesOrderLine l join l.order o "
            + "where o.customer.id = :customerId and o.status in :statuses and l.product.id in :productIds "
            + "and coalesce(o.approvedAt, o.createdAt) = ("
            + "  select max(coalesce(o2.approvedAt, o2.createdAt)) from SalesOrderLine l2 join l2.order o2 "
            + "  where o2.customer.id = :customerId and o2.status in :statuses and l2.product.id = l.product.id)")
    List<PurchasedLastPrice> findLastPurchasedPrices(@Param("customerId") Long customerId,
                                                     @Param("statuses") Collection<String> statuses,
                                                     @Param("productIds") Collection<Long> productIds);

    /** S4-04: Đơn đã mua gần nhất của đại lý (dùng PageRequest.of(0, 1)). */
    @Query("select o from SalesOrder o where o.customer.id = :customerId and o.status in :statuses "
            + "order by coalesce(o.approvedAt, o.createdAt) desc, o.id desc")
    List<SalesOrder> findLatestPurchasedOrders(@Param("customerId") Long customerId,
                                               @Param("statuses") Collection<String> statuses,
                                               Pageable pageable);

    boolean existsByReservedWarehouse_Id(Long warehouseId);

    /**
     * S7-01: Đơn đã giao của đại lý chưa lập hoá đơn, giao trong [from, to).
     * Mốc giao = deliveredAt (S6-08); đơn giao theo cách cũ chưa có deliveredAt thì dùng updatedAt.
     */
    @Query("select o from SalesOrder o where o.customer.id = :customerId and o.status in :statuses "
            + "and o.invoiceId is null and coalesce(o.deliveredAt, o.updatedAt) >= :from "
            + "and coalesce(o.deliveredAt, o.updatedAt) < :to order by coalesce(o.deliveredAt, o.updatedAt) asc, o.id asc")
    List<SalesOrder> findUninvoiced(@Param("customerId") Long customerId,
                                    @Param("statuses") Collection<String> statuses,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /** S7-01: chỉ lấy id (không nạp đơn vào bộ nhớ trước khi khoá, để lúc khoá đọc được dữ liệu mới nhất). */
    @Query("select o.id from SalesOrder o where o.customer.id = :customerId and o.status in :statuses "
            + "and o.invoiceId is null and coalesce(o.deliveredAt, o.updatedAt) >= :from "
            + "and coalesce(o.deliveredAt, o.updatedAt) < :to")
    List<Long> findUninvoicedIds(@Param("customerId") Long customerId,
                                 @Param("statuses") Collection<String> statuses,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    /** S7-01: Khoá các đơn khi lập hoá đơn (theo id tăng dần để tránh deadlock), chống lập hoá đơn 2 lần. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id in :ids order by o.id asc")
    List<SalesOrder> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    /** S6-06 & S6-07: các đơn (điểm giao) của những chuyến giao, kèm thông tin người nhận & địa chỉ. */
    @Query("select o from SalesOrder o join fetch o.customer c left join fetch o.deliveryAddress a "
            + "left join fetch c.region r "
            + "where o.deliveryTrip.id in :tripIds order by coalesce(o.deliveryStopSequence, o.id) asc, o.id asc")
    List<SalesOrder> findByDeliveryTripIds(@Param("tripIds") Collection<Long> tripIds);

    /** S6-07: id các đơn của 1 chuyến (không nạp đơn trước khi khoá). */
    @Query("select o.id from SalesOrder o where o.deliveryTrip.id = :tripId order by coalesce(o.deliveryStopSequence, o.id) asc, o.id asc")
    List<Long> findIdsByDeliveryTripId(@Param("tripId") Long tripId);

    /** S6-07: đơn đã xuất kho chưa xếp vào chuyến giao nào. */
    @Query("select o from SalesOrder o where o.status = :status and o.deliveryTrip is null")
    List<SalesOrder> findByStatusWithoutTrip(@Param("status") String status);

    /** S6-06: Đơn đã xuất kho chưa xếp chuyến kèm chi tiết địa chỉ, người nhận, khu vực để gom chuyến theo khu vực. */
    @Query("select o from SalesOrder o join fetch o.customer c left join fetch o.deliveryAddress a "
            + "left join fetch c.region r left join fetch o.reservedWarehouse w "
            + "where o.status = :status and o.deliveryTrip is null order by o.id asc")
    List<SalesOrder> findCandidateOrdersWithDetails(@Param("status") String status);

    /** S6-05: mã các đơn nợ hàng tách ra từ đơn :parentId (theo thứ tự tạo). */
    @Query("select o.code from SalesOrder o where o.parentOrder.id = :parentId order by o.id")
    List<String> findBackorderCodes(@Param("parentId") Long parentId);

    /** S8-05: Đếm số lượng đơn theo trạng thái đơn hàng. */
    long countByStatus(String status);

    /** S8-05: Đếm số lượng đơn theo danh sách trạng thái. */
    long countByStatusIn(Collection<String> statuses);

    /** S8-05: Lấy dữ liệu bán hàng theo khoảng thời gian để thống kê doanh số theo ngày và khu vực. */
    @Query("select coalesce(o.dispatchedAt, coalesce(o.deliveredAt, coalesce(o.approvedAt, coalesce(o.updatedAt, o.createdAt)))), "
            + "o.totalAmount, o.id, r.id, r.code, r.name "
            + "from SalesOrder o left join o.customer c left join c.region r "
            + "where o.status in :statuses "
            + "and coalesce(o.dispatchedAt, coalesce(o.deliveredAt, coalesce(o.approvedAt, coalesce(o.updatedAt, o.createdAt)))) >= :from "
            + "and coalesce(o.dispatchedAt, coalesce(o.deliveredAt, coalesce(o.approvedAt, coalesce(o.updatedAt, o.createdAt)))) < :to")
    List<Object[]> findSalesDataForPeriod(@Param("statuses") Collection<String> statuses,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}

