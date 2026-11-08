package com.campus.storage.service;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.NotFoundException;
import com.campus.common.error.PayloadTooLargeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared validation rules for {@link LocalStorageService} and Matei's future
 * {@code S3StorageService} so dev disk storage and AWS S3 enforce identical security checks (#71).
 */
public final class StorageValidation {

    public static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;

    private static final Map<String, String> CONTENT_TYPE_TO_EXT = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private static final Pattern SAFE_KEY_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|webp)$");

    private StorageValidation() {}

    public record ValidatedPhoto(byte[] bytes, String canonicalExtension, String contentType) {}

    public static ValidatedPhoto validateAndRead(InputStream in, long declaredSize, String contentType, String ext) {
        if (declaredSize > MAX_PHOTO_BYTES) {
            throw photoTooLarge();
        }
        if (declaredSize < 0) {
            throw invalidPhotoType("Photo size cannot be negative");
        }
        if (in == null) {
            throw invalidPhotoType("Photo stream is required");
        }

        String normalizedType = normalizeContentType(contentType);
        String canonicalExt = CONTENT_TYPE_TO_EXT.get(normalizedType);
        if (canonicalExt == null) {
            throw invalidPhotoType("Only JPEG, PNG, and WebP images are allowed");
        }

        // Never trust the client extension for building the filename, but still reject bad/traversal
        // values if a caller passes one explicitly.
        validateOptionalExtension(ext, canonicalExt);

        byte[] data = readBounded(in);
        if (data.length == 0) {
            throw invalidPhotoType("Uploaded photo is empty");
        }
        if (!matchesMagicBytes(data, canonicalExt)) {
            throw invalidPhotoType("File content does not match declared image type " + normalizedType);
        }

        return new ValidatedPhoto(data, canonicalExt, normalizedType);
    }

    public static void requireValidKeyFormat(String key) {
        if (key == null || !SAFE_KEY_PATTERN.matcher(key).matches()) {
            throw new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found");
        }
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw invalidPhotoType("Photo content type is required");
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static void validateOptionalExtension(String ext, String canonicalExt) {
        if (ext == null || ext.isBlank()) {
            return;
        }
        String clean = ext.startsWith(".") ? ext.substring(1) : ext;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(lower)) {
            throw invalidPhotoType("Unsupported file extension");
        }
        String mapped = "jpeg".equals(lower) ? "jpg" : lower;
        if (!mapped.equals(canonicalExt)) {
            throw invalidPhotoType("File extension does not match content type");
        }
    }

    // Reads at most MAX_PHOTO_BYTES + 1 so a stream larger than 5 MB is rejected even when a caller
    // passes a smaller declaredSize.
    private static byte[] readBounded(InputStream in) {
        try {
            byte[] buf = in.readNBytes((int) MAX_PHOTO_BYTES + 1);
            if (buf.length > MAX_PHOTO_BYTES) {
                throw photoTooLarge();
            }
            return buf;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read uploaded photo stream", ex);
        }
    }

    // Checks the binary header ("magic bytes") so renamed HTML/script files cannot be stored as images.
    private static boolean matchesMagicBytes(byte[] b, String canonicalExt) {
        return switch (canonicalExt) {
            case "jpg" -> b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            case "png" ->
                b.length >= 8
                        && (b[0] & 0xFF) == 0x89
                        && b[1] == 0x50
                        && b[2] == 0x4E
                        && b[3] == 0x47
                        && b[4] == 0x0D
                        && b[5] == 0x0A
                        && b[6] == 0x1A
                        && b[7] == 0x0A;
            case "webp" ->
                b.length >= 12
                        && b[0] == 'R'
                        && b[1] == 'I'
                        && b[2] == 'F'
                        && b[3] == 'F'
                        && b[8] == 'W'
                        && b[9] == 'E'
                        && b[10] == 'B'
                        && b[11] == 'P';
            default -> false;
        };
    }

    private static BadRequestException invalidPhotoType(String message) {
        return new BadRequestException("INVALID_PHOTO_TYPE", message);
    }

    private static PayloadTooLargeException photoTooLarge() {
        return new PayloadTooLargeException("PHOTO_TOO_LARGE", "Uploaded photo exceeds the 5 MB limit");
    }
}
