package com.campus.storage.service;

import com.campus.storage.api.StorageService;
import java.io.InputStream;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("!prod")
public class LocalStorageService implements StorageService {

    @Override
    public String store(InputStream in, long size, String contentType, String ext) {
        String normalizedExt = ext != null && ext.startsWith(".") ? ext : "." + ext;
        String key = UUID.randomUUID() + normalizedExt;
        log.info("Storage stub store: key={}, size={}, contentType={}", key, size, contentType);
        return key;
    }

    public Resource load(String key) {
        log.info("Storage stub load: key={}", key);
        return new ByteArrayResource(new byte[0]);
    }

    public void delete(String key) {
        log.info("Storage stub delete: key={}", key);
    }
}
