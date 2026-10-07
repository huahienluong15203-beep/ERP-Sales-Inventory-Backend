package com.erp.backend.dto.category;

/** S2-06: Kết quả chuyển nhóm. */
public record MoveProductsResponse(Long categoryId, String categoryName, int movedCount) {
}
