package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts how often the engine closes each compiler a language creates, for the contract kit's {@code compilerClosed}.
 */
final class CompilerCloseCounter {

    private CompilerCloseCounter() {
    }

    /**
     * Wraps a language so that each compiler it creates counts how often it's closed, and records in
     * {@code closeFailures} what its close() throws that the engine only logs.
     */
    static ExpressionLanguage countingCloses(ExpressionLanguage language, List<AtomicInteger> closes,
                                             List<Throwable> closeFailures) {
        return new ForwardingLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = super.newCompiler(context);
                AtomicInteger closed = new AtomicInteger();
                closes.add(closed);
                // warmUp() is forwarded, so that a copy made when the rules load, as a configure() that sets
                // copiesAtLoad makes one, is warmed up as it would be without the wrapper: a compiler whose close()
                // fails only once a session was warmed up still fails the check.
                return new ForwardingCompiler(compiler) {
                    // Anything it throws, a checked exception thrown sneakily included: the engine logs any Exception
                    // or Error at WARN, and rethrows the fatal error one is or carries, which fails the check by
                    // itself.
                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        try {
                            super.close();
                        } catch (Throwable e) {
                            if (!KitFailures.isFatal(e)) {
                                closeFailures.add(e);
                            }
                            throw e;
                        }
                    }
                };
            }
        };
    }
}
