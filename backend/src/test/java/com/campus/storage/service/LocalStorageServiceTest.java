package com.campus.storage.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.NotFoundException;
import com.campus.common.error.PayloadTooLargeException;
import com.campus.storage.api.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

class LocalStorageServiceTest {

    private static final byte[] VALID_JPEG =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
    private static final byte[] VALID_PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
    private static final byte[] VALID_WEBP =
            new byte[] {'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @TempDir
    Path tempDir;

    private Path uploadsDir;
    private LocalStorageService storageService;

    @BeforeEach
    void setUp() {
        uploadsDir = tempDir.resolve("uploads");
        storageService = new LocalStorageService(uploadsDir.toString());
    }

    @Test
    void storesLoadsAndDeletesJpegPngAndWebpPhotos() throws IOException {
        String jpgKey =
                storageService.store(new ByteArrayInputStream(VALID_JPEG), VALID_JPEG.length, "image/jpeg", ".jpeg");
        String pngKey = storageService.store(new ByteArrayInputStream(VALID_PNG), VALID_PNG.length, "image/png", null);
        String webpKey = storageService.store(
                new ByteArrayInputStream(VALID_WEBP), VALID_WEBP.length, "IMAGE/WEBP; charset=binary", "webp");

        assertTrue(jpgKey.endsWith(".jpg"));
        assertTrue(pngKey.endsWith(".png"));
        assertTrue(webpKey.endsWith(".webp"));

        assertEquals("image/jpeg", StorageService.contentTypeForKey(jpgKey));
        assertEquals("image/png", StorageService.contentTypeForKey(pngKey));
        assertEquals("image/webp", StorageService.contentTypeForKey(webpKey));

        Resource loadedJpg = storageService.load(jpgKey);
        assertArrayEquals(VALID_JPEG, loadedJpg.getContentAsByteArray());
        assertArrayEquals(VALID_PNG, storageService.load(pngKey).getContentAsByteArray());
        assertArrayEquals(VALID_WEBP, storageService.load(webpKey).getContentAsByteArray());

        storageService.delete(jpgKey);
        assertThrows(NotFoundException.class, () -> storageService.load(jpgKey));
    }

    @Test
    void rejectsDisallowedContentTypesWith400() throws IOException {
        byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

        BadRequestException htmlEx = assertThrows(
                BadRequestException.class,
                () -> storageService.store(new ByteArrayInputStream(html), html.length, "text/html", "html"));
        assertEquals("INVALID_PHOTO_TYPE", htmlEx.getCode());

        BadRequestException svgEx = assertThrows(
                BadRequestException.class,
                () -> storageService.store(new ByteArrayInputStream(html), html.length, "image/svg+xml", "svg"));
        assertEquals("INVALID_PHOTO_TYPE", svgEx.getCode());

        assertUploadDirEmpty();
    }

    @Test
    void rejectsHtmlSpoofedAsJpegViaMagicByteCheck() throws IOException {
        byte[] fakeJpeg = "<html><body>not a real jpeg</body></html>".getBytes(StandardCharsets.UTF_8);

        BadRequestException ex = assertThrows(
                BadRequestException.class,
                () -> storageService.store(new ByteArrayInputStream(fakeJpeg), fakeJpeg.length, "image/jpeg", "jpg"));

        assertEquals("INVALID_PHOTO_TYPE", ex.getCode());
        assertUploadDirEmpty();
    }

    @Test
    void rejectsPathTraversalOrMismatchedExtensionInStore() throws IOException {
        BadRequestException traversalEx = assertThrows(
                BadRequestException.class,
                () -> storageService.store(
                        new ByteArrayInputStream(VALID_JPEG), VALID_JPEG.length, "image/jpeg", "/../../etc/passwd"));
        assertEquals("INVALID_PHOTO_TYPE", traversalEx.getCode());

        BadRequestException mismatchEx = assertThrows(
                BadRequestException.class,
                () -> storageService.store(
                        new ByteArrayInputStream(VALID_JPEG), VALID_JPEG.length, "image/jpeg", "png"));
        assertEquals("INVALID_PHOTO_TYPE", mismatchEx.getCode());

        assertUploadDirEmpty();
    }

    @Test
    void rejectsPhotoOver5MbEvenWhenDeclaredSizeIsSmaller() throws IOException {
        int overLimit = (int) StorageValidation.MAX_PHOTO_BYTES + 1;
        byte[] oversized = new byte[overLimit];
        System.arraycopy(VALID_JPEG, 0, oversized, 0, VALID_JPEG.length);

        PayloadTooLargeException declaredEx = assertThrows(
                PayloadTooLargeException.class,
                () -> storageService.store(new ByteArrayInputStream(VALID_JPEG), overLimit, "image/jpeg", "jpg"));
        assertEquals("PHOTO_TOO_LARGE", declaredEx.getCode());

        PayloadTooLargeException streamEx = assertThrows(
                PayloadTooLargeException.class,
                () -> storageService.store(new ByteArrayInputStream(oversized), 128L, "image/jpeg", "jpg"));
        assertEquals("PHOTO_TOO_LARGE", streamEx.getCode());

        assertUploadDirEmpty();
    }

    @Test
    void acceptsExactSizeAndMagicByteBoundariesAndRejectsInvalidBoundaries() {
        // Exact 3-byte JPEG header with declaredSize == 0 (unknown declared length, valid stream)
        byte[] exact3ByteJpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        String jpgKey = assertDoesNotThrow(
                () -> storageService.store(new ByteArrayInputStream(exact3ByteJpeg), 0L, "image/jpeg", "jpg"));
        assertTrue(jpgKey.endsWith(".jpg"));

        // Exact 8-byte PNG header
        byte[] exact8BytePng = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String pngKey = assertDoesNotThrow(() -> storageService.store(
                new ByteArrayInputStream(exact8BytePng), exact8BytePng.length, "image/png", "png"));
        assertTrue(pngKey.endsWith(".png"));

        // Exact 12-byte WebP header
        byte[] exact12ByteWebp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        String webpKey = assertDoesNotThrow(() -> storageService.store(
                new ByteArrayInputStream(exact12ByteWebp), exact12ByteWebp.length, "image/webp", "webp"));
        assertTrue(webpKey.endsWith(".webp"));

        // Stream and declaredSize of exactly MAX_PHOTO_BYTES (5 MB boundary must be accepted)
        byte[] exactMaxJpeg = new byte[(int) StorageValidation.MAX_PHOTO_BYTES];
        System.arraycopy(exact3ByteJpeg, 0, exactMaxJpeg, 0, exact3ByteJpeg.length);
        String maxKey = assertDoesNotThrow(() -> storageService.store(
                new ByteArrayInputStream(exactMaxJpeg), StorageValidation.MAX_PHOTO_BYTES, "image/jpeg", "jpg"));
        assertTrue(maxKey.endsWith(".jpg"));

        // Negative declaredSize (< 0) is rejected
        BadRequestException negSizeEx = assertThrows(
                BadRequestException.class,
                () -> storageService.store(new ByteArrayInputStream(exact3ByteJpeg), -1L, "image/jpeg", "jpg"));
        assertEquals("INVALID_PHOTO_TYPE", negSizeEx.getCode());

        // Content-Type starting with semicolon at index 0 is rejected
        BadRequestException semiEx = assertThrows(
                BadRequestException.class,
                () -> storageService.store(new ByteArrayInputStream(exact3ByteJpeg), 3L, ";charset=utf-8", "jpg"));
        assertEquals("INVALID_PHOTO_TYPE", semiEx.getCode());
    }

    @Test
    void loadThrowsNotFoundForMissingOrTraversalKeys() throws IOException {
        Path outsideSecret = tempDir.resolve("secret.txt");
        Files.writeString(outsideSecret, "do-not-read");

        NotFoundException missingEx = assertThrows(
                NotFoundException.class, () -> storageService.load("00000000-0000-0000-0000-000000000000.jpg"));
        assertEquals("PHOTO_NOT_FOUND", missingEx.getCode());

        assertThrows(NotFoundException.class, () -> storageService.load("../secret.txt"));
        assertThrows(NotFoundException.class, () -> storageService.load("/etc/passwd"));
        assertThrows(NotFoundException.class, () -> storageService.load(null));
    }

    @Test
    void deleteIsNoOpForMissingValidKeyAndNeverDeletesOutsideRoot() throws IOException {
        Path outsideSecret = tempDir.resolve("secret.txt");
        Files.writeString(outsideSecret, "keep-me");

        assertDoesNotThrow(() -> storageService.delete("00000000-0000-0000-0000-000000000000.png"));

        assertThrows(NotFoundException.class, () -> storageService.delete("../secret.txt"));
        assertTrue(Files.exists(outsideSecret));
    }

    private void assertUploadDirEmpty() throws IOException {
        try (Stream<Path> files = Files.list(uploadsDir)) {
            assertEquals(0L, files.count());
        }
    }
}
