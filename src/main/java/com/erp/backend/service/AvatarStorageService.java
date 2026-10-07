package com.erp.backend.service;

import com.erp.backend.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Service lưu trữ và xử lý hình ảnh đại diện người dùng (S2-03).
 * Quy chuẩn:
 * - Dung lượng tối đa 2MB.
 * - Chỉ chấp nhận JPG / JPEG / PNG.
 * - Hỗ trợ cắt vuông (tự động căn giữa hoặc theo tọa độ chỉ định).
 * - Tự động tạo bản thu nhỏ (thumbnail).
 */
@Slf4j
@Service
public class AvatarStorageService {

    public static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2MB (2,097,152 bytes)
    public static final int STANDARD_AVATAR_SIZE = 400;       // 400x400 px
    public static final int THUMBNAIL_SIZE = 120;             // 120x120 px

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png");

    private final Path uploadDirectory;

    public AvatarStorageService(@Value("${erp.app.upload.avatar-dir:uploads/avatars}") String uploadDir) {
        this.uploadDirectory = Paths.get(uploadDir).toAbsolutePath().normalize();
        initStorage();
    }

    private void initStorage() {
        try {
            Files.createDirectories(uploadDirectory);
        } catch (IOException e) {
            log.error("Không thể khởi tạo thư mục lưu trữ avatar: {}", uploadDirectory, e);
            throw new RuntimeException("Không thể khởi tạo thư mục lưu ảnh đại diện: " + uploadDirectory, e);
        }
    }

    public record AvatarResult(String avatarUrl, String avatarThumbnailUrl) {}

    /**
     * Xác thực, cắt vuông, thu nhỏ và lưu ảnh avatar của người dùng.
     */
    public AvatarResult processAndStoreAvatar(Long userId, MultipartFile file, Integer x, Integer y, Integer width, Integer height) {
        validateFile(file);

        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);

        BufferedImage sourceImage = readImage(file);
        BufferedImage squareImage = cropSquare(sourceImage, x, y, width, height);

        BufferedImage standardImage = resizeImage(squareImage, STANDARD_AVATAR_SIZE, extension);
        BufferedImage thumbnailImage = resizeImage(squareImage, THUMBNAIL_SIZE, extension);

        long timestamp = System.currentTimeMillis();
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String baseName = String.format("avatar_user_%d_%d_%s", userId, timestamp, uniqueSuffix);

        String mainFileName = baseName + "." + extension;
        String thumbFileName = baseName + "_thumb." + extension;

        Path mainFilePath = uploadDirectory.resolve(mainFileName);
        Path thumbFilePath = uploadDirectory.resolve(thumbFileName);

        try {
            String formatName = extension.equalsIgnoreCase("png") ? "png" : "jpg";
            ImageIO.write(standardImage, formatName, mainFilePath.toFile());
            ImageIO.write(thumbnailImage, formatName, thumbFilePath.toFile());
        } catch (IOException e) {
            log.error("Lỗi khi ghi tệp ảnh avatar xuống đĩa: {}", mainFilePath, e);
            // Dọn dẹp nếu ghi dang dở
            deleteIfExists(mainFilePath);
            deleteIfExists(thumbFilePath);
            throw BusinessException.badRequest("FILE_WRITE_ERROR", "Không thể lưu tệp ảnh vào hệ thống. Vui lòng thử lại.");
        }

        String avatarUrl = "/uploads/avatars/" + mainFileName;
        String avatarThumbnailUrl = "/uploads/avatars/" + thumbFileName;

