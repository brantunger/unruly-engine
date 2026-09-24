package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.Expression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What one language compiles one rule list with. <b>Internal:</b> public only because {@link CompileContext} is sealed
 * to it.
 *
 * @param packageImports Package names, imported with all their classes
 * @param classImports   Classes imported one by one
 * @param classLoader    The class loader that finds the classes in {@code packageImports}
 * @param outputType       The type of the output object, or {@link Object} if the engine wasn't told one
 * @param options          This language's options, by name
 * @param declaredFacts    The declared type of each fact, by name
 * @param allFactsDeclared Whether a run may supply only the declared facts
 * @param warningsLogged   Whether {@link #warn(Expression, InvalidExpressionException.Issue)} logs: it does when the
 *                         rules are loaded, and not when they are only validated
 */
public record EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                   Class<?> outputType, Map<String, String> options,
                                   Map<String, Class<?>> declaredFacts,
                                   boolean allFactsDeclared, boolean warningsLogged) implements CompileContext {

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    /**
     * Keeps unmodifiable copies of the imports, options and declarations, so the context can't change after it's
     * created. A declared primitive type is kept as its wrapper, as {@link #declaredType(String, Class)} says, so a
     * language sees the same declarations from the test kit as from an engine.
     *
     * @throws NullPointerException     if an argument, or an element of a set, of the options or of the declarations,
     *                                  is {@code null}
     * @throws IllegalArgumentException if a fact is declared with the name {@code output}
     */
    public EngineCompileContext {
        Objects.requireNonNull(packageImports, "packageImports must not be null");
        Objects.requireNonNull(classImports, "classImports must not be null");
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        Objects.requireNonNull(outputType, "outputType must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(declaredFacts, "declaredFacts must not be null");
        for (String packageName : packageImports) {
            Objects.requireNonNull(packageName, "packageImports must not contain null");
        }
        for (Class<?> importedClass : classImports) {
            Objects.requireNonNull(importedClass, "classImports must not contain null");
        }
        options.forEach((name, value) -> {
            Objects.requireNonNull(name, "options must not contain null");
            Objects.requireNonNull(value, "options must not contain null");
        });
        packageImports = Set.copyOf(packageImports);
        classImports = Set.copyOf(classImports);
        options = Map.copyOf(options);
        // declaredType names a null fact name or type itself.
        Map<String, Class<?>> declared = new LinkedHashMap<>();
        declaredFacts.forEach((name, type) -> declared.put(name, declaredType(name, type)));
        declaredFacts = Map.copyOf(declared);
    }

    /**
     * Checks a fact's declaration. The builder and the engine then keep the type as it was declared, a primitive type
     * included, so a run can widen a boxed primitive to it.
     *
     * @param name The fact's name
     * @param type The type it was declared with
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code output}, which actions use for the output object
     */
    public static void checkDeclaration(String name, Class<?> type) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (ActionContext.OUTPUT_NAME.equals(name)) {
            throw new IllegalArgumentException("'" + ActionContext.OUTPUT_NAME
                    + "' is reserved for the output object and cannot be declared as a fact");
        }
    }

    /**
     * Returns the type a language is told a fact is declared with: {@code type}, or its wrapper if it's primitive,
     * which a run's value is an instance of once the engine has widened it.
     *
     * @param name The fact's name
     * @param type The type it was declared with
     * @return The type to tell languages
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code output}, which actions use for the output object
     */
    public static Class<?> declaredType(String name, Class<?> type) {
        checkDeclaration(name, type);
        return Widening.wrap(type);
    }

    /**
     * Creates a context for a language with no options and no output type of its own.
     *
     * @param packageImports Package names, imported with all their classes
     * @param classImports   Classes imported one by one
     * @param classLoader    The class loader that finds the classes in {@code packageImports}
     * @throws NullPointerException if an argument, or an element of a set, is {@code null}
     */
    public EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader) {
        this(packageImports, classImports, classLoader, Object.class, Map.of(), Map.of(), false);
    }

    /**
     * Creates a context whose warnings are logged, as when the rules are loaded.
     *
     * @param packageImports   Package names, imported with all their classes
     * @param classImports     Classes imported one by one
     * @param classLoader      The class loader that finds the classes in {@code packageImports}
     * @param outputType       The type of the output object, or {@link Object} if the engine wasn't told one
     * @param options          This language's options, by name
     * @param declaredFacts    The declared type of each fact, by name
     * @param allFactsDeclared Whether a run may supply only the declared facts
     * @throws NullPointerException     if an argument, or an element of a set, of the options or of the declarations,
     *                                  is {@code null}
     * @throws IllegalArgumentException if a fact is declared with the name {@code output}
     */
    public EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                Class<?> outputType, Map<String, String> options, Map<String, Class<?>> declaredFacts,
                                boolean allFactsDeclared) {
        this(packageImports, classImports, classLoader, outputType, options, declaredFacts, allFactsDeclared, true);
    }

    /**
     * Logs a warning at WARN, such as {@code Condition for rule 'prime-rate' has a warning at line 2, column 5: ...},
     * unless the rules are only being validated.
     */
    @Override
    public void warn(Expression source, InvalidExpressionException.Issue issue) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(issue, "issue must not be null");
        if (warningsLogged) {
            log.warn("{} has a warning{}: {}", Failures.expression(source.kind(), source.ruleName()),
                    Failures.position(issue), Failures.escape(Failures.truncate(issue.message())));
        }
    }
}
