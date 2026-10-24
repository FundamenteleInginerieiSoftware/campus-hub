package com.campus.storage.api;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * File storage abstraction used by Stefania's incident module to store and serve incident photos.
 *
 * <p>Implemented by {@code LocalStorageService} in dev/test ({@code @Profile("!prod")}) and by
 * Matei's {@code S3StorageService} in production ({@code @Profile("prod")}).
 */
public interface StorageService {

    /**
     * Stores the given stream and returns the generated storage key to persist in {@code incident_reports}.
     */
    String store(InputStream in, long size, String contentType, String ext);

    /**
     * Loads a previously stored file by its key, or throws {@code NotFoundException} if missing.
     */
    Resource load(String key);

    /**
     * Deletes the file identified by {@code key} if it exists.
     */
    void delete(String key);
}
