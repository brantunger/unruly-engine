package io.github.brantunger.unruly;

import java.util.SplittableRandom;
import java.util.stream.LongStream;

/**
 * Random inputs for the property tests (#1332): code points weighted toward the ones example tests have missed, lone
 * surrogates and surrogate pairs, characters above U+00FF, combining marks, whitespace and the characters next to it,
 * and lengths at the edges of a limit. A property test is a {@code @ParameterizedTest} whose cases are
 * {@link #seeds(int)}; each case makes its own {@code PropertyInputs} from its seed.
 *
 * <p>
 * The seeds come from one base seed, {@value #DEFAULT_SEED} unless the system property {@value #SEED_PROPERTY} gives
 * another, so the build always runs the same cases and a failure can be run again. For an exploratory run, give the
 * build another: {@code ./gradlew :core:test -Dunruly.test.seed=7 --tests '*PropertyTest*'}, which the test task
 * passes on to the tests. A failing case's display name is its own seed.
 * </p>
 */
public final class PropertyInputs {

    /** The system property that replaces {@link #DEFAULT_SEED}. */
    public static final String SEED_PROPERTY = "unruly.test.seed";

    /** The base seed the build runs with. */
    public static final long DEFAULT_SEED = 1332;

    // Every character Character.isWhitespace accepts.
    private static final int[] WHITESPACE = {'\t', '\n', 0x0B, '\f', '\r', 0x1C, 0x1D, 0x1E, 0x1F, ' ', 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x205F, 0x3000};

    // The characters next to whitespace that it doesn't accept: the no-break spaces, the format characters that look
    // like nothing, and the controls that aren't whitespace.
    private static final int[] NEAR_WHITESPACE = {0x00, 0x7F, 0x85, 0xA0, 0x2007, 0x202F, 0x200B, 0xFEFF, 0x180E};

    // Characters outside the Basic Multilingual Plane, so surrogate pairs: an emoji, a Deseret capital, a tag
    // character and a variation selector, the last two escaped as two units each.
    private static final int[] SUPPLEMENTARY = {0x1F600, 0x10400, 0x10428, 0xE0041, 0xE0100, 0x1D173};

    // Raw text that reads as an escape, or nearly does: a backslash isn't escaped, so text can hold one.
    private static final String[] ESCAPE_LIKE = {"\\n", "\\r", "\\t", "\\u00e9", "\\u0041", "\\uD800", "\\u00", "\\",
        "\\x"};

    private final SplittableRandom random;

    /**
     * Inputs drawn from one seed.
     *
     * @param seed The seed, one of {@link #seeds(int)}
     */
    public PropertyInputs(long seed) {
        random = new SplittableRandom(seed);
    }

    /**
     * The seeds of a property test's cases, drawn from the base seed: the same ones on every run with the same base.
     *
     * @param count How many cases
     * @return The cases' seeds
     */
    public static LongStream seeds(int count) {
        String given = System.getProperty(SEED_PROPERTY);
        long base = given == null || given.isBlank() ? DEFAULT_SEED : Long.parseLong(given.trim());
        SplittableRandom random = new SplittableRandom(base);
        return LongStream.generate(random::nextLong).limit(count);
    }

    /**
     * A number from {@code 0} up to, not including, {@code bound}.
     *
     * @param bound The bound, positive
     * @return The number
     */
    public int below(int bound) {
        return random.nextInt(bound);
    }

    /**
     * Picks one of some values.
     *
     * @param values The values
     * @return One of them
     */
    public int oneOf(int... values) {
        return values[random.nextInt(values.length)];
    }

    /**
     * A length at the edges of a limit most of the time, from two below it to two above, and anywhere from 0 to twice
     * the limit the rest.
     *
     * @param limit The limit
     * @return The length, never negative
     */
    public int lengthAround(int limit) {
        return random.nextInt(10) < 7 ? Math.max(0, limit - 2 + random.nextInt(5)) : random.nextInt(2 * limit + 3);
    }

    /**
     * A code point, weighted toward the kinds that broke the engine before: ASCII a fifth of the time, and otherwise
     * Latin-1 above ASCII, U+00FF and the first characters past it, any character of the Basic Multilingual Plane, a
     * combining mark, whitespace or a character next to it, a lone surrogate of either half, or a surrogate pair.
     * Never {@code (}, so no text ends in what reads as the note a cut text ends with.
     *
     * @return The code point
     */
    public int codePoint() {
        return switch (random.nextInt(10)) {
            case 0, 1 -> {
                int ascii = 0x20 + random.nextInt(0x5F);
                yield ascii == '(' ? 'a' : ascii;
            }
            case 2 -> 0x80 + random.nextInt(0x80);
            case 3 -> 0xFE + random.nextInt(4);
            case 4 -> 0x100 + random.nextInt(0xFFFE - 0x100);
            case 5 -> 0x300 + random.nextInt(0x70);
            case 6 -> random.nextBoolean() ? oneOf(WHITESPACE) : oneOf(NEAR_WHITESPACE);
            case 7 -> 0xD800 + random.nextInt(0x400);
            case 8 -> 0xDC00 + random.nextInt(0x400);
            default -> oneOf(SUPPLEMENTARY);
        };
    }

    /**
     * Text of exactly {@code length} characters (UTF-16 units) of {@link #codePoint()}s, with now and then raw text
     * that reads as an escape. A surrogate pair the length falls inside is cut in half, leaving a lone surrogate, which
     * text may hold.
     *
     * @param length How many characters
     * @return The text
     */
    public String text(int length) {
        StringBuilder text = new StringBuilder(length + 6);
        while (text.length() < length) {
            if (random.nextInt(12) == 0) {
                text.append(ESCAPE_LIKE[random.nextInt(ESCAPE_LIKE.length)]);
            } else {
                text.appendCodePoint(codePoint());
            }
        }
        text.setLength(length);
        return text.toString();
    }

    /**
     * Text of up to {@code most} characters, or one more where it ends in a surrogate pair, mostly whitespace: three
     * in four code points are whitespace, one in eight is a character next to it that isn't, and one in eight any
     * {@link #codePoint()}.
     *
     * @param most The most characters
     * @return The text
     */
    public String mostlySpaces(int most) {
        int length = random.nextInt(most + 1);
        StringBuilder text = new StringBuilder(length + 1);
        while (text.length() < length) {
            int kind = random.nextInt(8);
            text.appendCodePoint(kind == 0 ? codePoint() : kind == 1 ? oneOf(NEAR_WHITESPACE) : oneOf(WHITESPACE));
        }
        return text.toString();
    }

    /**
     * Shows text as code points, so a failure message holds no control character or lone surrogate.
     *
     * @param text The text
     * @return Its code points, such as {@code U+0061 U+D800}
     */
    public static String points(String text) {
        StringBuilder points = new StringBuilder();
        text.codePoints().forEach(point -> points.append(String.format(" U+%04X", point)));
        return points.toString().trim();
    }
}
