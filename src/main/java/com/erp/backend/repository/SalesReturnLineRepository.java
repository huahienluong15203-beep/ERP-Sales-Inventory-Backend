package com.erp.backend.repository;

import com.erp.backend.entity.SalesReturn;
import com.erp.backend.entity.SalesReturnLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * S8-01: Repository quản lý dòng chi tiết phiếu trả hàng.
 */
@Repository
public interface SalesReturnLineRepository extends JpaRepository<SalesReturnLine, Long> {

    List<SalesReturnLine> findBySalesReturn_IdOrderByLineNoAsc(Long salesReturnId);

    /**
     * Tính tổng số lượng đã trả trước đó của một dòng hoá đơn cụ thể.
     * Chỉ tính các phiếu có trạng thái KHÔNG PHẢI CANCELLED.
     * @param invoiceLineId id dòng hoá đơn
     * @param excludeReturnId id phiếu trả hàng cần loại trừ (khi kiểm tra hoặc cập nhật phiếu hiện tại, nếu tạo mới thì truyền 0L)
     */
    @Query("SELECT COALESCE(SUM(l.returnQuantity), 0) FROM SalesReturnLine l " +
            "WHERE l.invoiceLine.id = :invoiceLineId " +
            "AND l.salesReturn.status <> :cancelledStatus " +
            "AND (:excludeReturnId IS NULL OR l.salesReturn.id <> :excludeReturnId)")
    BigDecimal getPreviouslyReturnedQuantity(
            @Param("invoiceLineId") Long invoiceLineId,
            @Param("excludeReturnId") Long excludeReturnId,
            @Param("cancelledStatus") String cancelledStatus);

    @Query("SELECT l FROM SalesReturnLine l " +
            "WHERE l.salesReturn.invoice.id = :invoiceId " +
            "AND l.salesReturn.status <> :cancelledStatus")
    List<SalesReturnLine> findActiveReturnLinesByInvoiceId(
            @Param("invoiceId") Long invoiceId,
            @Param("cancelledStatus") String cancelledStatus);
}
