package com.stripe1.xmouse;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Characterizes synthetic legacy layout fixture contents without a migration/parser implementation. */
public class LegacyLayoutFixtureTest {
    private static final String FIXTURE_ROOT = "/legacy/layouts/";
    private static final String WHITE = "#FFFFFF";
    private static final String SEPARATOR = "<xmousesep>";

    @Test
    public void activeAndPresetFixturesPreserveOrderedLegacyFields() throws IOException {
        ExpectedButton[] activeComma = {
                button("Terminal", "xdotool key ctrl+alt+t", 4, WHITE, false),
                button("Files", "xdotool key super+e", 3, WHITE, false)
        };
        ExpectedButton[] activeSeparated = {
                button("Terminal", "xdotool key ctrl+alt+t", 4, "#DDEEFF", true),
                button("Files", "xdotool key super+e", 3, WHITE, false)
        };
        ExpectedButton[] presetOneComma = {
                button("Address bar", "xdotool key ctrl+l", 3, WHITE, false),
                button("Reload", "xdotool key ctrl+r", 2, WHITE, false)
        };
        ExpectedButton[] presetOneSeparated = {
                button("Address bar", "xdotool key ctrl+l", 3, "#CFE8FF", true),
                button("Reload", "xdotool key ctrl+r", 2, "#F5C2E7", true)
        };
        ExpectedButton[] presetTwoComma = {
                button("Next desktop", "xdotool set_desktop 1", 4, WHITE, false),
                button("Previous desktop", "xdotool set_desktop 0", 4, WHITE, false)
        };
        ExpectedButton[] presetTwoSeparated = {
                button("Next desktop", "xdotool set_desktop 1", 4, "#D8EAD2", true),
                button("Previous desktop", "xdotool set_desktop 0", 4, "#FFE599", true)
        };
        ExpectedButton[] presetThreeComma = {
                button("Play", "xdotool key XF86AudioPlay", 3, WHITE, false),
                button("Mute", "xdotool key XF86AudioMute", 2, WHITE, false)
        };
        ExpectedButton[] presetThreeSeparated = {
                button("Play", "xdotool key XF86AudioPlay", 3, "#D9EAD3", true),
                button("Mute", "xdotool key XF86AudioMute", 2, "#C9DAF8", true)
        };

        assertLayoutFixture("comma/keyLayoutFile.csv", ",", activeComma);
        assertLayoutFixture("xmousesep/keyLayoutFile.csv", SEPARATOR, activeSeparated);
        assertLayoutFixture("comma/keyFileOne.csv", ",", presetOneComma);
        assertLayoutFixture("xmousesep/keyFileOne.csv", SEPARATOR, presetOneSeparated);
        assertLayoutFixture("comma/keyFileTwo.csv", ",", presetTwoComma);
        assertLayoutFixture("xmousesep/keyFileTwo.csv", SEPARATOR, presetTwoSeparated);
        assertLayoutFixture("comma/keyFileThree.csv", ",", presetThreeComma);
        assertLayoutFixture("xmousesep/keyFileThree.csv", SEPARATOR, presetThreeSeparated);

        assertSameButtonsAcrossFormats("active layout", activeComma, activeSeparated);
        assertSameButtonsAcrossFormats("preset one", presetOneComma, presetOneSeparated);
        assertSameButtonsAcrossFormats("preset two", presetTwoComma, presetTwoSeparated);
        assertSameButtonsAcrossFormats("preset three", presetThreeComma, presetThreeSeparated);

        assertPresetsAreDistinct("comma");
        assertPresetsAreDistinct("xmousesep");
    }

    @Test
    public void malformedExamplesAreSeparateAndUnambiguous() throws IOException {
        String invalidSpan = singleRecord("malformed/invalid-span-xmousesep.csv");
        assertEquals("Too wide<xmousesep>xdotool key ctrl+w<xmousesep>11<xmousesep>#FF0000", invalidSpan);
        String[] separatedFields = invalidSpan.split(SEPARATOR);
        assertEquals(4, separatedFields.length);
        int span = Integer.parseInt(separatedFields[2]);
        assertEquals(11, span);
        assertTrue("fixture span must be outside the legacy 1..10 range", span < 1 || span > 10);

        String truncatedComma = singleRecord("malformed/truncated-comma.csv");
        assertEquals("Missing span,xdotool key ctrl+x", truncatedComma);
        assertEquals("fixture must omit the third (span) column", 2, truncatedComma.split(",", -1).length);
    }

    private static ExpectedButton button(String title, String command, int span, String color, boolean hasColorColumn) {
        return new ExpectedButton(title, command, span, color, hasColorColumn);
    }

    private static void assertLayoutFixture(String relativePath, String delimiter, ExpectedButton... expected)
            throws IOException {
        String[] lines = readFixture(relativePath).split("\n");
        assertEquals("record count for " + relativePath, expected.length, lines.length);

        for (int i = 0; i < expected.length; i++) {
            ExpectedButton button = expected[i];
            String[] fields = lines[i].split(java.util.regex.Pattern.quote(delimiter));
            String record = relativePath + " record " + (i + 1);
            assertEquals(record + " legacy column count", button.hasColorColumn ? 4 : 3, fields.length);
            assertEquals(record + " title/order", button.title, fields[0]);
            assertEquals(record + " command", button.command, fields[1]);
            assertEquals(record + " span", button.span, Integer.parseInt(fields[2]));
            String color = fields.length == 4 ? fields[3] : WHITE;
            assertEquals(record + " color/default", button.color, color);
        }
    }

    private static void assertSameButtonsAcrossFormats(String source, ExpectedButton[] comma, ExpectedButton[] separated) {
        assertEquals(source + " record count", comma.length, separated.length);
        for (int i = 0; i < comma.length; i++) {
            assertEquals(source + " title/order at " + i, comma[i].title, separated[i].title);
            assertEquals(source + " command at " + i, comma[i].command, separated[i].command);
            assertEquals(source + " span at " + i, comma[i].span, separated[i].span);
        }
    }

    private static void assertPresetsAreDistinct(String formatDirectory) throws IOException {
        String one = readFixture(formatDirectory + "/keyFileOne.csv");
        String two = readFixture(formatDirectory + "/keyFileTwo.csv");
        String three = readFixture(formatDirectory + "/keyFileThree.csv");
        assertNotEquals(formatDirectory + " preset one and two", one, two);
        assertNotEquals(formatDirectory + " preset one and three", one, three);
        assertNotEquals(formatDirectory + " preset two and three", two, three);
    }

    private static String singleRecord(String relativePath) throws IOException {
        String[] lines = readFixture(relativePath).split("\n");
        assertEquals("one malformed record in " + relativePath, 1, lines.length);
        return lines[0];
    }

    private static String readFixture(String relativePath) throws IOException {
        String resource = FIXTURE_ROOT + relativePath;
        try (InputStream input = LegacyLayoutFixtureTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing legacy layout fixture: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class ExpectedButton {
        private final String title;
        private final String command;
        private final int span;
        private final String color;
        private final boolean hasColorColumn;

        private ExpectedButton(String title, String command, int span, String color, boolean hasColorColumn) {
            this.title = title;
            this.command = command;
            this.span = span;
            this.color = color;
            this.hasColorColumn = hasColorColumn;
        }
    }
}
