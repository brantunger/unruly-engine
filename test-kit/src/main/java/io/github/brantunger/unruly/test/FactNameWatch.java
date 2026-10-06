package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records whether a language's own {@code checkFactName} rejected a name, for the contract kit's
 * {@code unusableFactNameRejected} and {@code unreservedOutputReadAsFact}: {@code run()} rejects a fact with an
 * {@link IllegalArgumentException} for other reasons too, such as a value that isn't of the type the fact was declared
 * with. And records what a language's compilers say their rules read, for {@code factNamesReadHoldsTheFactsRead}.
 */
final class FactNameWatch {

    private FactNameWatch() {
    }

    /**
     * Wraps a language so that each compiler it creates sets {@code rejected} when its {@code checkFactName(name)}
     * throws an {@link IllegalArgumentException} for {@code name}, which is how a language rejects a fact name. A
     * compiler whose {@code factNamesRead()} returns a set is said to read {@code name} too, so the engine asks it
     * about the name although the checks' rules don't read it: no rule could, as the language can't refer to it, or
     * needn't compile one that names it. Every other call is forwarded unchanged.
     */
    static ExpressionLanguage watchingRejection(ExpressionLanguage language, String name, AtomicBoolean rejected) {
        return watching(language, name, rejected, null);
    }

    /**
     * Wraps a language so that each compiler it creates adds to {@code answers} what its {@code factNamesRead()}
     * returns, {@code null} included, each time the engine asks. Every call is forwarded unchanged.
     */
    static ExpressionLanguage recordingNamesRead(ExpressionLanguage language, List<@Nullable Set<String>> answers) {
        return watching(language, null, new AtomicBoolean(), answers);
    }

    /**
     * Wraps a language as {@link #watchingRejection} does when {@code name} isn't {@code null}, and as
     * {@link #recordingNamesRead} does when {@code answers} isn't.
     */
    // The wrapper holds the language's compiler, and closes it when the engine closes the wrapper.
    @SuppressWarnings("PMD.CloseResource")
    private static ExpressionLanguage watching(ExpressionLanguage language, @Nullable String name,
                                               AtomicBoolean rejected, @Nullable List<@Nullable Set<String>> answers) {
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
                            if (checked.equals(name)) {
                                rejected.set(true);
                            }
                            throw e;
                        }
                    }

                    @Override
                    public @Nullable Set<String> factNamesRead() {
                        Set<String> read = compiler.factNamesRead();
                        if (answers != null) {
                            answers.add(read);
                        }
                        if (read == null || name == null) {
                            return read;
                        }
                        // A set that holds null is passed on as it is, for the engine to reject.
                        Set<String> watched = new HashSet<>(read);
                        watched.add(name);
                        return watched;
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
