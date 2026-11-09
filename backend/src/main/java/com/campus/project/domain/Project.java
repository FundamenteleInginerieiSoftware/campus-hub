package com.campus.project.domain;

import java.util.List;

public record Project(
        String slug,
        Difficulty difficulty,
        int estimatedHours,
        LocalizedText title,
        LocalizedText summary,
        Keywords keywords,
        List<ProjectComponent> components,
        List<LocalizedText> bringYourself,
        List<ProjectStep> steps,
        List<LocalizedText> mistakes) {}
