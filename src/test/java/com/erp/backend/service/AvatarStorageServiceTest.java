package com.erp.backend.service;

import com.erp.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Unit test AvatarStorageService - S2-03 Xử lý và lưu trữ ảnh đại diện")
class AvatarStorageServiceTest {

    @TempDir
    Path tempDir;

    private AvatarStorageService avatarStorageService;

    @BeforeEach
    void setUp() {
        avatarStorageService = new AvatarStorageService(tempDir.toString());
    }

    private byte[] createTestImageBytes(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, "png".equalsIgnoreCase(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setColor(Color.BLUE);
        g2.fillRect(0, 0, width, height);
        g2.setColor(Color.RED);
        g2.drawString("Test", 10, 20);
        g2.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("S2-03: Tải ảnh JPG hợp lệ (<= 2MB), tự động cắt vuông căn giữa tâm và tạo thumbnail")
    void uploadJpg_Success_AutoSquareCrop() throws IOException {
        byte[] imageBytes = createTestImageBytes(600, 400, "jpg");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "my_photo.jpg",
                "image/jpeg",
                imageBytes
        );

        AvatarStorageService.AvatarResult result = avatarStorageService.processAndStoreAvatar(1L, file, null, null, null, null);

        assertThat(result).isNotNull();
        assertThat(result.avatarUrl()).startsWith("/uploads/avatars/avatar_user_1_");
        assertThat(result.avatarUrl()).endsWith(".jpg");
        assertThat(result.avatarThumbnailUrl()).contains("_thumb.jpg");

        // Kiểm tra tệp vật lý được tạo trong thư mục
        String mainFileName = result.avatarUrl().substring("/uploads/avatars/".length());
        String thumbFileName = result.avatarThumbnailUrl().substring("/uploads/avatars/".length());

        Path mainPath = tempDir.resolve(mainFileName);
        Path thumbPath = tempDir.resolve(thumbFileName);

        assertThat(Files.exists(mainPath)).isTrue();
        assertThat(Files.exists(thumbPath)).isTrue();

        // Kiểm tra kích thước ảnh đầu ra
        BufferedImage mainImg = ImageIO.read(mainPath.toFile());
        assertThat(mainImg.getWidth()).isEqualTo(AvatarStorageService.STANDARD_AVATAR_SIZE);
        assertThat(mainImg.getHeight()).isEqualTo(AvatarStorageService.STANDARD_AVATAR_SIZE);

        BufferedImage thumbImg = ImageIO.read(thumbPath.toFile());
        assertThat(thumbImg.getWidth()).isEqualTo(AvatarStorageService.THUMBNAIL_SIZE);
        assertThat(thumbImg.getHeight()).isEqualTo(AvatarStorageService.THUMBNAIL_SIZE);
    }

    @Test
    @DisplayName("S2-03: Tải ảnh PNG hợp lệ với toạ độ cắt vuông tùy chỉnh (x, y, width, height)")
    void uploadPng_Success_CustomCrop() throws IOException {
        byte[] imageBytes = createTestImageBytes(800, 600, "png");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                imageBytes
        );

        // Cắt vùng 300x300 bắt đầu từ (50, 50)
        AvatarStorageService.AvatarResult result = avatarStorageService.processAndStoreAvatar(2L, file, 50, 50, 300, 300);

        assertThat(result).isNotNull();
        assertThat(result.avatarUrl()).endsWith(".png");
        assertThat(result.avatarThumbnailUrl()).endsWith(".png");

        String mainFileName = result.avatarUrl().substring("/uploads/avatars/".length());
        Path mainPath = tempDir.resolve(mainFileName);
        assertThat(Files.exists(mainPath)).isTrue();

        BufferedImage mainImg = ImageIO.read(mainPath.toFile());
        assertThat(mainImg.getWidth()).isEqualTo(AvatarStorageService.STANDARD_AVATAR_SIZE);
        assertThat(mainImg.getHeight()).isEqualTo(AvatarStorageService.STANDARD_AVATAR_SIZE);
    }

