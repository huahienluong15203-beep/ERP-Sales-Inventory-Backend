package com.erp.backend.repository;

import com.erp.backend.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    boolean existsByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    java.util.Optional<Warehouse> findByCodeIgnoreCase(String code);

    List<Warehouse> findByStatusOrderByNameAsc(String status);

    List<Warehouse> findByStatusIgnoreCaseOrderByNameAsc(String status);

    List<Warehouse> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCase(String name, String code);
}
