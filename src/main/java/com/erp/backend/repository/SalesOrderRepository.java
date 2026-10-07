package com.erp.backend.repository;

import com.erp.backend.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long>, JpaSpecificationExecutor<SalesOrder> {

    boolean existsByCode(String code);

    /** Đại lý đã có đơn hàng (kể cả đơn nháp) thì không được xoá hồ sơ. */
    boolean existsByCustomer_Id(Long customerId);
}
