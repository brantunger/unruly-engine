package io.github.brantunger.unruly.internal;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Writes an instant as {@link Instant#toString()} does, ISO-8601 in UTC, without it. {@code toString()} formats with
 * {@link java.time.format.DateTimeFormatter#ISO_INSTANT}, whose classes, some 25 with a static initializer, the JVM's
 * first use of it would initialize, deep in a stack maybe: a run that passes its deadline, whose message shows the
 * deadline; the {@code toString()} of a rule, its builder and the message of a validity window that ends before it
 * starts, with no engine built; the {@code toString()} of a run's result or a rule set, the engine's or one the
 * application made with {@code RunResult.of} or {@code RuleSetInfo.of}, with or without an engine; and a run
 * context's (#1302). The first build formats one instant, so after it the application's own
 * {@code Instant.toString()} is safe too (see core.RunClasses). <b>Internal:</b> this class may change in any
 * release, and isn't part of the API: its package isn't exported, and the {@code api} and {@code core} packages share
 * it. It has no static initializer of its own, so its own first use deep in a stack can't break it.
 *
 * <p>
 * The text is the same: the date and the time to the second, always; a fraction of a second in groups of three
 * digits, as few as it needs, and none when it is zero; then {@code Z}. A year has at least four digits, a {@code +}
 * before it when it has more than four, and a {@code -} before it when it is before year zero.
 * </p>
 */
public final class IsoInstant {

    private static final int SECONDS_PER_DAY = 86_400;
    // The days from 0000-03-01, where the count of days in each 400-year era starts, to 1970-01-01.
    private static final long DAYS_0000_TO_1970 = 719_468;
    private static final int DAYS_PER_ERA = 146_097;
    private static final int LARGEST_FOUR_DIGIT_YEAR = 9_999;

    private IsoInstant() {
    }

    /**
     * Writes an instant as {@link Instant#toString()} does, or {@code null} as string concatenation shows it, so a
     * {@code toString()} can show a field that may be null.
     *
     * @param instant The instant, or {@code null}
     * @return The text, or {@code "null"} for a null instant
     */
    public static String text(@Nullable Instant instant) {
        if (instant == null) {
            return "null";
        }
        long seconds = instant.getEpochSecond();
        long days = Math.floorDiv(seconds, SECONDS_PER_DAY);
        int secondOfDay = Math.floorMod(seconds, SECONDS_PER_DAY);
        // The proleptic Gregorian date of the day, counted in 400-year eras that start on 1 March, so a leap day is
        // the last day of its year.
        long shifted = days + DAYS_0000_TO_1970;
        long era = Math.floorDiv(shifted, DAYS_PER_ERA);
        long dayOfEra = shifted - era * DAYS_PER_ERA;
        long yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / (DAYS_PER_ERA - 1)) / 365;
        long dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
        long monthFromMarch = (5 * dayOfYear + 2) / 153;
        int day = (int) (dayOfYear - (153 * monthFromMarch + 2) / 5 + 1);
        int month = (int) (monthFromMarch < 10 ? monthFromMarch + 3 : monthFromMarch - 9);
        long year = era * 400 + yearOfEra + (month <= 2 ? 1 : 0);

        StringBuilder text = new StringBuilder(32);
        if (year > LARGEST_FOUR_DIGIT_YEAR) {
            text.append('+').append(year);
        } else if (year < 0) {
            digits(text.append('-'), -year, 4);
        } else {
            digits(text, year, 4);
        }
        digits(text.append('-'), month, 2);
        digits(text.append('-'), day, 2);
        digits(text.append('T'), secondOfDay / 3_600, 2);
        digits(text.append(':'), secondOfDay / 60 % 60, 2);
        digits(text.append(':'), secondOfDay % 60, 2);
        int nanos = instant.getNano();
        if (nanos > 0) {
            text.append('.');
            if (nanos % 1_000_000 == 0) {
                digits(text, nanos / 1_000_000, 3);
            } else if (nanos % 1_000 == 0) {
                digits(text, nanos / 1_000, 6);
            } else {
                digits(text, nanos, 9);
            }
        }
        return text.append('Z').toString();
    }

    // Appends a number that isn't negative with zeros before it to make at least the count of digits given.
    private static void digits(StringBuilder text, long value, int count) {
        int digits = 1;
        for (long rest = value / 10; rest > 0; rest /= 10) {
            digits++;
        }
        for (int zeros = count - digits; zeros > 0; zeros--) {
            text.append('0');
        }
        text.append(value);
    }
}
