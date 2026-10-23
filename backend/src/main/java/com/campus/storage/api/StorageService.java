package com.campus.storage.api;

import java.io.InputStream;
import org.springframework.core.io.Resource;

public interface StorageService {
    String store(InputStream in, long size, String contentType, String ext);

    Resource load(String key);

    void delete(String key);
}
