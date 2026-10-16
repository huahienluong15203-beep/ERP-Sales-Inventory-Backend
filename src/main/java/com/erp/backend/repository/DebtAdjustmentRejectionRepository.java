package com.erp.backend.repository;

import com.erp.backend.entity.DebtAdjustmentRejection;
import com.erp.backend.entity.SalesReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/** S8-02: Quyết định từ chối giảm trừ công nợ theo phiếu trả hàng. */
public interface DebtAdjustmentRejectionRepository extends JpaRepository<DebtAdjustmentRejection, Long> {

    boolean existsBySalesReturn_Id(Long salesReturnId);

    List<DebtAdjustmentRejection> findBySalesReturn_IdIn(Collection<Long> salesReturnIds);

    /** Mọi phiếu trả đã được thủ kho xác nhận nhận hàng (mới nhất trước) = nguồn của chứng từ điều chỉnh. */
    @Query("select r from SalesReturn r where r.status = 'RECEIVED' order by r.receivedAt desc, r.id desc")
    List<SalesReturn> findReceivedReturns();
}
