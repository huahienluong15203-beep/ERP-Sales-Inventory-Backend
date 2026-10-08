package com.erp.backend.repository;

import com.erp.backend.entity.Customer;
import com.erp.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    // S3-03: Chặn trùng mã đại lý / mã số thuế
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByTaxCode(String taxCode);

    boolean existsByTaxCodeAndIdNot(String taxCode, Long id);

    /** Khoá dòng đại lý khi sửa điểm giao / đổi người phụ trách để 2 người thao tác cùng lúc không ghi đè nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    // S3-06: Chuyển giao hàng loạt
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.salesRep.id = :salesRepId")
    List<Customer> findBySalesRepIdForUpdate(@Param("salesRepId") Long salesRepId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.salesRep.id = :salesRepId and c.region.id = :regionId")
    List<Customer> findBySalesRepIdAndRegionIdForUpdate(@Param("salesRepId") Long salesRepId,
                                                        @Param("regionId") Long regionId);

    // S3-08: Tìm kiếm + lọc, nạp sẵn khu vực và người phụ trách để không phải truy vấn từng dòng
    @Override
    @EntityGraph(attributePaths = {"region", "salesRep"})
    Page<Customer> findAll(Specification<Customer> spec, Pageable pageable);

    @Query("select distinct c.salesRep from Customer c where c.salesRep is not null")
    List<User> findDistinctSalesRepsWithCustomers();

    long countBySalesRep_Id(Long salesRepId);
}
