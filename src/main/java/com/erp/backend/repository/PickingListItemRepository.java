package com.erp.backend.repository;

import com.erp.backend.entity.PickingListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PickingListItemRepository extends JpaRepository<PickingListItem, Long> {

    List<PickingListItem> findByPickingList_IdOrderByRouteSequenceAsc(Long pickingListId);

    List<PickingListItem> findByPickingList_Id(Long pickingListId);
}
