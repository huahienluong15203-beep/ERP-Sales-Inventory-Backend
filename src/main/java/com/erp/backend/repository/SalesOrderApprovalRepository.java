package com.erp.backend.repository;

import com.erp.backend.entity.SalesOrderApproval;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * S4-05: Lịch sử duyệt đơn không sửa, không xoá được.
 * Cố ý kế thừa Repository (không phải JpaRepository) để không có hàm delete / deleteAll.
 */
public interface SalesOrderApprovalRepository extends Repository<SalesOrderApproval, Long> {

    SalesOrderApproval save(SalesOrderApproval approval);

    List<SalesOrderApproval> findByOrder_IdOrderByCreatedAtAscIdAsc(Long orderId);
}
