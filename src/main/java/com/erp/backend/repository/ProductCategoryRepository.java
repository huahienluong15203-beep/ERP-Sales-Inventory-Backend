package com.erp.backend.repository;

import com.erp.backend.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByParent_Id(Long parentId);

    List<ProductCategory> findAllByOrderByLevelAscNameAsc();
}
