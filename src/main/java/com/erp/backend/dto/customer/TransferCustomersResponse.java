package com.erp.backend.dto.customer;

/** S3-06: Kết quả chuyển giao hàng loạt. */
public record TransferCustomersResponse(int transferredCount, String message) {
}
