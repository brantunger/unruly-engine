package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ForwardingExpressionCompiler;
import io.github.brantunger.unruly.api.language.ForwardingExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1046: the wrappers the kit builds engines with forward {@code reservesForEveryRuleList()} and
 * {@code factNamesRead()}, which they implement by hand, so a language that opts in isn't checked as one that doesn't.
 */
@DisplayName("the kit's wrappers forward reservesForEveryRuleList() and factNamesRead() (#1046)")
class WrappedLanguageScopingTest {

    /** A toy that reserves its names only for the rule lists that use it, and whose compiler reads {@code x}. */
    private static ExpressionLanguage scoped() {
        return new ForwardingExpressionLanguage(new ToyExpressionLanguage()) {
            @Override
            public boolean reservesForEveryRuleList() {
                return false;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ForwardingExpressionCompiler(super.newCompiler(context)) {
                    @Override
                    public Set<String> factNamesRead() {
                        return Set.of("x");
                    }
                };
            }
        };
    }

    private static void assertForwards(UnaryOperator<ExpressionLanguage> wrapping) {
        ExpressionLanguage wrapped = wrapping.apply(scoped());
        assertFalse(wrapped.reservesForEveryRuleList());
        assertEquals(Set.of("x"), wrapped.newCompiler(LanguageTestContexts.compile()).factNamesRead());

        ExpressionLanguage defaults = wrapping.apply(new ToyExpressionLanguage());
        assertTrue(defaults.reservesForEveryRuleList());
        assertNull(defaults.newCompiler(LanguageTestContexts.compile()).factNamesRead());
    }

    @Test
    @DisplayName("SessionWatch forwards them")
    void sessionWatch() {
        assertForwards(language -> new SessionWatch().watching(language));
    }

    @Test
    @DisplayName("CompilerCloseCounter forwards them")
    void compilerCloseCounter() {
        assertForwards(language -> CompilerCloseCounter.countingCloses(language, new ArrayList<>(),
                new ArrayList<>()));
    }
}
