package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
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
    // The wrapper holds the language's compiler, and closes it when the engine closes the wrapper.
    @SuppressWarnings("PMD.CloseResource")
    static ExpressionLanguage countingCloses(ExpressionLanguage language, List<AtomicInteger> closes,
                                             List<Throwable> closeFailures) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            // Forwarded, so the language initializes its classes when the engine is built, as it does unwrapped.
            @Override
            public void prepare() {
                language.prepare();
            }

            // Forwarded, so the engine rejects the fact names the language reserves, and only those.
            @Override
            public Set<String> reservedFactNames() {
                return language.reservedFactNames();
            }

            // Forwarded, so the engine rejects those names for the same rule lists as it does unwrapped.
            @Override
            public boolean reservesForEveryRuleList() {
                return language.reservesForEveryRuleList();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                AtomicInteger closed = new AtomicInteger();
                closes.add(closed);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }

                    // Forwarded, so that a copy made when the rules load, as a configure() that sets copiesAtLoad
                    // makes one, is warmed up as it would be without the wrapper: a compiler whose close() fails only
                    // once a session was warmed up still fails the check.
                    @Override
                    public void warmUp(Session session) throws Exception {
                        compiler.warmUp(session);
                    }

                    @Override
                    public void checkFactName(String name) {
                        compiler.checkFactName(name);
                    }

                    // Forwarded, so the engine asks the language about the same facts as it does unwrapped.
                    @Override
                    public @Nullable Set<String> factNamesRead() {
                        return compiler.factNamesRead();
                    }

                    // Anything it throws, a checked exception thrown sneakily included: the engine logs any Exception
                    // or Error at WARN, and rethrows the fatal error one is or carries, which fails the check by
                    // itself.
                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        try {
                            compiler.close();
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
