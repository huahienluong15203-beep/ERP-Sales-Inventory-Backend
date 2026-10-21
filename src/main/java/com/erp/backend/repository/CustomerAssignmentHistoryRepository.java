package com.erp.backend.repository;

import com.erp.backend.entity.CustomerAssignmentHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerAssignmentHistoryRepository
        extends JpaRepository<CustomerAssignmentHistory, Long>, JpaSpecificationExecutor<CustomerAssignmentHistory> {

    List<CustomerAssignmentHistory> findByCustomer_IdOrderByChangedAtDescIdDesc(Long customerId);

    /** Dọn lịch sử phân công khi xoá hồ sơ đại lý chưa phát sinh giao dịch. */
    void deleteByCustomer_Id(Long customerId);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.region", "fromSalesRep", "toSalesRep", "changedBy"})
    Page<CustomerAssignmentHistory> findAll(Specification<CustomerAssignmentHistory> spec, Pageable pageable);
}
