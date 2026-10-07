package com.erp.backend.repository;

import com.erp.backend.entity.CustomerDeliveryAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerDeliveryAddressRepository extends JpaRepository<CustomerDeliveryAddress, Long> {

    /** Điểm giao đang dùng của một đại lý, điểm mặc định đứng đầu. */
    List<CustomerDeliveryAddress> findByCustomer_IdAndStatusOrderByDefaultAddressDescIdAsc(Long customerId, String status);

    Optional<CustomerDeliveryAddress> findByIdAndCustomer_Id(Long id, Long customerId);

    /** Dọn điểm giao khi xoá hồ sơ đại lý chưa phát sinh giao dịch. */
    void deleteByCustomer_Id(Long customerId);
}
