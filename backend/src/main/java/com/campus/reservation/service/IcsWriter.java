package com.campus.reservation.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure RFC 5545 iCalendar ({@code .ics}) serializer with zero Spring dependencies.
 *
 * <p>Calendar clients (Google Calendar, Apple Calendar, Outlook) are strict about three format
 * rules, each pinned by {@code IcsWriterTest}:
 * <ol>
 *   <li>Every line ends with CRLF ({@code \r\n}), never bare {@code \n}.</li>
 *   <li>Text values escape {@code \}, {@code ;}, {@code ,} and encode line breaks as {@code \n}.</li>
 *   <li>Content lines exceeding 75 UTF-8 bytes are folded with CRLF + one leading space without
 *       splitting multi-byte UTF-8 characters across lines.</li>
 * </ol>
 */
public final class IcsWriter {

    private static final String CRLF = "\r\n";
    private static final String PRODID = "-//Campus Hub//Reservations//EN";
    private static final int MAX_LINE_BYTES = 75;
    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsWriter() {}

    /** Renders a complete {@code VCALENDAR} document containing all given events in order. */
    public static String write(String calendarName, List<CalendarEvent> events, Instant now) {
        StringBuilder out = new StringBuilder();
        appendFoldedLine(out, "BEGIN:VCALENDAR");
        appendFoldedLine(out, "VERSION:2.0");
        appendFoldedLine(out, "PRODID:" + PRODID);
        appendFoldedLine(out, "CALSCALE:GREGORIAN");
        appendFoldedLine(out, "METHOD:PUBLISH");
        appendFoldedLine(out, "X-WR-CALNAME:" + escape(calendarName));

        String dtStamp = UTC_TIMESTAMP.format(now);
        for (CalendarEvent event : events) {
            appendFoldedLine(out, "BEGIN:VEVENT");
            appendFoldedLine(out, "UID:" + escape(event.uid()));
            appendFoldedLine(out, "DTSTAMP:" + dtStamp);
            appendFoldedLine(out, "DTSTART:" + UTC_TIMESTAMP.format(event.start()));
            appendFoldedLine(out, "DTEND:" + UTC_TIMESTAMP.format(event.end()));
            appendFoldedLine(out, "SUMMARY:" + escape(event.summary()));
            appendFoldedLine(out, "DESCRIPTION:" + escape(event.description()));
            appendFoldedLine(out, "END:VEVENT");
        }

        appendFoldedLine(out, "END:VCALENDAR");
        return out.toString();
    }

    /** Escapes RFC 5545 text delimiters ({@code \}, {@code ;}, {@code ,}) and line breaks. */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case ';' -> sb.append("\\;");
                case ',' -> sb.append("\\,");
                case '\r' -> {
                    // Collapse CRLF into a single escaped \n so no bare CR leaks into the output.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    sb.append("\\n");
                }
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Splits a logical iCalendar content line into physical lines of at most 75 UTF-8 bytes.
     * Continuation lines start with a single space (taking 1 byte, leaving 74 bytes for content)
     * and never cut in the middle of a multi-byte Unicode code point.
     */
    public static List<String> fold(String line) {
        if (line == null || line.isEmpty()) {
            return List.of("");
        }
        List<String> folded = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;

        int i = 0;
        while (i < line.length()) {
            int codePoint = line.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int cpBytes = utf8ByteLength(codePoint);

            if (currentBytes + cpBytes > MAX_LINE_BYTES && currentBytes > 0) {
                folded.add(current.toString());
                current = new StringBuilder(" ");
                currentBytes = 1;
            }
            current.appendCodePoint(codePoint);
            currentBytes += cpBytes;
            i += charCount;
        }
        folded.add(current.toString());
        return List.copyOf(folded);
    }

    private static void appendFoldedLine(StringBuilder out, String line) {
        for (String physicalLine : fold(line)) {
            out.append(physicalLine).append(CRLF);
        }
    }

    private static int utf8ByteLength(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
