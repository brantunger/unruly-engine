package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.runCheck;
import static io.github.brantunger.unruly.api.language.DoubleNumbers.doubleNumbers;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The contract test kit's {@code outputNumbersConverted}, which a language opts into with
 * {@code convertsOutputNumbers()}, must fail a language whose doubles never reach an {@code int} setter, and one that
 * rounds a fraction to set one (#1127). Apart from {@link ContractKitChecksTest}, which has to compile without
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
}
