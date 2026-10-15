package com.erp.backend.repository;

import com.erp.backend.entity.SalesOrderLine;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * S8-06: Tổng hợp doanh số theo nhiều chiều từ dòng đơn hàng.
 * Mỗi dòng kết quả: [id nhóm, mã nhóm, tên nhóm, doanh số (tiền sau chiết khấu), sản lượng (ĐVT cơ sở),
 * giá vốn (sản lượng x giá vốn hiện tại của SKU), số đơn].
 * Đơn tính doanh số: trạng thái trong :statuses, mốc = ngày duyệt (đơn cũ thiếu ngày duyệt thì lấy ngày tạo) trong [from, to).
 */
public interface SalesReportRepository extends Repository<SalesOrderLine, Long> {

    String SUMS = "sum(l.netAmount), sum(l.baseQuantity), sum(l.baseQuantity * coalesce(p.costPrice, 0)), count(distinct o.id) ";
    String FROM = "from SalesOrderLine l join l.order o join o.customer c join l.product p ";
    String WHERE = "where o.status in :statuses and coalesce(o.approvedAt, o.createdAt) >= :from "
            + "and coalesce(o.approvedAt, o.createdAt) < :to ";

    @Query("select r.id, r.username, r.fullName, " + SUMS + FROM + "left join c.salesRep r " + WHERE
            + "group by r.id, r.username, r.fullName")
    List<Object[]> byRep(@Param("statuses") Collection<String> statuses,
                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select g.id, g.code, g.name, " + SUMS + FROM + "left join c.region g " + WHERE
            + "group by g.id, g.code, g.name")
    List<Object[]> byRegion(@Param("statuses") Collection<String> statuses,
                            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select c.id, c.code, c.name, " + SUMS + FROM + WHERE + "group by c.id, c.code, c.name")
    List<Object[]> byCustomer(@Param("statuses") Collection<String> statuses,
                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select pc.id, pc.code, pc.name, " + SUMS + FROM + "left join p.productCategory pc " + WHERE
            + "group by pc.id, pc.code, pc.name")
    List<Object[]> byCategory(@Param("statuses") Collection<String> statuses,
                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select p.id, p.sku, p.name, " + SUMS + FROM + WHERE + "group by p.id, p.sku, p.name")
    List<Object[]> bySku(@Param("statuses") Collection<String> statuses,
                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
