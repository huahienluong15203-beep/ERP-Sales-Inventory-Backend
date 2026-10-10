package com.erp.backend.repository;

import com.erp.backend.entity.WarehouseLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseLocationRepository extends JpaRepository<WarehouseLocation, Long> {

    List<WarehouseLocation> findByWarehouse_IdOrderByCodeAsc(Long warehouseId);

    List<WarehouseLocation> findByWarehouse_IdAndStatusIgnoreCaseOrderByCodeAsc(Long warehouseId, String status);

    List<WarehouseLocation> findByWarehouse_IdAndLocationTypeIgnoreCaseOrderByCodeAsc(Long warehouseId, String locationType);

    List<WarehouseLocation> findByWarehouse_IdAndStatusIgnoreCaseAndLocationTypeIgnoreCaseOrderByCodeAsc(
            Long warehouseId, String status, String locationType);

    Optional<WarehouseLocation> findByWarehouse_IdAndId(Long warehouseId, Long id);

    Optional<WarehouseLocation> findByWarehouse_IdAndCodeIgnoreCase(Long warehouseId, String code);

    boolean existsByWarehouse_IdAndCodeIgnoreCase(Long warehouseId, String code);

    boolean existsByWarehouse_IdAndCodeIgnoreCaseAndIdNot(Long warehouseId, String code, Long id);

    long countByWarehouse_Id(Long warehouseId);

    long countByWarehouse_IdAndStatusIgnoreCase(Long warehouseId, String status);
}
