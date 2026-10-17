package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.Set;

/**
 * A language that forwards every method to another, for a check that wraps the language under test to watch one thing
 * it does and leaves the rest as it is: {@code prepare()}, so the language initializes its classes when the engine is
 * built, as it does unwrapped; {@code reservedFactNames()} and {@code reservesForEveryRuleList()}, so the engine
 * rejects the fact names the language reserves, and only those, for the same rule lists. A wrapper overrides
 * {@link #newCompiler} to wrap the language's compiler in a {@link ForwardingCompiler}.
 *
 * <p>
 * {@code WrapperForwardingTest} fails if a method of {@link ExpressionLanguage} isn't forwarded, so a {@code default}
 * method added to it later isn't silently dropped (#736, #1127). Not a lambda anywhere: the wrappers sit on a
 * language's first-run path, and the kit measures the language, not itself.
 * </p>
 */
class ForwardingLanguage implements ExpressionLanguage {

    private final ExpressionLanguage language;

    /**
     * Creates the language.
     *
     * @param language The language every method is forwarded to
     */
    ForwardingLanguage(ExpressionLanguage language) {
        this.language = language;
    }

    @Override
    public String name() {
        return language.name();
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        return language.newCompiler(context);
    }

    @Override
    public void prepare() {
        language.prepare();
    }

    @Override
    public Set<String> reservedFactNames() {
        return language.reservedFactNames();
    }

    @Override
    public boolean reservesForEveryRuleList() {
        return language.reservesForEveryRuleList();
    }
}
