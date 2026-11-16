package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcsWriterTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    private static final LocalDateTime NOV = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final LocalDateTime JUL = LocalDateTime.of(2026, 7, 1, 10, 0);
    private static final Instant NOW = Instant.parse("2026-11-10T09:30:00Z");

    private static Instant campusToInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(CAMPUS_ZONE).toInstant();
    }

    @Test
    void emptyCalendarIsValid() {
        String ics = IcsWriter.write("Campus Hub", List.of(), NOW);

        assertThat(ics)
                .as("empty calendar must begin with VCALENDAR/VERSION header; see IcsWriter.write")
                .startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        assertThat(ics)
                .as("empty calendar must declare Campus Hub PRODID; see IcsWriter.write")
                .contains("PRODID:-//Campus Hub//Reservations//EN\r\n");
        assertThat(ics)
                .as("empty calendar must end with END:VCALENDAR CRLF; see IcsWriter.write")
                .endsWith("END:VCALENDAR\r\n");
        assertThat(ics)
                .as("empty calendar must contain no VEVENT blocks; see IcsWriter.write")
                .doesNotContain("BEGIN:VEVENT");
    }

    @Test
    void everyLineEndsWithCrlf() {
        CalendarEvent event = new CalendarEvent(
                "reservation-1@campus-hub",
                campusToInstant(NOV),
                campusToInstant(NOV.plusHours(2)),
                "Sursă DP832, canal 2; nouă " + "x".repeat(80),
                "Line 1\nLine 2\r\nLine 3");

        String ics = IcsWriter.write("Campus Hub", List.of(event), NOW);
        String stripped = ics.replace("\r\n", "");

        assertThat(stripped)
                .as("every line break must be CRLF, leaving no bare LF or CR; see IcsWriter.write")
                .doesNotContain("\n")
                .doesNotContain("\r");
    }

    @Test
    void writesOneEventPerReservation() {
        CalendarEvent first = new CalendarEvent(
                "reservation-1@campus-hub",
                campusToInstant(NOV),
                campusToInstant(NOV.plusHours(2)),
                "Oscilloscope #1",
                "First booking");
        CalendarEvent second = new CalendarEvent(
                "reservation-2@campus-hub",
                campusToInstant(NOV.plusDays(1)),
                campusToInstant(NOV.plusDays(1).plusHours(2)),
                "Multimeter #2",
                "Second booking");

        String ics = IcsWriter.write("Campus Hub", List.of(first, second), NOW);

        int firstBegin = ics.indexOf("BEGIN:VEVENT");
        int secondBegin = ics.indexOf("BEGIN:VEVENT", firstBegin + 1);
        int firstUid = ics.indexOf("UID:reservation-1@campus-hub");
        int secondUid = ics.indexOf("UID:reservation-2@campus-hub");

        assertThat(ics.split("BEGIN:VEVENT", -1).length - 1)
                .as("output must contain one BEGIN:VEVENT per input event; see IcsWriter.write")
                .isEqualTo(2);
        assertThat(ics.split("END:VEVENT", -1).length - 1)
                .as("output must contain one END:VEVENT per input event; see IcsWriter.write")
                .isEqualTo(2);
        assertThat(firstBegin < firstUid && firstUid < secondBegin && secondBegin < secondUid)
                .as("events must be written in the given order; see IcsWriter.write")
                .isTrue();
    }

    @Test
    void convertsWinterTimeToUtc() {
        CalendarEvent event = new CalendarEvent(
                "reservation-10@campus-hub",
                campusToInstant(NOV),
                campusToInstant(NOV.plusHours(2)),
                "Oscilloscope",
                "Winter slot");

        String ics = IcsWriter.write("Campus Hub", List.of(event), NOW);

        assertThat(ics)
                .as("winter campus time (UTC+2) 10:00 must serialize as 08:00Z; see IcsWriter.write")
                .contains("DTSTART:20261116T080000Z\r\n");
    }

    @Test
    void convertsSummerTimeToUtc() {
        CalendarEvent event = new CalendarEvent(
                "reservation-11@campus-hub",
                campusToInstant(JUL),
                campusToInstant(JUL.plusHours(2)),
                "Oscilloscope",
                "Summer slot");

        String ics = IcsWriter.write("Campus Hub", List.of(event), NOW);

        assertThat(ics)
                .as("summer campus time (UTC+3) 10:00 must serialize as 07:00Z; see IcsWriter.write")
                .contains("DTSTART:20260701T070000Z\r\n");
    }

    @Test
    void dtstampIsNowInUtc() {
        CalendarEvent event = new CalendarEvent(
                "reservation-12@campus-hub",
                campusToInstant(NOV),
                campusToInstant(NOV.plusHours(1)),
                "Oscilloscope",
                "Stamp check");

        String ics = IcsWriter.write("Campus Hub", List.of(event), NOW);

        assertThat(ics)
                .as("DTSTAMP must serialize the provided Instant in UTC; see IcsWriter.write")
                .contains("DTSTAMP:20261110T093000Z\r\n");
    }

    @Test
    void uidIsWrittenAsGiven() {
        CalendarEvent event = new CalendarEvent(
                "reservation-123@campus-hub",
                campusToInstant(NOV),
                campusToInstant(NOV.plusHours(1)),
                "Oscilloscope",
                "UID check");

        String ics = IcsWriter.write("Campus Hub", List.of(event), NOW);

        assertThat(ics)
                .as("UID must be emitted verbatim for stable updates; see IcsWriter.write")
                .contains("UID:reservation-123@campus-hub\r\n");
    }

    @Test
    void escapesSpecialCharacters() {
        String escaped = IcsWriter.escape("a,b;c\\d\ne");

        assertThat(escaped)
                .as("comma, semicolon, backslash, and newline must be escaped per RFC 5545; see IcsWriter.escape")
                .isEqualTo("a\\,b\\;c\\\\d\\ne");
    }

    @Test
    void foldsLinesLongerThan75Bytes() {
        String original = "SUMMARY:" + "A".repeat(200);

        List<String> physicalLines = IcsWriter.fold(original);

        assertThat(physicalLines.size())
                .as("208-byte ASCII line must be folded into multiple physical lines; see IcsWriter.fold")
                .isGreaterThan(1);
        for (int i = 0; i < physicalLines.size(); i++) {
            String line = physicalLines.get(i);
            assertThat(line.getBytes(StandardCharsets.UTF_8).length)
                    .as("physical line %d must be at most 75 UTF-8 bytes; see IcsWriter.fold", i)
                    .isLessThanOrEqualTo(75);
            if (i > 0) {
                assertThat(line)
                        .as("continuation line %d must begin with a single space; see IcsWriter.fold", i)
                        .startsWith(" ");
            }
        }
        String unfolded = String.join("\r\n", physicalLines).replace("\r\n ", "");
        assertThat(unfolded)
                .as("unfolding continuation lines must recover the exact original line; see IcsWriter.fold")
                .isEqualTo(original);
    }

    @Test
    void foldsWithoutSplittingAMultiByteLetter() {
        String original = "SUMMARY:" + "ș".repeat(100);

        List<String> physicalLines = IcsWriter.fold(original);

        assertThat(physicalLines.size())
                .as("100 multi-byte letters must fold across multiple lines; see IcsWriter.fold")
                .isGreaterThan(1);
        for (int i = 0; i < physicalLines.size(); i++) {
            byte[] utf8 = physicalLines.get(i).getBytes(StandardCharsets.UTF_8);
            assertThat(utf8.length)
                    .as("physical line %d must be at most 75 UTF-8 bytes; see IcsWriter.fold", i)
                    .isLessThanOrEqualTo(75);
            assertThat(new String(utf8, StandardCharsets.UTF_8))
                    .as("physical line %d must round-trip valid UTF-8 without replacement chars; see IcsWriter.fold", i)
                    .isEqualTo(physicalLines.get(i))
                    .doesNotContain("\uFFFD");
        }
        String unfolded = String.join("\r\n", physicalLines).replace("\r\n ", "");
        assertThat(unfolded)
                .as("unfolding multi-byte lines must recover the exact original text; see IcsWriter.fold")
                .isEqualTo(original);
    }

    @Test
    void shortLineIsNotFolded() {
        String shortLine = "SUMMARY:Oscilloscope DS1054Z #2";

        List<String> physicalLines = IcsWriter.fold(shortLine);

        assertThat(physicalLines)
                .as("line within 75 bytes must stay a single unfolded line; see IcsWriter.fold")
                .containsExactly(shortLine);
    }
}
