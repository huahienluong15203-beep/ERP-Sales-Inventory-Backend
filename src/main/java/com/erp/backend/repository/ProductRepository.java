package com.erp.backend.repository;

import com.erp.backend.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    // S2-10: tìm SKU không phân biệt hoa thường (sản phẩm nhập Excel có thể lưu chữ thường)
    Optional<Product> findFirstBySkuIgnoreCaseOrderByIdAsc(String sku);

    boolean existsBySku(String sku);

    @Query("SELECT p FROM Product p WHERE UPPER(p.sku) IN :skus")
    List<Product> findBySkuIn(@Param("skus") Collection<String> skus);

    /**
     * Truy vấn nhanh tập hợp SKU đã tồn tại trong DB không phân biệt hoa thường, tránh N+1 khi import tới 5.000 sản phẩm.
     */
    @Query("SELECT UPPER(p.sku) FROM Product p WHERE UPPER(p.sku) IN :skus")
    Set<String> findExistingSkus(@Param("skus") Collection<String> skus);

    // ======================= S2-06: NHÓM HÀNG =======================

    boolean existsByProductCategory_Id(Long categoryId);

    List<Product> findByProductCategory_Id(Long categoryId);

    Page<Product> findByProductCategory_IdIn(Collection<Long> categoryIds, Pageable pageable);

    /** Số sản phẩm trực tiếp của từng nhóm: mỗi dòng gồm [id nhóm, số sản phẩm]. */
    @Query("SELECT p.productCategory.id, COUNT(p) FROM Product p WHERE p.productCategory IS NOT NULL GROUP BY p.productCategory.id")
    List<Object[]> countByCategory();

    Page<Product> findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(String name, String sku, Pageable pageable);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.unitConversions WHERE p.id = :id")
    Optional<Product> findByIdWithConversions(@Param("id") Long id);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.unitConversions WHERE LOWER(p.sku) = LOWER(:sku)")
    Optional<Product> findBySkuWithConversions(@Param("sku") String sku);

    @Query("SELECT p FROM Product p WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:category IS NULL OR :category = '' OR p.category = :category) " +
            "AND (:status IS NULL OR :status = '' OR p.status = :status)")
    Page<Product> searchProducts(@Param("keyword") String keyword,
                                 @Param("category") String category,
                                 @Param("status") String status,
                                 Pageable pageable);
}
