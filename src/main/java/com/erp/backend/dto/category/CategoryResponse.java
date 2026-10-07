package com.erp.backend.dto.category;

/**
 * S2-06: Một nhóm hàng trong cây. Frontend dựng cây từ danh sách phẳng theo parentId.
 * productCount: số sản phẩm nằm trực tiếp trong nhóm (không tính nhóm con).
 */
public record CategoryResponse(
        Long id,
        String code,
        String name,
        Integer level,
        Long parentId,
        String description,
        long productCount) {
}
