package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
                .as("empty calendar must declare CALSCALE, METHOD, and X-WR-CALNAME; see IcsWriter.write")
                .contains("CALSCALE:GREGORIAN\r\n")
                .contains("METHOD:PUBLISH\r\n")
                .contains("X-WR-CALNAME:Campus Hub\r\n");
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
    void escapesCrLfBareCrAndTrailingCrWithoutDoubleNewlines() {
        assertThat(IcsWriter.escape("a\r\nb"))
                .as("CRLF must collapse to a single escaped \\n; see IcsWriter.escape")
                .isEqualTo("a\\nb");
        assertThat(IcsWriter.escape("a\rb"))
                .as(
                        "bare CR followed by another char must escape to \\n without dropping the next char; see IcsWriter.escape")
                .isEqualTo("a\\nb");
        assertThat(IcsWriter.escape("a\r"))
                .as("trailing CR at end of string must escape to \\n without out-of-bounds; see IcsWriter.escape")
                .isEqualTo("a\\n");
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

    @Test
    void foldEmptyLineReturnsSingleEmptyString() {
        assertThat(IcsWriter.fold(""))
                .as("empty line must fold to a single empty string; see IcsWriter.fold")
                .containsExactly("");
    }

    static Stream<Arguments> utf8FoldBoundaryCases() {
        return Stream.of(
                Arguments.of("75 ASCII bytes (exact limit, no fold)", "A".repeat(75), 1),
                Arguments.of("76 ASCII bytes (exceeds limit by 1, folds)", "A".repeat(76), 2),
                Arguments.of("25 three-byte € chars = 75 bytes (no fold)", "€".repeat(25), 1),
                Arguments.of("26 three-byte € chars = 78 bytes (folds)", "€".repeat(26), 2),
                Arguments.of("18 four-byte emoji = 72 bytes (no fold)", "🔧".repeat(18), 1),
                Arguments.of(
                        "19 four-byte emoji = 76 bytes (folds without splitting surrogate pair)", "🔧".repeat(19), 2),
                Arguments.of("75 x U+007F (1-byte upper boundary = 75B)", "\u007F".repeat(75), 1),
                Arguments.of("38 x U+0080 (2-byte lower boundary = 76B)", "\u0080".repeat(38), 2),
                Arguments.of("37 x U+07FF (2-byte upper boundary = 74B)", "\u07FF".repeat(37), 1),
                Arguments.of("26 x U+0800 (3-byte lower boundary = 78B)", "\u0800".repeat(26), 2),
                Arguments.of("25 x U+FFFF (3-byte upper boundary = 75B)", "\uFFFF".repeat(25), 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("utf8FoldBoundaryCases")
    void foldRespects75ByteBoundaryAcrossUtf8Widths(String description, String input, int expectedLineCount) {
        List<String> folded = IcsWriter.fold(input);

        assertThat(folded)
                .as(
                        "UTF-8 fold boundary (%s) must produce %d physical line(s); see IcsWriter.fold",
                        description, expectedLineCount)
                .hasSize(expectedLineCount);
        assertThat(String.join("\r\n", folded).replace("\r\n ", ""))
                .as("unfolding (%s) must recover original string intact; see IcsWriter.fold", description)
                .isEqualTo(input);
    }
}
