package com.campus.storage.service;

import com.campus.common.error.NotFoundException;
import com.campus.storage.api.StorageService;
import com.campus.storage.service.StorageValidation.ValidatedPhoto;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Local-disk {@link StorageService} implementation for {@code dev} and {@code test} profiles
 * ({@code @Profile("!prod")}). Matei's {@code S3StorageService} handles {@code prod}.
 */
@Slf4j
@Service
@Profile("!prod")
public class LocalStorageService implements StorageService {

    private final Path rootDir;

    // Default value "./uploads" ensures @SpringBootTest contexts start cleanly under the "test"
    // profile without needing app.storage.local-dir in application-test.yml.
    public LocalStorageService(@Value("${app.storage.local-dir:./uploads}") String localDir) {
        try {
            Path created = Files.createDirectories(Path.of(localDir));
            this.rootDir = created.toRealPath();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to initialize local storage directory: " + localDir, ex);
        }
    }

    @Override
    public String store(InputStream in, long size, String contentType, String ext) {
        ValidatedPhoto validated = StorageValidation.validateAndRead(in, size, contentType, ext);
        String key = UUID.randomUUID() + "." + validated.canonicalExtension();
        Path target = resolveUnderRoot(key);

        try {
            Files.write(target, validated.bytes(), StandardOpenOption.CREATE_NEW);
            log.info(
                    "Stored local photo: key={}, size={}, contentType={}",
                    key,
                    validated.bytes().length,
                    validated.contentType());
            return key;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write photo to local storage: " + key, ex);
        }
    }

    @Override
    public Resource load(String key) {
        Path target = resolveUnderRoot(key);
        if (!Files.isRegularFile(target)) {
            throw new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found");
        }
        return new FileSystemResource(target);
    }

    @Override
    public void delete(String key) {
        Path target = resolveUnderRoot(key);
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("Deleted local photo: key={}", key);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to delete photo from local storage: " + key, ex);
        }
    }

    // Defense in depth: checks both the strict <uuid>.<ext> pattern and normalized path containment
    // so traversal inputs ("../../...") can never escape rootDir.
    private Path resolveUnderRoot(String key) {
        StorageValidation.requireValidKeyFormat(key);
        Path resolved = rootDir.resolve(key).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found");
        }
        return resolved;
    }
}
