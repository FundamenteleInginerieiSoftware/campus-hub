package com.campus.project.web;

import java.util.List;

public record ProjectSearchResponse(boolean ambiguous, List<ProjectListItem> items) {}
