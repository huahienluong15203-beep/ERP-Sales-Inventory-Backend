package com.erp.backend.service;

import com.erp.backend.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S7-05: Lưu ảnh biên lai thu tiền tại tuyến.
 * - Frontend gửi ảnh dạng data URL base64 (data:image/jpeg;base64,...). Trước đây chuỗi này được ghi thẳng vào
 *   cột receipt_image_url (tối đa 1000 ký tự) nên mọi ảnh thật đều lưu lỗi.
 * - Giờ giải mã, kiểm tra đúng là ảnh JPG / PNG (magic bytes + kích thước), tối đa 5MB, lưu thành tệp
 *   trong uploads/receipt-images; cột chỉ giữ tên tệp. Xem lại qua API có kiểm quyền, không phục vụ tĩnh.
 */
@Slf4j
@Service
public class ReceiptImageStorageService {

    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    /** Tên tệp do hệ thống sinh, dùng để phân biệt với đường dẫn http(s) cũ đã lưu trong cột. */
    static final Pattern STORED_NAME = Pattern.compile("rc_[A-Za-z0-9_\\-]{1,90}\\.(jpg|png)");
    private static final Pattern DATA_URL = Pattern.compile(
            "^data:image/(jpeg|jpg|png);base64,(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final Path directory;

    public ReceiptImageStorageService(
            @Value("${erp.app.upload.receipt-image-dir:uploads/receipt-images}") String dir) {
        this.directory = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được thư mục lưu ảnh biên lai: " + directory, e);
        }
    }

    public record LoadedFile(byte[] content, String contentType) {
    }

    /** Tên tệp ảnh do hệ thống lưu (không phải đường dẫn http cũ)? */
    public static boolean isStoredName(String value) {
        return value != null && STORED_NAME.matcher(value).matches();
    }

    /** Giải mã data URL base64, kiểm tra và lưu ảnh; trả về tên tệp để ghi vào phiếu thu. */
    public String storeDataUrl(String dataUrl) {
        if (!StringUtils.hasText(dataUrl)) {
            throw BusinessException.badRequest("RECEIPT_IMAGE_REQUIRED", "Vui lòng chụp hoặc chọn ảnh biên lai thu tiền.");
        }
        Matcher m = DATA_URL.matcher(dataUrl.trim());
        if (!m.matches()) {
            throw BusinessException.badRequest("INVALID_IMAGE_DATA", "Ảnh biên lai chỉ nhận JPG hoặc PNG.");
        }
        // base64 dài hơn ~4/3 kích thước thật: chặn sớm trước khi giải mã chuỗi quá lớn
        String base64 = m.group(2).replaceAll("\\s", "");
        if ((long) base64.length() * 3 / 4 > MAX_FILE_SIZE) {
            throw BusinessException.badRequest("FILE_SIZE_EXCEEDED", "Ảnh biên lai tối đa 5MB.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("INVALID_IMAGE_DATA", "Dữ liệu ảnh biên lai bị hỏng, vui lòng chọn lại ảnh.");
        }
        if (bytes.length == 0) {
            throw BusinessException.badRequest("RECEIPT_IMAGE_REQUIRED", "Vui lòng chụp hoặc chọn ảnh biên lai thu tiền.");
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw BusinessException.badRequest("FILE_SIZE_EXCEEDED", "Ảnh biên lai tối đa 5MB.");
        }
        String format = DeliveryProofStorageService.detectFormat(bytes);
        if (format == null) {
            throw BusinessException.badRequest("INVALID_IMAGE_DATA", "Tệp không phải ảnh JPG / PNG hợp lệ.");
        }
        DeliveryProofStorageService.checkDimensions(bytes);
        String name = "rc_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + format;
        Path target = directory.resolve(name).normalize();
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.error("Lỗi ghi ảnh biên lai {}", target, e);
            throw BusinessException.badRequest("FILE_WRITE_ERROR", "Không lưu được ảnh biên lai, vui lòng thử lại.");
        }
        return name;
    }

    /** Đọc lại ảnh đã lưu; tên lạ / không có tệp -> 404. */
    public LoadedFile load(String fileName) {
        Path path = resolve(fileName);
        if (path == null || !Files.isRegularFile(path)) {
            throw BusinessException.notFound("Không tìm thấy ảnh biên lai");
        }
        try {
            return new LoadedFile(Files.readAllBytes(path), fileName.endsWith(".png") ? "image/png" : "image/jpeg");
        } catch (IOException e) {
            throw BusinessException.notFound("Không đọc được ảnh biên lai");
        }
    }

    /** Xoá tệp vừa lưu khi giao dịch lưu phiếu thu thất bại (lỗi xoá chỉ ghi log). */
    public void deleteQuietly(String fileName) {
        Path path = resolve(fileName);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Không xoá được ảnh biên lai {}", path);
        }
    }

    private Path resolve(String fileName) {
        if (!isStoredName(fileName)) {
            return null;
        }
        Path path = directory.resolve(fileName).normalize();
        return path.startsWith(directory) ? path : null;
    }
}
