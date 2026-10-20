package com.erp.backend.service;

import com.erp.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S7-05: Lưu ảnh biên lai thu tiền tại tuyến từ data URL base64. */
class ReceiptImageStorageServiceTest {

    @TempDir
    Path dir;

    private ReceiptImageStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new ReceiptImageStorageService(dir.toString());
    }

    private static String pngDataUrl() throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    @DisplayName("Ảnh PNG hợp lệ (lớn hơn nhiều so với 1000 ký tự cũ) được lưu thành tệp và đọc lại đúng nội dung")
    void storeAndLoad_validPng() throws IOException {
        String dataUrl = pngDataUrl();

        String name = storage.storeDataUrl(dataUrl);

        assertThat(ReceiptImageStorageService.isStoredName(name)).isTrue();
        assertThat(name).endsWith(".png");
        assertThat(Files.exists(dir.resolve(name))).isTrue();
        ReceiptImageStorageService.LoadedFile f = storage.load(name);
        assertThat(f.contentType()).isEqualTo("image/png");
        assertThat(f.content()).isEqualTo(Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1)));
    }

    @Test
    @DisplayName("Không phải ảnh (đổi đuôi / nội dung chữ) -> 400 INVALID_IMAGE_DATA, không ghi tệp")
    void rejectNonImage() throws IOException {
        String fake = "data:image/png;base64," + Base64.getEncoder().encodeToString("<script>alert(1)</script>".getBytes());

        assertThatThrownBy(() -> storage.storeDataUrl(fake))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_IMAGE_DATA");
        try (var files = Files.list(dir)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    @DisplayName("Định dạng khác JPG / PNG hoặc rỗng -> 400")
    void rejectWrongTypeOrEmpty() {
        assertThatThrownBy(() -> storage.storeDataUrl("data:image/gif;base64,R0lGODlh"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_IMAGE_DATA");
        assertThatThrownBy(() -> storage.storeDataUrl("  "))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("RECEIPT_IMAGE_REQUIRED");
    }

    @Test
    @DisplayName("Ảnh trên 5MB -> 400 FILE_SIZE_EXCEEDED (chặn trước khi giải mã)")
    void rejectTooLarge() {
        String big = "data:image/jpeg;base64," + "A".repeat(7_200_000);

        assertThatThrownBy(() -> storage.storeDataUrl(big))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FILE_SIZE_EXCEEDED");
    }

    @Test
    @DisplayName("Tên tệp lạ / cố đọc ra ngoài thư mục -> 404")
    void loadRejectsUnknownOrTraversal() {
        assertThatThrownBy(() -> storage.load("../application.properties"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.load("rc_khong_ton_tai.jpg"))
                .isInstanceOf(BusinessException.class);
    }
}
