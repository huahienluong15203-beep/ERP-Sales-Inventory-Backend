package com.erp.backend.repository;

import com.erp.backend.entity.ProductUnitConversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductUnitConversionRepository extends JpaRepository<ProductUnitConversion, Long> {

    List<ProductUnitConversion> findByProductId(Long productId);

    List<ProductUnitConversion> findByProductIdAndStatus(Long productId, String status);

    Optional<ProductUnitConversion> findByProductIdAndUnitNameIgnoreCase(Long productId, String unitName);

    boolean existsByProductIdAndUnitNameIgnoreCase(Long productId, String unitName);

    boolean existsByProductIdAndUnitNameIgnoreCaseAndIdNot(Long productId, String unitName, Long id);

    Optional<ProductUnitConversion> findByBarcode(String barcode);

    @Query("SELECT puc FROM ProductUnitConversion puc WHERE puc.product.sku = :sku AND LOWER(puc.unitName) = LOWER(:unitName)")
    Optional<ProductUnitConversion> findByProductSkuAndUnitNameIgnoreCase(@Param("sku") String sku, @Param("unitName") String unitName);
}
