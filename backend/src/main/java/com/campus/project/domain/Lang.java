package com.campus.project.domain;

import com.campus.common.error.BadRequestException;
import java.util.Locale;

public enum Lang {
    RO,
    EN;

    public static Lang parse(String value) {
        if (value == null || value.isBlank()) {
            return EN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ro" -> RO;
            case "en" -> EN;
            default -> throw new BadRequestException("VALIDATION_FAILED", "lang must be ro or en");
        };
    }
}
