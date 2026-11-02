package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.runCheck;
import static io.github.brantunger.unruly.api.language.DoubleNumbers.doubleNumbers;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The contract test kit's {@code outputUnconvertibleValueFailsTheRule}, which no hook skips, must fail a language
 * whose {@link ExpressionCompiler#convertOutput} returns {@code null} for a value it has no conversion for, which sets
 * a property taking a reference to {@code null} instead of failing the rule (#1307), whether its actions return their
 * properties or change the output in place. Apart from {@link ContractKitChecksTest}, which has to compile without
 * {@link ExpressionCompiler#convertOutput}.
 */
@DisplayName("the contract test kit fails a language that converts a value it has no conversion for to null")
class UnconvertibleOutputCheckTest {

    /** The check's name. */
    private static final String CHECK = "outputUnconvertibleValueFailsTheRule";

    /** A contract test for {@code language}, whose actions return their properties. */
    private static ExpressionLanguageContractTest returningProperties(ExpressionLanguage language) {
        return new ToyPropertiesContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }
        };
    }

    /** A contract test for {@code language}, whose actions change the output in place. */
    private static ExpressionLanguageContractTest inPlace(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }
        };
    }

    /** Wraps a language so that its compiler converts every value as {@code conversion} does. */
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
    @DisplayName("a language whose actions return properties, and whose convertOutput returns null for a conversion it"
            + " doesn't have, fails only this check")
    void nullForUnknownConversionFails() {
        ExpressionLanguage nulling = converting(new ToyExpressionLanguage("toy-properties", true),
                (value, target) -> null);

        assertEquals(List.of(CHECK), failedChecks(returningProperties(nulling)));
    }

    @Test
    @DisplayName("a language whose actions change the output in place, and whose convertOutput returns null for a"
            + " conversion it doesn't have, fails only this check")
    void nullForUnknownConversionInPlaceFails() {
        ExpressionLanguage nulling = converting(new ToyExpressionLanguage(), (value, target) -> null);

        assertEquals(List.of(CHECK), failedChecks(inPlace(nulling)));
    }

    @Test
    @DisplayName("the failure of a language whose actions return properties says the Double was set as null")
    void nullForUnknownConversionReported() {
        ExpressionLanguage nulling = converting(new ToyExpressionLanguage("toy-properties", true),
                (value, target) -> null);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(returningProperties(nulling), CHECK));

        assertEquals("the Double 1.5 was set on setName(String), as null, or the run failed for another reason:"
                + " convertOutput must return a value it has no conversion for unchanged, not null or a String",
                failure.getMessage());
    }

    @Test
    @DisplayName("the failure of a language whose actions change the output in place says what its convertOutput"
            + " returned")
    void nullForUnknownConversionInPlaceReported() {
        ExpressionLanguage nulling = converting(new ToyExpressionLanguage(), (value, target) -> null);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> runCheck(inPlace(nulling), CHECK));

        assertEquals("the language's actions change the output in place, so the writer never asks its convertOutput,"
                + " but asked to convert the Double 1.5 for setName(String) it returned null, which the setter would"
                + " take: convertOutput must return a value it has no conversion for unchanged, not null or a String",
                failure.getMessage());
    }

    @Test
    @DisplayName("a language that converts the Double to a String fails, whether its actions return properties or"
            + " change the output in place")
    void stringConversionFails() {
        OutputConversion stringing = (value, target) -> target == String.class ? String.valueOf(value) : value;

        AssertionFailedError returned = assertThrows(AssertionFailedError.class, () -> runCheck(
                returningProperties(converting(new ToyExpressionLanguage("toy-properties", true), stringing)), CHECK));
        assertTrue(returned.getMessage().startsWith("the Double 1.5 was set on setName(String), as 1.5,"),
                returned.getMessage());
        AssertionFailedError inPlace = assertThrows(AssertionFailedError.class,
                () -> runCheck(inPlace(converting(new ToyExpressionLanguage(), stringing)), CHECK));
        assertTrue(inPlace.getMessage().contains(" it returned 1.5, which the setter would take"),
                inPlace.getMessage());
    }

    @Test
    @DisplayName("a language that returns the value unchanged, as the default convertOutput does, or refuses it,"
            + " passes, whether its actions return properties or change the output in place, as does one whose"
            + " numbers are doubles")
    void unchangedOrRefusedPasses() throws Throwable {
        runCheck(returningProperties(new ToyExpressionLanguage("toy-properties", true)), CHECK);
        runCheck(inPlace(new ToyExpressionLanguage()), CHECK);
        runCheck(returningProperties(doubleNumbers(new ToyExpressionLanguage("toy-doubles", true))), CHECK);
        OutputConversion refusing = (value, target) -> {
            throw new IllegalArgumentException(value + " isn't a " + target.getName());
        };
        runCheck(returningProperties(converting(new ToyExpressionLanguage("toy-properties", true), refusing)), CHECK);
        runCheck(inPlace(converting(new ToyExpressionLanguage(), refusing)), CHECK);
    }
}
