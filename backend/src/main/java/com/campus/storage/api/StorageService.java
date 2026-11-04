package com.campus.storage.api;

import com.campus.common.error.NotFoundException;
import java.io.InputStream;
import java.util.Locale;
import org.springframework.core.io.Resource;

/**
 * File storage abstraction used by Stefania's incident module to store and serve incident photos.
 *
 * <p>Implemented by {@code LocalStorageService} in dev/test ({@code @Profile("!prod")}) and by
 * Matei's {@code S3StorageService} in production ({@code @Profile("prod")}).
 */
public interface StorageService {

    /**
     * Validates and stores an image stream (JPEG, PNG, or WebP, max 5 MB) and returns the generated
     * {@code <uuid>.<ext>} key to persist in {@code incident_reports}.
     *
     * <p>Throws {@code BadRequestException("INVALID_PHOTO_TYPE", ...)} (400) for disallowed content
     * types, extensions, empty files, or mismatched magic bytes, and
     * {@code PayloadTooLargeException("PHOTO_TOO_LARGE", ...)} (413) above 5 MB.
     */
    String store(InputStream in, long size, String contentType, String ext);

    /**
     * Loads a previously stored photo by its key, or throws {@code NotFoundException} (404) if the
     * key is invalid or the file does not exist.
     */
    Resource load(String key);

    /**
     * Deletes the photo identified by {@code key} if it exists (no-op when a valid key is already
     * absent; throws {@code NotFoundException} on malformed or traversal keys).
     */
    void delete(String key);

    /**
     * Resolves the safe {@code Content-Type} header value from a stored {@code <uuid>.<ext>} key.
     * Because {@link #store} derives extensions strictly from verified image types, Stefania's photo
     * controller can serve the exact image type alongside {@code X-Content-Type-Options: nosniff}.
     */
    static String contentTypeForKey(String key) {
        if (key == null) {
            throw new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found");
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        throw new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found");
    }
}
