package com.erp.backend.exception;

import lombok.Getter;

/**
 * Người dùng gửi yêu cầu quá nhanh / quá nhiều lần (chống spam gửi email).
 * retryAfterSeconds: số giây phải đợi trước khi được thử lại.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
