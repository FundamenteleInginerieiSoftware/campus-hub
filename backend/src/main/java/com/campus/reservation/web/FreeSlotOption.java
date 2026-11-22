package com.campus.reservation.web;

import java.time.LocalDateTime;

public record FreeSlotOption(LocalDateTime startTime, LocalDateTime endTime) {}
