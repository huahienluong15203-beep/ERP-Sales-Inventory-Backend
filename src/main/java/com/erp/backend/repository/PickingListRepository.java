package com.erp.backend.repository;

import com.erp.backend.entity.PickingList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PickingListRepository extends JpaRepository<PickingList, Long>, JpaSpecificationExecutor<PickingList> {

    Optional<PickingList> findByCode(String code);

    boolean existsByCode(String code);

    List<PickingList> findByWarehouse_Id(Long warehouseId);

    List<PickingList> findByWarehouse_IdAndStatus(Long warehouseId, String status);

    List<PickingList> findByPicker_IdAndStatus(Long pickerId, String status);

    @Query("SELECT count(plo) > 0 FROM PickingListOrder plo " +
            "WHERE plo.order.id = :orderId AND plo.pickingList.status IN :statuses")
    boolean isOrderInPickingListWithStatuses(@Param("orderId") Long orderId,
                                            @Param("statuses") Collection<String> statuses);

    @Query("SELECT count(plo) > 0 FROM PickingListOrder plo " +
            "WHERE plo.order.id IN :orderIds AND plo.pickingList.status IN :statuses")
    boolean areAnyOrdersInPickingListWithStatuses(@Param("orderIds") Collection<Long> orderIds,
                                                 @Param("statuses") Collection<String> statuses);

    @Query("SELECT plo.order.id FROM PickingListOrder plo " +
            "WHERE plo.order.id IN :orderIds AND plo.pickingList.status IN :statuses")
    List<Long> findOrderIdsInActivePickingList(@Param("orderIds") Collection<Long> orderIds,
                                              @Param("statuses") Collection<String> statuses);
    @Query("SELECT plo.pickingList FROM PickingListOrder plo " +
            "WHERE plo.order.id = :orderId AND plo.pickingList.status IN :statuses ORDER BY plo.pickingList.id DESC")
    List<PickingList> findPickingListsByOrderId(@Param("orderId") Long orderId,
                                                @Param("statuses") Collection<String> statuses);
}
