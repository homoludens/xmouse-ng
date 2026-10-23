package com.stripe1.xmouse.layout;

/**
 * The shared reader for the two legacy layout record formats. Keep the delimiter detection,
 * field selection, and missing-color behavior aligned with the legacy control-screen reader.
 */
public final class LegacyLayoutReader {
    private static final String SEPARATOR = "<xmousesep>";

    private LegacyLayoutReader() {
    }

    public static Record parseRecord(String line, String missingColor) {
        boolean separatorFormat = line.contains(SEPARATOR);
        String[] fields;
        if (separatorFormat) {
            fields = line.split(SEPARATOR);
        } else {
            fields = line.split(",");
        }

        String color = separatorFormat && fields.length > 3 ? fields[3] : missingColor;
        return new Record(fields[0], fields[1], Integer.valueOf(fields[2]), color, separatorFormat);
    }

    public static final class Record {
        private final String title;
        private final String command;
        private final int span;
        private final String color;
        private final boolean separatorFormat;

        private Record(String title, String command, int span, String color, boolean separatorFormat) {
            this.title = title;
            this.command = command;
            this.span = span;
            this.color = color;
            this.separatorFormat = separatorFormat;
        }

        public String getTitle() {
            return title;
        }

        public String getCommand() {
            return command;
        }

        public int getSpan() {
            return span;
        }

        public String getColor() {
            return color;
        }

        public boolean isSeparatorFormat() {
            return separatorFormat;
        }
    }
}
