package com.erp.backend.repository;

import com.erp.backend.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    boolean existsByCode(String code);

    java.util.Optional<Warehouse> findByCodeIgnoreCase(String code);

    List<Warehouse> findByStatusOrderByNameAsc(String status);
}