        return new AvatarResult(avatarUrl, avatarThumbnailUrl);
    }

    /**
     * Xoá file avatar cũ khi người dùng cập nhật ảnh mới hoặc xoá avatar.
     */
    public void deleteAvatarFiles(String avatarUrl, String avatarThumbnailUrl) {
        deleteFileByUrl(avatarUrl);
        deleteFileByUrl(avatarThumbnailUrl);
    }

    private void deleteFileByUrl(String url) {
        if (!StringUtils.hasText(url) || !url.startsWith("/uploads/avatars/")) {
            return;
        }
        String fileName = url.substring("/uploads/avatars/".length());
        Path filePath = uploadDirectory.resolve(fileName).normalize();
        if (filePath.startsWith(uploadDirectory)) {
            deleteIfExists(filePath);
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            log.warn("Không thể xoá tệp: {}", path);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("FILE_EMPTY", "Vui lòng chọn tệp ảnh để tải lên.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw BusinessException.badRequest(
                    "FILE_SIZE_EXCEEDED",
                    "Dung lượng ảnh đại diện vượt quá giới hạn cho phép (tối đa 2MB)."
            );
        }

        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw BusinessException.badRequest("INVALID_FILENAME", "Tên tệp không hợp lệ.");
        }

        String extension = getFileExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw BusinessException.badRequest(
                    "INVALID_IMAGE_EXTENSION",
                    "Định dạng tệp không được hỗ trợ. Hệ thống chỉ chấp nhận ảnh định dạng JPG hoặc PNG."
            );
        }

        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType) && !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw BusinessException.badRequest(
                    "INVALID_CONTENT_TYPE",
                    "Loại tệp không hợp lệ (" + contentType + "). Chỉ chấp nhận image/jpeg hoặc image/png."
            );
        }
    }

    private BufferedImage readImage(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            BufferedImage image = ImageIO.read(is);
            if (image == null) {
                throw BusinessException.badRequest(
                        "INVALID_IMAGE_DATA",
                        "Tệp tin không phải là hình ảnh hợp lệ hoặc dữ liệu ảnh bị hỏng."
                );
            }
            return image;
        } catch (IOException e) {
            log.error("Lỗi khi đọc luồng dữ liệu hình ảnh", e);
            throw BusinessException.badRequest(
                    "IMAGE_READ_ERROR",
                    "Không thể đọc nội dung tệp ảnh. Vui lòng kiểm tra lại tệp."
            );
        }
    }

    private BufferedImage cropSquare(BufferedImage src, Integer x, Integer y, Integer width, Integer height) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();

        // Trường hợp cung cấp toạ độ cắt ảnh từ người dùng / client (hỗ trợ cắt vuông tùy chỉnh)
        if (x != null || y != null || width != null || height != null) {
            if (x == null || y == null || width == null || height == null) {
                throw BusinessException.badRequest(
                        "INVALID_CROP_BOUNDS",
                        "Vui lòng cung cấp đầy đủ cả 4 tham số tọa độ cắt ảnh: x, y, width, height."
                );
            }

            if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > srcW || y + height > srcH) {
                throw BusinessException.badRequest(
                        "INVALID_CROP_BOUNDS",
                        String.format("Tọa độ cắt ảnh không hợp lệ (x=%d, y=%d, w=%d, h=%d). Kích thước ảnh gốc: %dx%d.",
                                x, y, width, height, srcW, srcH)
                );
            }

            int squareSize = Math.min(width, height);
            int cropX = x + (width - squareSize) / 2;
            int cropY = y + (height - squareSize) / 2;

            return src.getSubimage(cropX, cropY, squareSize, squareSize);
        }

        // Mặc định tự động cắt vuông căn giữa tâm (Center Crop)
        int squareSize = Math.min(srcW, srcH);
        int cropX = (srcW - squareSize) / 2;
        int cropY = (srcH - squareSize) / 2;

        return src.getSubimage(cropX, cropY, squareSize, squareSize);
    }

    private BufferedImage resizeImage(BufferedImage src, int targetSize, String extension) {
        boolean isPng = "png".equalsIgnoreCase(extension);
        int imageType = isPng ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        BufferedImage output = new BufferedImage(targetSize, targetSize, imageType);
        Graphics2D g2d = output.createGraphics();

        try {
            if (!isPng) {
                // Với ảnh JPG/JPEG, nền mặc định là màu trắng
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, targetSize, targetSize);
            }

            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.drawImage(src, 0, 0, targetSize, targetSize, null);
        } finally {
            g2d.dispose();
        }

        return output;
    }

    private String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return ext.equals("jpeg") ? "jpg" : ext;
    }
}
