package com.erp.backend.service;

import com.erp.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S6-08: Lưu ảnh biên bản giao hàng: chỉ ảnh thật, thư mục riêng, chặn đọc ra ngoài thư mục. */
class DeliveryProofStorageServiceTest {

    @TempDir
    Path dir;

    private DeliveryProofStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new DeliveryProofStorageService(dir.toString());
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage img = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("S6-08: Lưu ảnh PNG thật -> tên tệp do hệ thống sinh, đọc lại đúng nội dung và content-type")
    void storeAndLoad_png() throws IOException {
        byte[] png = image("png");

        DeliveryProofStorageService.StoredFile stored = storage.store(100L,
                new MockMultipartFile("file", "../../bien-ban.PNG", "image/png", png));

        assertThat(stored.fileName()).startsWith("order_100_").endsWith(".png").doesNotContain("..");
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(Files.exists(dir.resolve(stored.fileName()))).isTrue();
        DeliveryProofStorageService.LoadedFile loaded = storage.load(stored.fileName());
        assertThat(loaded.content()).isEqualTo(png);
        assertThat(loaded.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("S6-08: Đuôi .jpg nhưng nội dung PNG -> lưu theo định dạng thật (.png)")
    void store_detectsRealFormat() throws IOException {
        DeliveryProofStorageService.StoredFile stored = storage.store(5L,
                new MockMultipartFile("file", "a.jpg", "image/jpeg", image("png")));
        assertThat(stored.fileName()).endsWith(".png");
    }

    @Test
    @DisplayName("S6-08: Lưu ảnh JPG thật")
    void store_jpg() throws IOException {
        DeliveryProofStorageService.StoredFile stored = storage.store(7L,
                new MockMultipartFile("file", "a.jpeg", "image/jpeg", image("jpg")));
        assertThat(stored.fileName()).endsWith(".jpg");
        assertThat(stored.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("S6-08: Tệp rỗng / sai đuôi / không phải ảnh / quá 5MB -> 400, không ghi tệp")
    void store_rejectsInvalid() throws IOException {
        assertThatThrownBy(() -> storage.store(1L, new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("code", "FILE_EMPTY");
        assertThatThrownBy(() -> storage.store(1L, new MockMultipartFile("file", "a.pdf", "application/pdf", image("png"))))
                .hasFieldOrPropertyWithValue("code", "INVALID_IMAGE_EXTENSION");
        assertThatThrownBy(() -> storage.store(1L, new MockMultipartFile("file", "a.png", "image/png",
                "<html>khong phai anh</html>".getBytes())))
                .hasFieldOrPropertyWithValue("code", "INVALID_IMAGE_DATA");
        byte[] big = new byte[(int) DeliveryProofStorageService.MAX_FILE_SIZE + 1];
        assertThatThrownBy(() -> storage.store(1L, new MockMultipartFile("file", "a.png", "image/png", big)))
                .hasFieldOrPropertyWithValue("code", "FILE_SIZE_EXCEEDED");
        try (var files = Files.list(dir)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    @DisplayName("S6-08: Đọc tệp bằng tên lạ / đường dẫn ra ngoài thư mục -> 404")
    void load_rejectsTraversal() throws IOException {
        Files.writeString(dir.getParent().resolve("secret.png"), "x");
        assertThatThrownBy(() -> storage.load("../secret.png"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.load("khong-co.png"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.load(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("S6-08: Xoá ảnh cũ; tên lạ thì bỏ qua không lỗi")
    void deleteQuietly_works() throws IOException {
        DeliveryProofStorageService.StoredFile stored = storage.store(9L,
                new MockMultipartFile("file", "a.png", "image/png", image("png")));
        storage.deleteQuietly(stored.fileName());
        storage.deleteQuietly("../../etc/passwd");
        assertThat(Files.exists(dir.resolve(stored.fileName()))).isFalse();
    }
}