    @Test
    @DisplayName("S2-03: Báo lỗi khi tọa độ cắt vuông vượt ra ngoài khung hình gốc")
    void upload_ThrowsWhenCropCoordinatesOutOfBounds() throws IOException {
        byte[] imageBytes = createTestImageBytes(400, 300, "jpg");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", imageBytes);

        // x=350 + w=100 = 450 > 400
        assertThatThrownBy(() -> avatarStorageService.processAndStoreAvatar(1L, file, 350, 50, 100, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Tọa độ cắt ảnh không hợp lệ");
    }

    @Test
    @DisplayName("S2-03: Báo lỗi khi chỉ truyền một phần tham số cắt ảnh")
    void upload_ThrowsWhenPartialCropParams() throws IOException {
        byte[] imageBytes = createTestImageBytes(400, 300, "jpg");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", imageBytes);

        assertThatThrownBy(() -> avatarStorageService.processAndStoreAvatar(1L, file, 10, 10, null, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Vui lòng cung cấp đầy đủ cả 4 tham số");
    }

    @Test
    @DisplayName("S2-03: Báo lỗi khi tệp rỗng")
    void upload_ThrowsWhenFileEmpty() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> avatarStorageService.processAndStoreAvatar(1L, file, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Vui lòng chọn tệp ảnh");
    }

    @Test
    @DisplayName("S2-03: Chặn tệp vượt quá dung lượng 2MB")
    void upload_ThrowsWhenFileExceeds2MB() {
        // 2MB + 1 byte
        byte[] bigData = new byte[(int) (AvatarStorageService.MAX_FILE_SIZE + 1)];
        MockMultipartFile file = new MockMultipartFile("file", "large.jpg", "image/jpeg", bigData);

        assertThatThrownBy(() -> avatarStorageService.processAndStoreAvatar(1L, file, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tối đa 2MB");
    }

    @Test
    @DisplayName("S2-03: Chặn định dạng tệp không được hỗ trợ (vd: .gif, .pdf)")
    void upload_ThrowsWhenExtensionNotSupported() {
        MockMultipartFile file = new MockMultipartFile("file", "test.gif", "image/gif", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> avatarStorageService.processAndStoreAvatar(1L, file, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JPG hoặc PNG");
    }

    @Test
    @DisplayName("S2-03: Chặn tệp giả mạo phần mở rộng ảnh nhưng nội dung không phải ảnh")
    void upload_ThrowsWhenCorruptedOrFakeImageData() {
        byte[] fakeContent = "This is not an image file".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", fakeContent);

        assertThatThrownBy(() -> avatarStorageService.processAndStoreAvatar(1L, file, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không phải là hình ảnh hợp lệ");
    }

    @Test
    @DisplayName("S2-03: Xoá tệp vật lý ảnh đại diện cũ trên đĩa thành công")
    void deleteAvatarFiles_DeletesSuccessfully() throws IOException {
        byte[] imageBytes = createTestImageBytes(200, 200, "jpg");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", imageBytes);

        AvatarStorageService.AvatarResult result = avatarStorageService.processAndStoreAvatar(99L, file, null, null, null, null);

        String mainName = result.avatarUrl().substring("/uploads/avatars/".length());
        String thumbName = result.avatarThumbnailUrl().substring("/uploads/avatars/".length());

        Path mainPath = tempDir.resolve(mainName);
        Path thumbPath = tempDir.resolve(thumbName);

        assertThat(Files.exists(mainPath)).isTrue();
        assertThat(Files.exists(thumbPath)).isTrue();

        avatarStorageService.deleteAvatarFiles(result.avatarUrl(), result.avatarThumbnailUrl());

        assertThat(Files.exists(mainPath)).isFalse();
        assertThat(Files.exists(thumbPath)).isFalse();
    }
}
