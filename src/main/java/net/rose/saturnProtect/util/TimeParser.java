package net.rose.saturnProtect.util;

import java.util.Locale;

public final class TimeParser {

    private TimeParser() {
    }

    /**
     * Parses durations like {@code 3d}, {@code 1w2d}, {@code 12h30m}, {@code 45s}.
     * Units: w, d, h, m, s.
     *
     * @return duration in milliseconds
     * @throws IllegalArgumentException if the text is not a valid duration
     */
    public static long parseMillis(String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Empty time value.");
        }

        long total = 0L;
        long number = -1L;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                number = (number < 0 ? 0 : number * 10) + (c - '0');
                if (number > 100_000_000L) {
                    throw new IllegalArgumentException("Time value too large: " + input);
                }
                continue;
            }
            if (number < 0) {
                throw new IllegalArgumentException("Invalid time value: " + input);
            }
            long unit = switch (c) {
                case 'w' -> 604_800_000L;
                case 'd' -> 86_400_000L;
                case 'h' -> 3_600_000L;
                case 'm' -> 60_000L;
                case 's' -> 1_000L;
                default -> throw new IllegalArgumentException("Unknown time unit '" + c + "' in: " + input);
            };
            total += number * unit;
            number = -1L;
        }
        if (number >= 0) {
            throw new IllegalArgumentException("Time value needs a unit (w, d, h, m, s): " + input);
        }
        return total;
    }
}
