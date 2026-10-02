package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #911: a run that passes its deadline shows it without {@link Instant#toString()}, whose formatter's classes it would
 * be the first to initialize, and shows the same text.
 */
@DisplayName("an instant is written as Instant.toString() writes it (#911)")
class IsoInstantTest {

    // A fraction of a second of each length toString() writes, and none.
    private static final int[] NANOS = {0, 1, 999_999_999, 100_000_000, 120_000_000, 123_000_000, 1_000, 123_456_000,
            1_000_000, 123_456_789, 500};

    @Test
    @DisplayName("the earliest and latest instants, the epoch, and the edges of four-digit years and of leap days")
    void edges() {
        List<Instant> instants = new ArrayList<>(List.of(Instant.MIN, Instant.MAX, Instant.EPOCH,
                Instant.MIN.plusNanos(1), Instant.MAX.minusNanos(1), Instant.EPOCH.minusNanos(1)));
        for (String text : List.of("9999-12-31T23:59:59Z", "+10000-01-01T00:00:00Z", "0000-01-01T00:00:00Z",
                "-0001-12-31T23:59:59Z", "-9999-01-01T00:00:00Z", "-10000-01-01T00:00:00Z", "-10000-12-31T23:59:59Z",
                "-10001-12-31T23:59:59Z", "+99999-12-31T23:59:59Z", "+100000-01-01T00:00:00Z", "2000-02-29T12:00:00Z",
                "1900-02-28T23:59:59Z", "1900-03-01T00:00:00Z", "2024-02-29T00:00:00Z", "1600-02-29T00:00:00Z",
                "-0400-02-29T00:00:00Z", "-0100-03-01T00:00:00Z", "0999-01-01T00:00:00Z", "1000-01-01T00:00:00Z",
                "-0999-01-01T00:00:00Z", "-1000-01-01T00:00:00Z", "1969-12-31T23:59:59Z")) {
            Instant instant = Instant.parse(text);
            for (int nanos : NANOS) {
                instants.add(instant.plusNanos(nanos));
                instants.add(instant.minusNanos(nanos));
            }
        }
        for (long year = 1; year <= 1_000_000_000; year *= 10) {
            for (long sign : new long[] {1, -1}) {
                // The first and last second of the year, worked out with no help from java.time's formatting.
                Instant first = Instant.parse("2000-01-01T00:00:00Z").atZone(ZoneOffset.UTC)
                        .withYear((int) Math.max(-999_999_999, Math.min(999_999_999, sign * year))).toInstant();
                instants.add(first);
                instants.add(first.minusSeconds(1));
            }
        }

        for (Instant instant : instants) {
            assertEquals(instant.toString(), IsoInstant.text(instant));
        }
    }

    @Test
    @DisplayName("random instants across the whole range, and around today")
    void random() {
        Random random = new Random(911);
        long earliest = Instant.MIN.getEpochSecond();
        long latest = Instant.MAX.getEpochSecond();
        for (int i = 0; i < 200_000; i++) {
            long seconds = i % 2 == 0 ? earliest + (long) (random.nextDouble() * (latest - earliest))
                    : random.nextLong(-100_000_000_000L, 100_000_000_000L);
            int nanos = NANOS[random.nextInt(NANOS.length)];
            Instant instant = Instant.ofEpochSecond(seconds, i % 3 == 0 ? random.nextInt(1_000_000_000) : nanos);

            assertEquals(instant.toString(), IsoInstant.text(instant));
        }
    }
}
