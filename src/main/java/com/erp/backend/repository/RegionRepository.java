package com.erp.backend.repository;

import com.erp.backend.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegionRepository extends JpaRepository<Region, Long> {
    boolean existsByCode(String code);

    java.util.Optional<Region> findByCodeIgnoreCase(String code);

    List<Region> findByStatusOrderByNameAsc(String status);
}
