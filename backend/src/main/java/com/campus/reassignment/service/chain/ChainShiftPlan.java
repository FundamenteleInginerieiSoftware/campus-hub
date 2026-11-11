package com.campus.reassignment.service.chain;

import java.util.List;

/**
 * Summary outcome of an augmenting path chain-shift search.
 */
public record ChainShiftPlan(boolean resolved, List<ChainShiftStep> steps, String explanation) {}
