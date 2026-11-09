package com.campus.project.web;

import com.campus.project.domain.Difficulty;

public record ProjectListItem(
        String slug,
        String title,
        String summary,
        Difficulty difficulty,
        int estimatedHours,
        int componentCount,
        int score) {}
