package com.erp.backend.repository;

import com.erp.backend.entity.DeliveryTrip;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** S6-07: Chuyến giao hàng. */
public interface DeliveryTripRepository extends JpaRepository<DeliveryTrip, Long> {

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DeliveryTrip t where t.id = :id")
    Optional<DeliveryTrip> findByIdForUpdate(@Param("id") Long id);

    @Query("select t from DeliveryTrip t join fetch t.warehouse where t.status = :status order by t.expectedReturnAt asc, t.id asc")
    List<DeliveryTrip> findByStatusWithWarehouse(@Param("status") String status);

    @Query("select t from DeliveryTrip t join fetch t.warehouse where t.status = :status "
            + "and t.departedAt >= :from order by t.departedAt desc, t.id desc")
    List<DeliveryTrip> findRecentByStatus(@Param("status") String status, @Param("from") java.time.LocalDateTime from);
}
