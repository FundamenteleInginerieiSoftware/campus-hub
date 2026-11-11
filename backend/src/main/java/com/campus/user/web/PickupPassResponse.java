package com.campus.user.web;

/** The student's current pass: the 6-digit code, the text for the QR image, and the seconds it stays valid. */
public record PickupPassResponse(String code, String qrText, int validForSeconds) {}
