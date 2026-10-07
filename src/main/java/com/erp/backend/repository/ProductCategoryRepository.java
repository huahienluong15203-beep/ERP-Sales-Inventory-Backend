package com.erp.backend.repository;

import com.erp.backend.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    boolean existsByCodeIgnoreCase(String code);

    java.util.Optional<ProductCategory> findByCodeIgnoreCase(String code);

    java.util.List<ProductCategory> findByLevelAndNameIgnoreCase(Integer level, String name);

    java.util.List<ProductCategory> findByParent_IdAndNameIgnoreCase(Long parentId, String name);

    boolean existsByParent_Id(Long parentId);

    List<ProductCategory> findAllByOrderByLevelAscNameAsc();
}
