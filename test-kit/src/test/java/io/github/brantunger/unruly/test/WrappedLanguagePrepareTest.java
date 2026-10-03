package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #945: the kit's checks build engines with the language wrapped, and the engine prepares the wrapper, so each wrapper
 * forwards {@link ExpressionLanguage#prepare()} to the language, which initializes its classes there as it does when
 * an application's engine is built.
 */
@DisplayName("the kit's wrappers of a language forward prepare() to it (#945)")
class WrappedLanguagePrepareTest {

    /** A language that counts its prepare() calls, and compiles nothing. */
    private static final class Preparing implements ExpressionLanguage {
        private final AtomicInteger prepared = new AtomicInteger();

        @Override
        public String name() {
            return "preparing";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            throw new UnsupportedOperationException("never compiles");
        }

        @Override
        public void prepare() {
            prepared.incrementAndGet();
        }
    }

    @Test
    @DisplayName("the wrapper that counts how often compilers are closed forwards prepare()")
    void compilerCloseCounterForwards() {
        Preparing language = new Preparing();

        CompilerCloseCounter.countingCloses(language, new ArrayList<>(), new ArrayList<>()).prepare();

        assertEquals(1, language.prepared.get(), "prepared");
    }

    @Test
    @DisplayName("the wrapper that watches the sessions a language returns forwards prepare()")
    void sessionWatchForwards() {
        Preparing language = new Preparing();

        new SessionWatch().watching(language).prepare();

        assertEquals(1, language.prepared.get(), "prepared");
    }
}
