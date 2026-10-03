package io.github.brantunger.unruly.api.language;

/**
 * A language that forwards every method to another, for a test that needs a language that works like a real one
 * except for what it overrides. A test that changes the compiler usually overrides {@link #newCompiler} to wrap the
 * other language's compiler in a {@link ForwardingExpressionCompiler}.
 *
 * <p>
 * {@code ForwardingTest} fails if a method of {@link ExpressionLanguage} isn't forwarded, so a {@code default} method
 * added to it later isn't silently dropped (#736).
 * </p>
 */
public class ForwardingExpressionLanguage implements ExpressionLanguage {

    private final ExpressionLanguage language;

    /**
     * Creates the language.
     *
     * @param language The language every method is forwarded to
     */
    public ForwardingExpressionLanguage(ExpressionLanguage language) {
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
}
