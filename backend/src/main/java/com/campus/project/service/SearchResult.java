package com.campus.project.service;

import java.util.List;

public record SearchResult(List<ScoredProject> hits, boolean ambiguous) {}
