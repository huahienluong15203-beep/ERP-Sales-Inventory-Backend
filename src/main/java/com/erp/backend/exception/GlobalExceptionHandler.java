package com.erp.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chuyển lỗi thành JSON thống nhất.
 * LƯU Ý: Cố ý KHÔNG bắt Exception chung chung, để lỗi 401/403 của Spring Security vẫn trả đúng mã.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        Map<String, String> details = ex.getField() == null ? Map.of() : Map.of(ex.getField(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getCode(), ex.getMessage(), details));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(err -> details.putIfAbsent(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_ERROR", "Dữ liệu không hợp lệ", details));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("FILE_SIZE_EXCEEDED", "Dung lượng tệp tải lên vượt quá giới hạn cho phép (tối đa 2MB).", Map.of("file", "Dung lượng tối đa là 2MB")));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String paramValue = ex.getValue() != null ? ex.getValue().toString() : "null";
        String message = String.format("Giá trị '%s' không hợp lệ cho tham số '%s'.", paramValue, paramName);
        Map<String, String> details = Map.of(paramName, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_PARAMETER", message, details));
    }

    /**
     * S1-10: lỗi ném bằng ResponseStatusException (vd khoá tài khoản thiếu lý do) trước đây trả body mặc định
     * của Spring (status, error, path). Đưa về đúng định dạng chung {code, message, details, timestamp}.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(org.springframework.web.server.ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String code = status != null ? status.name() : "ERROR";
        String message = ex.getReason() != null ? ex.getReason() : (status != null ? status.getReasonPhrase() : "Có lỗi xảy ra");
        return ResponseEntity.status(ex.getStatusCode()).body(ApiError.of(code, message, Map.of()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("MALFORMED_JSON", "Dữ liệu JSON gửi lên không đúng định dạng.", Map.of("error", "Định dạng JSON không hợp lệ")));
    }
}
