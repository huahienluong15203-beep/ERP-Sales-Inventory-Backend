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

    /**
     * Trùng dữ liệu bị ràng buộc DB chặn (vd 2 người cùng lập hoá đơn cho 1 đơn, S7-01) -> 409 thay vì 500.
     * Không trả chi tiết lỗi SQL ra ngoài.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        // Dữ liệu vượt độ dài cột (PostgreSQL 22001) không phải "trùng dữ liệu": báo đúng để người dùng sửa lại
        String cause = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : null;
        if (cause != null && cause.toLowerCase(java.util.Locale.ROOT).contains("value too long")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiError.of("DATA_TOO_LONG", "Có trường dữ liệu dài quá mức cho phép. Vui lòng rút gọn nội dung rồi thử lại.", Map.of()));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("DATA_CONFLICT", "Dữ liệu bị trùng hoặc vừa được người khác cập nhật. Vui lòng tải lại và thử lại.", Map.of()));
    }

    /** 2 người cùng sửa 1 bản ghi (@Version) hoặc chờ khoá dòng quá lâu -> 409, người dùng tải lại rồi làm lại. */
    @ExceptionHandler({org.springframework.orm.ObjectOptimisticLockingFailureException.class,
            org.springframework.dao.PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleConcurrentUpdate(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONCURRENT_UPDATE", "Dữ liệu vừa được người khác cập nhật. Vui lòng tải lại và thử lại.", Map.of()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("MALFORMED_JSON", "Dữ liệu JSON gửi lên không đúng định dạng.", Map.of("error", "Định dạng JSON không hợp lệ")));
    }
}
