package io.github.brantunger.unruly.api.language;

/**
 * A compiler that forwards every method to another, for a test that needs a compiler that works like a real one except
 * for what it overrides.
 *
 * <p>
 * It forwards the {@code default} methods too, so a test that overrides only the method it breaks doesn't silently
 * replace the other compiler's {@code warmUp}, {@code checkFactName} or {@code close} with the do-nothing default
 * (#736). {@code ForwardingTest} fails if a method of {@link ExpressionCompiler} isn't forwarded.
 * </p>
 */
public class ForwardingExpressionCompiler implements ExpressionCompiler {

    private final ExpressionCompiler compiler;

    /**
     * Creates the compiler.
     *
     * @param compiler The compiler every method is forwarded to
     */
    public ForwardingExpressionCompiler(ExpressionCompiler compiler) {
        this.compiler = compiler;
    }

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
    public void checkFactName(String name) {
        compiler.checkFactName(name);
    }

    @Override
    public void close() {
        compiler.close();
    }
}
