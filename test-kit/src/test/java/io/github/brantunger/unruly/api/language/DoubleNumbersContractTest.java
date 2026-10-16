package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;

import static io.github.brantunger.unruly.api.language.DoubleNumbers.doubleNumbers;

/**
 * The contract test for a language whose numbers are all {@code Double}s, as JavaScript's and JsonLogic's are: its
 * actions return the facts they put as {@code Double}s, and its compiler converts a whole double for a setter taking an
 * {@code int} (#1127). A faithful adapter for such a language passes, the check it opts into with
 * {@code convertsOutputNumbers()} included.
 */
@DisplayName("a language whose numbers are Doubles keeps the expression-language contract")
class DoubleNumbersContractTest extends ToyPropertiesContractTest {

    @Override
    protected ExpressionLanguage language() {
        return doubleNumbers(new ToyExpressionLanguage("toy-doubles", true));
    }

    @Override
    protected boolean convertsOutputNumbers() {
        return true;
    }
}
