package com.campus.incident.service;

import org.springframework.core.io.Resource;

/** An incident photo ready to send: the file and its Content-Type (image/jpeg, image/png or image/webp). */
public record IncidentPhoto(Resource resource, String contentType) {}
