package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.core.EngineActionContext;
import io.github.brantunger.unruly.core.EngineCompileContext;
import io.github.brantunger.unruly.core.EngineEvaluationContext;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Creates the contexts the engine passes to an expression language, for unit tests of a language's compiler and
 * compiled expressions. The context interfaces are sealed, so a test can't implement them. These are the engine's own
 * implementations, and behave as they do in a run: for example, writing to the facts fails with the engine's message.
 *
 * <p>
 * To check a language against everything the engine promises for its rules, extend
 * {@link ExpressionLanguageContractTest}.
 * </p>
 */
public final class LanguageTestContexts {

    private LanguageTestContexts() {
    }

    /**
     * Creates a compile context without imports. Its class loader is the current thread's context class loader, or
     * this class's class loader if the thread has none, as the engine chooses one.
     *
     * @return The context
     */
    // The engine also falls back to its own class loader when the thread has no context class loader.
    @SuppressWarnings("PMD.UseProperClassLoader")
    public static CompileContext compile() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return compile(Set.of(), Set.of(),
                contextLoader != null ? contextLoader : LanguageTestContexts.class.getClassLoader());
    }

    /**
     * Creates a compile context.
     *
     * @param packageImports The imported packages, such as {@code java.util}; copied
     * @param classImports   The classes imported one by one; copied
     * @param classLoader    The class loader to look up classes in the imported packages with
     * @return The context
     * @throws NullPointerException if an argument, or an element of a set, is {@code null}
     */
    public static CompileContext compile(Set<String> packageImports, Set<Class<?>> classImports,
                                         ClassLoader classLoader) {
        return new EngineCompileContext(packageImports, classImports, classLoader);
    }

    /**
     * Creates the context a condition is evaluated against.
     *
     * @param facts The facts by name, whose values can be {@code null}; copied
     * @return The context. Its facts are read-only, and a write fails with the message the engine uses for a condition.
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    public static EvaluationContext evaluation(Map<String, ? extends @Nullable Object> facts) {
        return new EngineEvaluationContext(new LinkedHashMap<String, Object>(facts));
    }

    /**
     * Creates the context an action runs against.
     *
     * @param facts  The facts by name, whose values can be {@code null}; copied
     * @param output The output object, which the action changes in place
     * @return The context. Its facts are read-only, and a write fails with the message the engine uses for an action.
     * @throws NullPointerException if {@code facts} or {@code output} is {@code null}
     */
    public static ActionContext action(Map<String, ? extends @Nullable Object> facts, Object output) {
        return new EngineActionContext(new LinkedHashMap<String, Object>(facts), output);
    }
}
