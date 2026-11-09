package com.campus.project.web;

import com.campus.project.domain.Difficulty;
import java.util.List;

public record ProjectDetailsResponse(
        String slug,
        String title,
        String summary,
        Difficulty difficulty,
        int estimatedHours,
        List<ComponentView> components,
        List<String> bringYourself,
        List<StepView> steps,
        List<String> mistakes,
        boolean slotChecked,
        boolean reservable) {}
