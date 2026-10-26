package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.math.BigDecimal;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.runCheck;
import static io.github.brantunger.unruly.api.language.DoubleNumbers.doubleNumbers;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The contract test kit's {@code outputNumbersConverted}, which a language opts into with
 * {@code convertsOutputNumbers()}, must fail a language whose doubles never reach an {@code int} setter, one that
 * rounds a fraction to set one (#1127), one that narrows a double beyond the setter's type, or an infinity, to the
 * type's extreme, one that sets a {@code long} from a whole double past 2<sup>53</sup>, where a double no longer
 * counts by ones, either side of zero, one that rounds a double for a {@code long} setter, and one that rounds a
 * {@link BigDecimal}, or keeps a long one's low bits, to set an {@code int} or a {@code long} (#1264). Each of the int
 * setter's refusals, each {@code BigDecimal}, and NaN and -Infinity on the long setter, is pinned on its own: a
 * language that mishandles one infinity, NaN, or a finite whole double below an int, and nothing else fails naming
 * it, and so does one that sets a wrong {@code long} for 3.0E9 without refusing anything. Apart from
 * {@link ContractKitChecksTest}, which has to compile without {@link ExpressionCompiler#convertOutput}.
 */
@DisplayName("the contract test kit fails a language that opts into converting its numbers and doesn't")
class OutputNumbersCheckTest {

    /** The check's name. */
    private static final String CHECK = "outputNumbersConverted";

    /** A contract test for {@code language} that opts into the check. */
    private static ExpressionLanguageContractTest optingIn(ExpressionLanguage language) {
        return new ToyPropertiesContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            @Override
            protected boolean convertsOutputNumbers() {
                return true;
            }
        };
    }

    /** Wraps a language whose numbers are doubles so that its compiler converts them as {@code conversion} does. */
    private static ExpressionLanguage converting(ExpressionLanguage language, OutputConversion conversion) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ForwardingExpressionCompiler(language.newCompiler(context)) {
                    @Override
                    public Object convertOutput(Object value, Class<?> target) {
                        return conversion.convert(value, target);
                    }
                };
            }
        };
    }

    /** A conversion of a value for a target, as {@link ExpressionCompiler#convertOutput} makes one. */
    @FunctionalInterface
    private interface OutputConversion {
        Object convert(Object value, Class<?> target);
    }

    /**
     * A conversion that converts a double for an {@code int} setter only, as {@code narrowing} does once the double is
     * whole by {@code isWhole}, and refuses a fraction: the two Gotchas of the output-conversion docs, which check
     * only that the double is whole and then narrow it, so a double beyond an int, or an infinity, saturates.
     */
    private static OutputConversion wholeInts(Predicate<Double> isWhole, Function<Double, Object> narrowing) {
        return (value, target) -> {
            if (!(value instanceof Double number) || target != int.class) {
                return value;
            }
            if (!isWhole.test(number)) {
                throw new IllegalArgumentException(number + " isn't whole");
            }
            return narrowing.apply(number);
        };
    }

    /**
     * A conversion that converts as {@link DoubleNumbers#convert} does, except for a {@code long} setter, for which
     * it converts a double as {@code forLong} does: a language whose ints are right and whose longs aren't.
     */
    private static OutputConversion exceptForLongs(Function<Double, Object> forLong) {
        return (value, target) -> value instanceof Double number && target == long.class
                ? forLong.apply(number) : DoubleNumbers.convert(value, target);
    }

    /**
     * A conversion that converts as {@link DoubleNumbers#convert} does, except for the double {@code only} on an
     * {@code int} setter, which sets {@code extreme}: a language that maps one infinity to an end of the type on
     * purpose, as one whose {@code Infinity} means "no limit" might, and converts every other double exactly. No
     * accidental narrowing does this, since {@code intValue()}, a cast and {@code Math.round()} treat the two
     * infinities alike; {@code only} can't be NaN, which equals nothing.
     */
    private static OutputConversion exceptForTheInt(double only, int extreme) {
        return (value, target) -> value instanceof Double number && target == int.class && number == only
                ? extreme : DoubleNumbers.convert(value, target);
    }

    @Test
    @DisplayName("a language whose doubles are converted exactly passes")
    void faithfulDoublesPass() throws Throwable {
        runCheck(optingIn(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true))), CHECK);
    }

    @Test
    @DisplayName("a language that doesn't opt in skips the check")
    void skippedWithoutOptingIn() {
        assertThrows(TestAbortedException.class, () -> runCheck(new ToyExpressionLanguage("toy-properties", true),
                CHECK));
    }

    @Test
    @DisplayName("a language whose doubles aren't converted fails: the whole Double never reaches the int setter")
    void unconvertedDoublesFail() {
        ExpressionLanguage unconverted = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> value);

        Throwable failure = assertThrows(Throwable.class, () -> runCheck(optingIn(unconverted), CHECK));

        assertTrue(failure.getMessage().contains("has no public method setCreditScore that accepts a java.lang.Double"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose numbers are Java's, which opts in without a conversion, fails: the whole Double 1.0"
            + " it returns as it is never reaches the int setter")
    void javaNumbersWithoutAConversionFail() {
        Throwable failure = assertThrows(Throwable.class,
                () -> runCheck(optingIn(new ToyExpressionLanguage("toy-properties", true)), CHECK));

        assertTrue(failure.getMessage().contains("has no public method setCreditScore that accepts a java.lang.Double"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that rounds a fraction to set an int fails")
    void roundingFails() {
        ExpressionLanguage rounding = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> value instanceof Double number && target == int.class ? number.intValue() : value);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(rounding), CHECK));

        assertTrue(failure.getMessage().startsWith("the fraction 1.5 was set on an int"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts a fraction to zero, so the setter is called, fails")
    void zeroingFails() {
        ExpressionLanguage zeroing = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> value instanceof Double number && target == int.class
                        ? number == Math.rint(number) ? number.intValue() : 0 : value);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(zeroing), CHECK));

        assertTrue(failure.getMessage().startsWith("the fraction 1.5 was set on an int"), failure.getMessage());
    }

    @Test
    @DisplayName("a language that narrows a double it has checked is whole with intValue(), which saturates beyond an"
            + " int, fails, naming the double and the setter")
    void saturatingFails() {
        ExpressionLanguage saturating = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                wholeInts(number -> number == Math.rint(number), Double::intValue));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(saturating), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double 3.0E9 was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that checks a double is whole with Math.floor() alone, which an infinity passes, and casts"
            + " it to an int, fails, naming the double and the setter")
    void floorOnlyFails() {
        // A cast saturates as intValue() does, and Math.floor() keeps an infinity, so 3.0E9 is the first failure.
        ExpressionLanguage floorOnly = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                wholeInts(number -> number == Math.floor(number), number -> (int) number.doubleValue()));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(floorOnly), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double 3.0E9 was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts every double exactly but Infinity, which sets Integer.MAX_VALUE on an int"
            + " setter, fails, naming the double and the setter")
    void infinityAloneFails() {
        ExpressionLanguage noLimit = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForTheInt(Double.POSITIVE_INFINITY, Integer.MAX_VALUE));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(noLimit), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double Infinity was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts every double exactly but -Infinity, which sets Integer.MIN_VALUE on an int"
            + " setter, fails, naming the double and the setter")
    void negativeInfinityAloneFails() {
        ExpressionLanguage noFloor = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForTheInt(Double.NEGATIVE_INFINITY, Integer.MIN_VALUE));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(noFloor), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double -Infinity was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that refuses a double whose floor is below it, or that is out of an int's range, which NaN"
            + " is neither, and casts it, so NaN sets 0, fails, naming the double and the setter")
    void nanAloneFails() {
        // Checks written as "refuse if", which NaN passes, since every comparison with it is false: a fraction, 3.0E9
        // and both infinities are refused, and (int) NaN is 0.
        ExpressionLanguage refuseIf = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> {
                    if (!(value instanceof Double number) || target != int.class) {
                        return DoubleNumbers.convert(value, target);
                    }
                    if (Math.floor(number) < number) {
                        throw new IllegalArgumentException(number + " isn't whole");
                    }
                    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(number + " is out of an int's range");
                    }
                    return (int) number.doubleValue();
                });

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(refuseIf), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double NaN was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts every double exactly but a finite whole one below Integer.MIN_VALUE, which"
            + " a cast with no lower bound saturates to it on an int setter, fails, naming the double and the setter")
    void belowIntAloneFails() {
        // An upper bound without a lower one, on a language that refuses both infinities and NaN: the cast saturates
        // -3.0E9, and every other number the check feeds is converted exactly, so that is the only failure.
        ExpressionLanguage noLowerBound = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> value instanceof Double number && target == int.class && !number.isInfinite()
                        && number == Math.rint(number) && number < Integer.MIN_VALUE
                        ? Integer.MIN_VALUE : DoubleNumbers.convert(value, target));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(noLowerBound), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double -3.0E9 was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose doubles are converted exactly, and whose BigDecimals are narrowed with intValue(),"
            + " which rounds, fails, naming the BigDecimal and the setter")
    void roundingBigDecimalsFails() {
        ExpressionLanguage roundingDecimals = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> value instanceof BigDecimal number && target == int.class
                        ? number.intValue() : DoubleNumbers.convert(value, target));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(roundingDecimals), CHECK));

        assertTrue(failure.getMessage().startsWith("the BigDecimal 1.5 was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that refuses a BigDecimal with a fraction and narrows the rest with intValue(), which"
            + " keeps the low 32 bits of one beyond an int, fails, naming the BigDecimal and the setter")
    void wholeBigDecimalsNarrowedFails() {
        ExpressionLanguage wholeDecimals = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> value instanceof BigDecimal number && target == int.class
                        ? whole(number).intValue() : DoubleNumbers.convert(value, target));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(wholeDecimals), CHECK));

        assertTrue(failure.getMessage().startsWith("the BigDecimal 3E9 was set on setCreditScore(int)"),
                failure.getMessage());
    }

    /** Returns {@code number} if it is whole, and refuses it otherwise: the check a language makes before narrowing. */
    private static BigDecimal whole(BigDecimal number) {
        if (number.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(number + " isn't whole");
        }
        return number;
    }

    /** Narrows a whole {@code number} within an int's range to an int, and refuses any other: a faithful conversion. */
    private static int exactInt(BigDecimal number) {
        if (whole(number).compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(number + " is out of an int's range");
        }
        return number.intValue();
    }

    @Test
    @DisplayName("a language whose ints are converted exactly, and whose longs are whole doubles cast, so an infinity"
            + " sets Long.MAX_VALUE, fails, naming the double and the setter")
    void infinityOnALongFails() {
        ExpressionLanguage castingLongs = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> {
                    if (number != Math.floor(number)) {
                        throw new IllegalArgumentException(number + " isn't whole");
                    }
                    return number.longValue();
                }));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(castingLongs), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double Infinity was set on setBalance(long)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts every finite whole double for a long setter, past 2^53 where a double no"
            + " longer counts by ones, fails, naming the double and the setter")
    void beyondExactLongFails() {
        ExpressionLanguage everyWholeDouble = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> {
                    if (Double.isInfinite(number) || number != Math.rint(number)) {
                        throw new IllegalArgumentException(number + " isn't a finite whole number");
                    }
                    return number.longValue();
                }));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(everyWholeDouble), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double 9.007199254740994E15, which is 2^53 + 2 and so beyond"
                + " where a double counts by ones, was set on setBalance(long)"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose ints are converted exactly, and whose longs are doubles rounded with Math.round()"
            + " once below 2^53, so a fraction sets the nearest long, fails, naming the double and the setter")
    void roundingLongsFails() {
        // Math.round() refuses nothing: it rounds 1.5 to 2, NaN to 0 and -Infinity to Long.MIN_VALUE, and 1.5 is the
        // first of them the check feeds.
        ExpressionLanguage roundingLongs = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> {
                    if (number == Double.POSITIVE_INFINITY || number > DoubleNumbers.EXACT) {
                        throw new IllegalArgumentException(number + " is beyond where a double counts by ones");
                    }
                    return Math.round(number);
                }));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(roundingLongs), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double 1.5 was set on setBalance(long)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts every double exactly but NaN, which sets 0 on a long setter, as a cast or"
            + " Math.round() does, fails, naming the double and the setter")
    void nanOnALongFails() {
        ExpressionLanguage nanIsZero = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> number.isNaN() ? 0L : DoubleNumbers.convert(number, long.class)));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(nanIsZero), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double NaN was set on setBalance(long)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts every double exactly but -Infinity, which sets Long.MIN_VALUE on a long"
            + " setter, as a cast does, fails, naming the double and the setter")
    void negativeInfinityOnALongFails() {
        ExpressionLanguage noFloor = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> number == Double.NEGATIVE_INFINITY
                        ? Long.MIN_VALUE : DoubleNumbers.convert(number, long.class)));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(noFloor), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double -Infinity was set on setBalance(long)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts every finite whole double up to 2^53 for a long setter, with no bound below"
            + " zero, so a whole double past -2^53 sets a long, fails, naming the double and the setter")
    void beyondExactNegativeLongFails() {
        ExpressionLanguage noLowerBound = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> {
                    if (number.isNaN() || number.isInfinite() || number != Math.rint(number)
                            || number > DoubleNumbers.EXACT) {
                        throw new IllegalArgumentException(number + " isn't a finite whole number up to 2^53");
                    }
                    return number.longValue();
                }));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(noLowerBound), CHECK));

        assertTrue(failure.getMessage().startsWith("the Double -9.007199254740994E15, which is -(2^53 + 2) and so"
                + " beyond where a double counts by ones, was set on setBalance(long)"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose BigDecimals are narrowed exactly for an int setter and with longValue() for a long"
            + " setter, which rounds, fails, naming the BigDecimal and the setter")
    void roundingBigDecimalLongsFails() {
        ExpressionLanguage roundingLongs = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> {
                    if (!(value instanceof BigDecimal number)) {
                        return DoubleNumbers.convert(value, target);
                    }
                    if (target == int.class) {
                        return exactInt(number);
                    }
                    return target == long.class ? number.longValue() : number;
                });

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(roundingLongs), CHECK));

        assertTrue(failure.getMessage().startsWith("the BigDecimal 1.5 was set on setBalance(long)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that refuses a BigDecimal with a fraction, narrows the rest exactly for an int setter and"
            + " with longValue() for a long setter, which keeps the low 64 bits of one beyond a long, fails, naming the"
            + " BigDecimal and the setter")
    void wholeBigDecimalLongsNarrowedFails() {
        ExpressionLanguage wholeLongs = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> {
                    if (!(value instanceof BigDecimal number)) {
                        return DoubleNumbers.convert(value, target);
                    }
                    if (target == int.class) {
                        return exactInt(number);
                    }
                    return target == long.class ? whole(number).longValue() : number;
                });

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(wholeLongs), CHECK));

        assertTrue(failure.getMessage().startsWith("the BigDecimal 1E19 was set on setBalance(long)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose ints are converted exactly, and whose longs are whole doubles narrowed with"
            + " intValue() and widened, so 3.0E9 sets Integer.MAX_VALUE without failing, fails: the whole Double 3.0E9"
            + " didn't reach the long setter")
    void wrongLongFails() {
        // The refusals after the equality would fail this language too, so the message is what proves the equality
        // caught it first.
        ExpressionLanguage intsWidened = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> {
                    if (number != Math.rint(number)) {
                        throw new IllegalArgumentException(number + " isn't whole");
                    }
                    return (long) number.intValue();
                }));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(intsWidened), CHECK));

        assertTrue(failure.getMessage().startsWith("the whole Double 3.0E9 didn't reach setBalance(long)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language whose ints are converted exactly and whose doubles never reach a long setter fails: the"
            + " whole Double 3.0E9 never reaches it")
    void unconvertedLongsFail() {
        ExpressionLanguage unconvertedLongs = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                exceptForLongs(number -> number));

        Throwable failure = assertThrows(Throwable.class, () -> runCheck(optingIn(unconvertedLongs), CHECK));

        assertTrue(failure.getMessage().contains("has no public method setBalance that accepts a java.lang.Double"),
                failure.getMessage());
    }
}
