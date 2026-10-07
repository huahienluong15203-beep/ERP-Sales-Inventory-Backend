package com.erp.backend.repository;

import com.erp.backend.entity.CustomerAssignmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerAssignmentHistoryRepository extends JpaRepository<CustomerAssignmentHistory, Long> {

    List<CustomerAssignmentHistory> findByCustomer_IdOrderByChangedAtDescIdDesc(Long customerId);
}
