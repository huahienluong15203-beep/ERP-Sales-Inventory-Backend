package com.erp.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ có chủ đích (trùng dữ liệu, vi phạm quy tắc...).
 * field: tên trường gây lỗi để Frontend tô đỏ đúng ô nhập (có thể null).
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String field;

    public BusinessException(HttpStatus status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message, null);
    }

    public static BusinessException conflict(String code, String message, String field) {
        return new BusinessException(HttpStatus.CONFLICT, code, message, field);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, null);
    }

    public static BusinessException forbidden(String code, String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, code, message, null);
    }
}
