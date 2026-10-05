package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records whether a language's own {@code checkFactName} rejected a name, for the contract kit's
 * {@code unusableFactNameRejected} and {@code unreservedOutputReadAsFact}: {@code run()} rejects a fact with an
 * {@link IllegalArgumentException} for other reasons too, such as a value that isn't of the type the fact was declared
 * with.
 */
final class FactNameWatch {

    private FactNameWatch() {
    }

    /**
     * Wraps a language so that each compiler it creates sets {@code rejected} when its {@code checkFactName(name)}
     * throws an {@link IllegalArgumentException} for {@code name}, which is how a language rejects a fact name. Every
     * other call is forwarded unchanged.
     */
    // The wrapper holds the language's compiler, and closes it when the engine closes the wrapper.
    @SuppressWarnings("PMD.CloseResource")
    static ExpressionLanguage watchingRejection(ExpressionLanguage language, String name, AtomicBoolean rejected) {
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

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
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

                    @Override
                    public void warmUp(Session session) throws Exception {
                        compiler.warmUp(session);
                    }

                    @Override
                    public void checkFactName(String checked) {
                        try {
                            compiler.checkFactName(checked);
                        } catch (IllegalArgumentException e) {
                            if (name.equals(checked)) {
                                rejected.set(true);
                            }
                            throw e;
                        }
                    }

                    @Override
                    public void close() {
                        compiler.close();
                    }
                };
            }
        };
    }
}
