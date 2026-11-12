package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.RuleExecutionException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
 * counts by ones, either side of zero, one that rounds a double for a {@code long} setter, one that rounds a
 * {@link BigDecimal}, or keeps a long one's low bits, to set an {@code int} or a {@code long} (#1264), and one that
 * keeps the low bits of a {@link Long} to set an {@code int} (#1384). Each of the int setter's refusals, each
 * {@code BigDecimal}, each {@code Long}, and NaN and -Infinity on the long setter, is pinned on its own: a
 * language that mishandles one infinity, NaN, or a finite whole double below an int, and nothing else fails naming
 * it, and so does one that sets a wrong {@code long} for 3.0E9 without refusing anything. And
 * {@code outputLongsNotNarrowed}, which no hook skips, must fail a language that keeps the low bits of a {@code Long}
 * without opting in, as one whose whole numbers are {@code Long}s doesn't, whether its actions return their properties
 * or change the output in place (#1405). Apart from {@link ContractKitChecksTest}, which has to compile without
 * {@link ExpressionCompiler#convertOutput}.
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

    @Test
    @DisplayName("a language whose doubles are converted exactly, and whose Longs are narrowed with intValue(), which"
            + " keeps the low 32 bits of one beyond an int, fails, naming the Long and the setter (#1384)")
    void narrowingLongsFails() {
        ExpressionLanguage narrowing = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> value instanceof Long number && target == int.class
                        ? number.intValue() : DoubleNumbers.convert(value, target));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(narrowing), CHECK));

        assertTrue(failure.getMessage().startsWith("the Long 3000000000 was set on setCreditScore(int)"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that refuses a Long above an int and narrows the rest with intValue() fails, naming the"
            + " Long below an int and the setter (#1384)")
    void narrowingLongsBelowIntFails() {
        ExpressionLanguage boundedAbove = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)),
                (value, target) -> {
                    if (!(value instanceof Long number) || target != int.class) {
                        return DoubleNumbers.convert(value, target);
                    }
                    if (number > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(number + " is out of an int's range");
                    }
                    return number.intValue();
                });

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(boundedAbove), CHECK));

        assertTrue(failure.getMessage().startsWith("the Long -3000000000 was set on setCreditScore(int)"),
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

    /** The check that converts on several threads at once (#1289). */
    private static final String CONCURRENT = "outputNumbersConvertedConcurrently";

    /**
     * A conversion that keeps the value it was given in a field every thread shares, and converts what the field holds
     * when it reads it back, as {@link DoubleNumbers#convert} does (#1289). The race is forced, so that the check fails
     * it every time, on any number of cores: the first two calls made on threads other than the one that made the
     * conversion each write the field, then wait at a barrier for the other, so the first to write reads back the
     * second's value. Calls on the thread that made it, as the single-threaded checks make them, and every call after
     * those two, don't wait. A call that waits {@code RENDEZVOUS} in vain carries on, so the conversion never blocks
     * for good.
     */
    private static OutputConversion racy() {
        Thread maker = Thread.currentThread();
        Object[] last = new Object[1];
        AtomicInteger waiting = new AtomicInteger();
        CyclicBarrier bothWritten = new CyclicBarrier(2);
        return (value, target) -> {
            last[0] = value;
            if (Thread.currentThread() != maker && waiting.getAndIncrement() < 2) {
                try {
                    bothWritten.await(RENDEZVOUS.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (BrokenBarrierException | TimeoutException e) {
                    // The other call never came: carry on with whatever the field holds.
                }
            }
            return DoubleNumbers.convert(last[0], target);
        };
    }

    /** How long {@link #racy()}'s conversion waits for another thread's call before it carries on. */
    private static final Duration RENDEZVOUS = Duration.ofSeconds(10);

    @Test
    @DisplayName("a language whose doubles are converted exactly, on any thread, passes the concurrent conversion check"
            + " (#1289)")
    void faithfulDoublesPassConcurrently() throws Throwable {
        runCheck(optingIn(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true))), CONCURRENT);
    }

    @Test
    @DisplayName("a language that doesn't opt in skips the concurrent conversion check (#1289)")
    void concurrentSkippedWithoutOptingIn() {
        assertThrows(TestAbortedException.class, () -> runCheck(new ToyExpressionLanguage("toy-properties", true),
                CONCURRENT));
    }

    @Test
    @DisplayName("a language whose convertOutput keeps the value in a field every thread shares fails the concurrent"
            + " conversion check, with the race forced so it fails every time, and passes the single-threaded"
            + " conversion check (#1289)")
    void racyConversionFails() {
        ExpressionLanguage racy = converting(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true)), racy());

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(optingIn(racy), CONCURRENT));

        assertTrue(failure.getMessage().contains("convertOutput must be thread-safe"), failure.getMessage());
        assertDoesNotThrow(() -> runCheck(optingIn(racy), CHECK));
    }

    @Test
    @DisplayName("a language that opts in and whose doubles never reach an int setter fails the concurrent conversion"
            + " check with what the worker's run threw (#1289)")
    void unconvertedDoublesFailConcurrently() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> runCheck(optingIn(new ToyExpressionLanguage("toy-properties", true)), CONCURRENT));

        assertInstanceOf(RuleExecutionException.class, failure.getCause(), failure::toString);
    }

    /** The check of a {@link Long} beyond an int, which no hook skips. */
    private static final String LONGS = "outputLongsNotNarrowed";

    /** A conversion that narrows a {@link Long} for an {@code int} setter with {@code intValue()}, and nothing else. */
    private static final OutputConversion NARROWING_LONGS = (value, target) ->
            value instanceof Long number && target == int.class ? number.intValue() : value;

    /**
     * A conversion that refuses a {@link Long} above an int for an {@code int} setter and narrows the rest with
     * {@code intValue()}, and converts nothing else.
     */
    private static final OutputConversion LONGS_BOUNDED_ABOVE = (value, target) -> {
        if (!(value instanceof Long number) || target != int.class) {
            return value;
        }
        if (number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(number + " is out of an int's range");
        }
        return number.intValue();
    };

    /**
     * A conversion that converts a {@link Long} an int holds for an {@code int} setter and refuses any other, and
     * converts nothing else: what a language whose whole numbers are {@code Long}s must do.
     */
    private static final OutputConversion EXACT_LONGS = (value, target) ->
            value instanceof Long number && target == int.class ? Math.toIntExact(number) : value;

    /**
     * A conversion that narrows a {@link Long} for an {@code int} setter with {@code intValue()} and refuses the
     * result when its sign isn't the {@code Long}'s, and converts nothing else: an overflow test that catches a
     * {@code Long} whose low 32 bits have the other sign, as 3000000000's do, and not one whose low 32 bits keep it,
     * as 5000000000's do.
     */
    private static final OutputConversion LONGS_SIGN_CHECKED = (value, target) -> {
        if (!(value instanceof Long number) || target != int.class) {
            return value;
        }
        int narrowed = number.intValue();
        if (narrowed < 0 != number < 0) {
            throw new IllegalArgumentException(number + " is out of an int's range");
        }
        return narrowed;
    };

    /**
     * A conversion that does as {@link #LONGS_SIGN_CHECKED} does, and refuses a {@link Long} above an int as well, so
     * that only one below an int whose low 32 bits are negative too is narrowed.
     */
    private static final OutputConversion LONGS_SIGN_CHECKED_BOUNDED_ABOVE = (value, target) -> {
        if (value instanceof Long number && target == int.class && number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(number + " is out of an int's range");
        }
        return LONGS_SIGN_CHECKED.convert(value, target);
    };

    /**
     * A contract test for {@code language}, whose whole numbers are Java's and whose actions return their properties,
     * which doesn't opt into {@code outputNumbersConverted}.
     */
    private static ExpressionLanguageContractTest returningProperties(ExpressionLanguage language) {
        return new ToyPropertiesContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }
        };
    }

    /** A contract test for {@code language}, whose actions change the output in place, which doesn't opt in either. */
    private static ExpressionLanguageContractTest inPlace(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }
        };
    }

    /** Runs every one of the kit's checks on the contract test, and returns the name of each that failed. */
    private static List<String> failedChecks(ExpressionLanguageContractTest test) {
        List<String> failed = new ArrayList<>();
        for (Method check : ExpressionLanguageContractTest.class.getDeclaredMethods()) {
            if (!check.isAnnotationPresent(Test.class)) {
                continue;
            }
            try {
                runCheck(test, check.getName());
            } catch (TestAbortedException e) {
                // Skipped by one of the toy's hooks, as it is in the toy's own contract test.
            } catch (Throwable e) {
                failed.add(check.getName());
            }
        }
        return failed;
    }

    @Test
    @DisplayName("a language whose whole numbers are Longs, which doesn't opt in, and whose convertOutput narrows a"
            + " Long with intValue(), fails only the Long check (#1405)")
    void narrowingLongsWithoutOptingInFails() {
        ExpressionLanguage narrowing = converting(new ToyExpressionLanguage("toy-properties", true), NARROWING_LONGS);

        assertEquals(List.of(LONGS), failedChecks(returningProperties(narrowing)));
    }

    @Test
    @DisplayName("the failure of a language that narrows a Long without opting in names the Long, the setter and the"
            + " low 32 bits it set (#1405)")
    void narrowingLongsWithoutOptingInReported() {
        ExpressionLanguage narrowing = converting(new ToyExpressionLanguage("toy-properties", true), NARROWING_LONGS);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(returningProperties(narrowing), LONGS));

        assertEquals("the Long 3000000000 was set on setCreditScore(int), as -1294967296, or the run failed for"
                + " another reason: convertOutput must refuse a Long the target doesn't hold, or return it unchanged,"
                + " not narrow it", failure.getMessage());
    }

    @Test
    @DisplayName("a language that refuses a Long above an int and narrows the rest with intValue(), without opting in,"
            + " fails only the Long check, naming the Long below an int (#1405)")
    void narrowingLongsBelowIntWithoutOptingInFails() {
        ExpressionLanguage boundedAbove = converting(new ToyExpressionLanguage("toy-properties", true),
                LONGS_BOUNDED_ABOVE);

        assertEquals(List.of(LONGS), failedChecks(returningProperties(boundedAbove)));
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(returningProperties(boundedAbove), LONGS));
        assertTrue(failure.getMessage().startsWith("the Long -3000000000 was set on setCreditScore(int), as"
                + " 1294967296,"), failure.getMessage());
    }

    @Test
    @DisplayName("a language whose actions change the output in place, and whose convertOutput narrows a Long with"
            + " intValue(), fails only the Long check (#1405)")
    void narrowingLongsInPlaceFails() {
        ExpressionLanguage narrowing = converting(new ToyExpressionLanguage(), NARROWING_LONGS);

        assertEquals(List.of(LONGS), failedChecks(inPlace(narrowing)));
    }

    @Test
    @DisplayName("the failure of a language whose actions change the output in place says what its convertOutput"
            + " returned for the Long (#1405)")
    void narrowingLongsInPlaceReported() {
        AssertionFailedError above = assertThrows(AssertionFailedError.class,
                () -> runCheck(inPlace(converting(new ToyExpressionLanguage(), NARROWING_LONGS)), LONGS));
        AssertionFailedError below = assertThrows(AssertionFailedError.class,
                () -> runCheck(inPlace(converting(new ToyExpressionLanguage(), LONGS_BOUNDED_ABOVE)), LONGS));

        assertEquals("the language's actions change the output in place, so the writer never asks its convertOutput,"
                + " but asked to convert the Long 3000000000 for setCreditScore(int) it returned the Integer"
                + " -1294967296, which the setter would take: convertOutput must refuse a Long the target doesn't"
                + " hold, or return it unchanged, not narrow it", above.getMessage());
        assertTrue(below.getMessage().contains(" asked to convert the Long -3000000000 for setCreditScore(int) it"
                + " returned the Integer 1294967296,"), below.getMessage());
    }

    @Test
    @DisplayName("a language that returns the Long unchanged, as the default convertOutput does, or refuses it, or"
            + " converts only a Long an int holds, passes the Long check, whether its actions return properties or"
            + " change the output in place, as does one whose numbers are doubles (#1405)")
    void unchangedOrRefusedLongsPass() throws Throwable {
        runCheck(returningProperties(new ToyExpressionLanguage("toy-properties", true)), LONGS);
        runCheck(inPlace(new ToyExpressionLanguage()), LONGS);
        runCheck(optingIn(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true))), LONGS);
        OutputConversion refusing = (value, target) -> {
            throw new IllegalArgumentException(value + " isn't a " + target.getName());
        };
        runCheck(returningProperties(converting(new ToyExpressionLanguage("toy-properties", true), refusing)), LONGS);
        runCheck(inPlace(converting(new ToyExpressionLanguage(), refusing)), LONGS);
        assertEquals(List.of(), failedChecks(returningProperties(
                converting(new ToyExpressionLanguage("toy-properties", true), EXACT_LONGS))));
        assertEquals(List.of(), failedChecks(inPlace(converting(new ToyExpressionLanguage(), EXACT_LONGS))));
    }

    @Test
    @DisplayName("a language that narrows a Long with intValue() and refuses only a result whose sign changed, which"
            + " 5000000000's low 32 bits don't, fails only the Long check, naming that Long, whether its actions"
            + " return properties or change the output in place (#1405)")
    void signCheckedLongsFail() {
        assertEquals(List.of(LONGS), failedChecks(returningProperties(
                converting(new ToyExpressionLanguage("toy-properties", true), LONGS_SIGN_CHECKED))));
        assertEquals(List.of(LONGS), failedChecks(inPlace(converting(new ToyExpressionLanguage(),
                LONGS_SIGN_CHECKED))));

        AssertionFailedError returned = assertThrows(AssertionFailedError.class, () -> runCheck(returningProperties(
                converting(new ToyExpressionLanguage("toy-properties", true), LONGS_SIGN_CHECKED)), LONGS));
        AssertionFailedError inPlace = assertThrows(AssertionFailedError.class,
                () -> runCheck(inPlace(converting(new ToyExpressionLanguage(), LONGS_SIGN_CHECKED)), LONGS));
        assertTrue(returned.getMessage().startsWith("the Long 5000000000 was set on setCreditScore(int), as"
                + " 705032704,"), returned.getMessage());
        assertTrue(inPlace.getMessage().contains(" asked to convert the Long 5000000000 for setCreditScore(int) it"
                + " returned the Integer 705032704,"), inPlace.getMessage());
    }

    @Test
    @DisplayName("a language that refuses a Long above an int, and below it only a result whose sign changed, which"
            + " -5000000000's low 32 bits don't, fails only the Long check, naming that Long (#1405)")
    void signCheckedLongsBelowIntFail() {
        ExpressionLanguage boundedAbove = converting(new ToyExpressionLanguage("toy-properties", true),
                LONGS_SIGN_CHECKED_BOUNDED_ABOVE);

        assertEquals(List.of(LONGS), failedChecks(returningProperties(boundedAbove)));
        AssertionFailedError returned = assertThrows(AssertionFailedError.class,
                () -> runCheck(returningProperties(boundedAbove), LONGS));
        AssertionFailedError inPlace = assertThrows(AssertionFailedError.class, () -> runCheck(
                inPlace(converting(new ToyExpressionLanguage(), LONGS_SIGN_CHECKED_BOUNDED_ABOVE)), LONGS));
        assertTrue(returned.getMessage().startsWith("the Long -5000000000 was set on setCreditScore(int), as"
                + " -705032704,"), returned.getMessage());
        assertTrue(inPlace.getMessage().contains(" asked to convert the Long -5000000000 for setCreditScore(int) it"
                + " returned the Integer -705032704,"), inPlace.getMessage());
    }

    @Test
    @DisplayName("a language whose actions change the output in place, and whose convertOutput narrows a Long to a"
            + " Short, a Byte or a Character, each of which the writer widens for an int setter, fails only the Long"
            + " check, naming what it returned (#1405)")
    void narrowingLongsToWhatWidensInPlaceFails() {
        Map<String, OutputConversion> narrowings = new LinkedHashMap<>();
        narrowings.put("the Short 24064,", (value, target) ->
                value instanceof Long number && target == int.class ? (Object) number.shortValue() : value);
        narrowings.put("the Byte 0,", (value, target) ->
                value instanceof Long number && target == int.class ? (Object) number.byteValue() : value);
        narrowings.put("the Character \u5e00,", (value, target) ->
                value instanceof Long number && target == int.class ? (Object) (char) number.longValue() : value);

        narrowings.forEach((returned, narrowing) -> {
            assertEquals(List.of(LONGS), failedChecks(inPlace(converting(new ToyExpressionLanguage(), narrowing))),
                    returned);
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> runCheck(inPlace(converting(new ToyExpressionLanguage(), narrowing)), LONGS));
            assertTrue(failure.getMessage().contains(" asked to convert the Long 3000000000 for setCreditScore(int)"
                    + " it returned " + returned), failure.getMessage());
        });
    }
}
