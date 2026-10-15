package com.erp.backend.service;

import com.erp.backend.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * S6-08: Lưu ảnh biên bản giao hàng có chữ ký.
 * - Chỉ JPG / PNG, tối đa 5MB, kiểm tra đúng là ảnh (đọc được bằng ImageIO), không tin đuôi tệp / content-type.
 * - Lưu ở thư mục uploads/delivery-proofs: KHÔNG có đường dẫn tĩnh nào phục vụ thư mục này (chỉ /uploads/avatars),
 *   xem lại qua API có kiểm quyền. Thư mục uploads/ đã nằm trong .gitignore.
 * - Tên tệp do hệ thống sinh, chặn đọc ra ngoài thư mục (path traversal).
 */
@Slf4j
@Service
public class DeliveryProofStorageService {

    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    static final int MAX_DIMENSION = 12000;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,100}\\.(jpg|png)");

    private final Path directory;

    public DeliveryProofStorageService(
            @Value("${erp.app.upload.delivery-proof-dir:uploads/delivery-proofs}") String dir) {
        this.directory = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được thư mục lưu biên bản giao hàng: " + directory, e);
        }
    }

    public record StoredFile(String fileName, String contentType) {
    }

    public record LoadedFile(byte[] content, String contentType) {
    }

    /** Kiểm tra và lưu ảnh; trả về tên tệp + content-type thực tế. */
    public StoredFile store(Long orderId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("FILE_EMPTY", "Vui lòng chọn ảnh biên bản giao hàng.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw BusinessException.badRequest("FILE_SIZE_EXCEEDED", "Ảnh biên bản tối đa 5MB.");
        }
        String original = file.getOriginalFilename();
        String ext = extension(original);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw BusinessException.badRequest("INVALID_IMAGE_EXTENSION", "Chỉ nhận ảnh JPG hoặc PNG.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.badRequest("IMAGE_READ_ERROR", "Không đọc được tệp ảnh, vui lòng thử lại.");
        }
        String format = detectFormat(bytes);
        if (format == null) {
            throw BusinessException.badRequest("INVALID_IMAGE_DATA", "Tệp không phải ảnh JPG / PNG hợp lệ.");
        }
        checkDimensions(bytes);
        String name = "order_" + orderId + "_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + "." + format;
        Path target = directory.resolve(name).normalize();
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.error("Lỗi ghi ảnh biên bản giao hàng {}", target, e);
            throw BusinessException.badRequest("FILE_WRITE_ERROR", "Không lưu được ảnh biên bản, vui lòng thử lại.");
        }
        return new StoredFile(name, "png".equals(format) ? "image/png" : "image/jpeg");
    }

    /** Đọc lại ảnh đã lưu; tên tệp lạ / không có -> 404. */
    public LoadedFile load(String fileName) {
        Path path = resolve(fileName);
        if (path == null || !Files.isRegularFile(path)) {
            throw BusinessException.notFound("Không tìm thấy ảnh biên bản giao hàng");
        }
        try {
            return new LoadedFile(Files.readAllBytes(path), fileName.endsWith(".png") ? "image/png" : "image/jpeg");
        } catch (IOException e) {
            throw BusinessException.notFound("Không đọc được ảnh biên bản giao hàng");
        }
    }

    /** Xoá ảnh cũ khi tải ảnh mới thay thế (lỗi xoá chỉ ghi log). */
    public void deleteQuietly(String fileName) {
        Path path = resolve(fileName);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Không xoá được ảnh biên bản cũ {}", path);
        }
    }

    private Path resolve(String fileName) {
        if (!StringUtils.hasText(fileName) || !SAFE_NAME.matcher(fileName).matches()) {
            return null;
        }
        Path path = directory.resolve(fileName).normalize();
        return path.startsWith(directory) ? path : null;
    }

    /**
     * Đọc kích thước ảnh từ phần đầu tệp (không giải nén cả ảnh -> không tốn bộ nhớ với ảnh "bom" kích thước lớn).
     */
    static void checkDimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in != null ? ImageIO.getImageReaders(in) : null;
            if (readers == null || !readers.hasNext()) {
                throw BusinessException.badRequest("INVALID_IMAGE_DATA", "Tệp không phải ảnh hợp lệ hoặc ảnh bị hỏng.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) {
                    throw BusinessException.badRequest("INVALID_IMAGE_SIZE",
                            "Kích thước ảnh không hợp lệ (tối đa " + MAX_DIMENSION + " x " + MAX_DIMENSION + " điểm ảnh).");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw BusinessException.badRequest("INVALID_IMAGE_DATA", "Tệp không phải ảnh hợp lệ hoặc ảnh bị hỏng.");
        }
    }

    /** Nhận dạng theo chữ ký đầu tệp (magic bytes): PNG / JPEG; khác -> null. */
    static String detectFormat(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        return null;
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
