package com.erp.backend.repository;

import com.erp.backend.entity.Invoice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * S7-03: Truy vấn chỉ đọc lấy các khoản phải thu để tính sổ công nợ & tuổi nợ.
 * - Hoá đơn: [customerId, ngày hoá đơn, tổng tiền, đã thu (có thể null), còn nợ].
 *   Hoá đơn tạo trước S7-02 chưa có số còn nợ thì coi như còn nợ toàn bộ.
 * - Đơn đã duyệt chưa xuất hoá đơn: [customerId, thời điểm duyệt, tổng tiền] (vẫn là nợ của đại lý).
 */
public interface DebtAgingRepository extends Repository<Invoice, Long> {

    String INVOICE_ITEM = "select i.customer.id, i.invoiceDate, i.totalAmount, i.paidAmount, "
            + "coalesce(i.remainingAmount, i.totalAmount) from Invoice i ";
    String ORDER_ITEM = "select o.customer.id, o.approvedAt, o.totalAmount from SalesOrder o "
            + "where o.status in :statuses and o.invoiceId is null ";

    @Query(INVOICE_ITEM)
    List<Object[]> invoiceItems();

    @Query(INVOICE_ITEM + "where i.customer.salesRep.id = :salesRepId")
    List<Object[]> invoiceItemsBySalesRep(@Param("salesRepId") Long salesRepId);

    @Query(ORDER_ITEM)
    List<Object[]> uninvoicedOrderItems(@Param("statuses") Collection<String> statuses);

    @Query(ORDER_ITEM + "and o.customer.salesRep.id = :salesRepId")
    List<Object[]> uninvoicedOrderItemsBySalesRep(@Param("statuses") Collection<String> statuses,
                                                  @Param("salesRepId") Long salesRepId);
}
