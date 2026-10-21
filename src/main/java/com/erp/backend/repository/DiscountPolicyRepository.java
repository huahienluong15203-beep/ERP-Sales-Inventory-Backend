package com.erp.backend.repository;

import com.erp.backend.entity.DiscountPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface DiscountPolicyRepository extends JpaRepository<DiscountPolicy, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<DiscountPolicy> {

    boolean existsByCodeIgnoreCase(String code);

    long countByScope(String scope);

    /** Đang bật và chưa hết hạn tính đến ngày date. */
    @Query("SELECT COUNT(p) FROM DiscountPolicy p WHERE p.status = 'ACTIVE' AND (p.endDate IS NULL OR p.endDate >= :date)")
    long countActive(@Param("date") LocalDate date);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    /** Chính sách đang hiệu lực áp cho một sản phẩm hoặc cho một trong các nhóm hàng chứa sản phẩm đó (cho tất cả các nhóm). */
    @Query("SELECT DISTINCT p FROM DiscountPolicy p LEFT JOIN FETCH p.tiers "
            + "WHERE p.status = 'ACTIVE' AND p.startDate <= :date AND (p.endDate IS NULL OR p.endDate >= :date) "
            + "AND ((p.scope = 'PRODUCT' AND p.product.id = :productId) "
            + "OR (p.scope = 'CATEGORY' AND p.category.id IN :categoryIds))")
    List<DiscountPolicy> findEffective(@Param("productId") Long productId,
                                       @Param("categoryIds") Collection<Long> categoryIds,
                                       @Param("date") LocalDate date);

    /** Chính sách đang hiệu lực áp cho nhóm đại lý cụ thể hoặc áp dụng chung (customerGroup IS NULL). */
    @Query("SELECT DISTINCT p FROM DiscountPolicy p LEFT JOIN FETCH p.tiers "
            + "WHERE p.status = 'ACTIVE' AND p.startDate <= :date AND (p.endDate IS NULL OR p.endDate >= :date) "
            + "AND (p.customerGroup IS NULL OR p.customerGroup = :customerGroup) "
            + "AND ((p.scope = 'PRODUCT' AND p.product.id = :productId) "
            + "OR (p.scope = 'CATEGORY' AND p.category.id IN :categoryIds))")
    List<DiscountPolicy> findEffectiveForGroup(@Param("productId") Long productId,
                                               @Param("categoryIds") Collection<Long> categoryIds,
                                               @Param("customerGroup") com.erp.backend.entity.CustomerGroup customerGroup,
                                               @Param("date") LocalDate date);
}
