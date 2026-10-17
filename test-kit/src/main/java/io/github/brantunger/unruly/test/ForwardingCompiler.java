package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A compiler that forwards every method to another, for a check that wraps the language under test to watch one thing
 * its compilers do and leaves the rest as it is. A wrapper's {@link ForwardingLanguage} overrides {@code newCompiler}
 * to wrap the language's compiler in one, which closes the language's compiler when the engine closes it.
 *
 * <p>
 * It forwards the {@code default} methods too, so a wrapper that overrides only the method it watches doesn't silently
 * replace the language's {@code warmUp}, {@code checkFactName}, {@code factNamesRead}, {@code convertOutput} or
 * {@code close} with the do-nothing default: {@code warmUp}, so a copy made when the rules load is warmed up as it
 * would be without the wrapper; {@code factNamesRead}, so the engine asks the language about the same facts as it does
 * unwrapped; {@code convertOutput}, so the writer converts a wrapped language's values as it does unwrapped (#1127).
 * {@code WrapperForwardingTest} fails if a method of {@link ExpressionCompiler} isn't forwarded, so a {@code default}
 * method added to it later isn't silently dropped (#736). Not a lambda anywhere: the wrappers sit on a language's
 * first-run path, and the kit measures the language, not itself.
 * </p>
 */
class ForwardingCompiler implements ExpressionCompiler {

    private final ExpressionCompiler compiler;

    /**
     * Creates the compiler.
     *
     * @param compiler The compiler every method is forwarded to
     */
    ForwardingCompiler(ExpressionCompiler compiler) {
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
    public @Nullable Set<String> factNamesRead() {
        return compiler.factNamesRead();
    }

    @Override
    public @Nullable Object convertOutput(Object value, Class<?> target) {
        return compiler.convertOutput(value, target);
    }

    @Override
    public void close() {
        compiler.close();
    }
}
