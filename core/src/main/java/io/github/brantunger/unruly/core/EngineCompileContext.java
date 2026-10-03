package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.Expression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
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
 * @param languageImportNames This language's own imports, as written and in order, which a language reads with
 *                            {@link CompileContext#languageImports()}. Not named {@code languageImports}, so that
 *                            method stays the interface's default, which reads them with
 *                            {@link #languageImports(CompileContext)}
 */
public record EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                   Class<?> outputType, Map<String, String> options,
                                   Map<String, Class<?>> declaredFacts,
                                   boolean allFactsDeclared, boolean warningsLogged,
                                   List<String> languageImportNames) implements CompileContext {

    /**
     * Keeps unmodifiable copies of the imports, options and declarations, so the context can't change after it's
     * created. A declared primitive type is kept as its wrapper, as {@link #declaredType(String, Class)} says, so a
     * language sees the same declarations from the test kit as from an engine. Package imports and language
     * imports are checked for size as the engine's {@code build()} checks them, so a language's tests can't be handed
     * one no engine would accept.
     *
     * @throws NullPointerException     if an argument, or an element of a set, of the language imports, of the options
     *                                  or of the declarations, is {@code null}
     * @throws IllegalArgumentException if a package import has more than 1,000 characters or more than 64
     *                                  dot-separated parts, a language import has more than 1,000 characters, or a
     *                                  fact is declared with a blank name or the name {@code output}
     */
    public EngineCompileContext {
        Objects.requireNonNull(packageImports, "packageImports must not be null");
        Objects.requireNonNull(classImports, "classImports must not be null");
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        Objects.requireNonNull(outputType, "outputType must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(declaredFacts, "declaredFacts must not be null");
        Objects.requireNonNull(languageImportNames, "languageImports must not be null");
        for (String packageName : packageImports) {
            Objects.requireNonNull(packageName, "packageImports must not contain null");
            ImportResolver.checkSize(packageName);
        }
        for (Class<?> importedClass : classImports) {
            Objects.requireNonNull(importedClass, "classImports must not contain null");
        }
        options.forEach((name, value) -> {
            Objects.requireNonNull(name, "options must not contain null");
            Objects.requireNonNull(value, "options must not contain null");
        });
        for (String name : languageImportNames) {
            Objects.requireNonNull(name, "languageImports must not contain null");
            ImportResolver.checkLength(name);
        }
        packageImports = Set.copyOf(packageImports);
        classImports = Set.copyOf(classImports);
        options = Map.copyOf(options);
        // declaredType names a null fact name or type itself.
        Map<String, Class<?>> declared = new LinkedHashMap<>();
        declaredFacts.forEach((name, type) -> declared.put(name, declaredType(name, type)));
        declaredFacts = Map.copyOf(declared);
        languageImportNames = List.copyOf(languageImportNames);
    }

    /**
     * Returns the imports a context's language was given for itself, as {@link CompileContext#languageImports()}
     * describes them.
     *
     * @param context A compile context, which the engine implements
     * @return The language's imports, as written and in order; unmodifiable
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public static List<String> languageImports(CompileContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return ((EngineCompileContext) context).languageImportNames();
    }

    /**
     * Checks a fact's declaration. The builder and the engine then keep the type as it was declared, a primitive type
     * included, so a run can widen a boxed primitive to it.
     *
     * @param name The fact's name
     * @param type The type it was declared with
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank, or is {@code output}, which actions use for the
     *                                  output object
     */
    public static void checkDeclaration(String name, Class<?> type) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        FactNames.Problem problem = FactNames.check(name);
        // Not a switch: the class javac makes for one would be initialized by the first rejected name, maybe deep in a
        // stack, and Problem itself is compared only once there is one (see RunClasses).
        if (problem != null) {
            // The name isn't null, as checked above, so the only other problem it can have is being output.
            throw new IllegalArgumentException(problem == FactNames.Problem.BLANK ? "fact name must not be blank"
                    : "'" + ActionContext.OUTPUT_NAME
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
     * @throws IllegalArgumentException if {@code name} is blank, or is {@code output}, which actions use for the
     *                                  output object
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
     * @throws NullPointerException     if an argument, or an element of a set, is {@code null}
     * @throws IllegalArgumentException if a package import has more than 1,000 characters or more than 64
     *                                  dot-separated parts
     */
    public EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader) {
        this(packageImports, classImports, classLoader, Object.class, Map.of(), Map.of(), false);
    }

    /**
     * Creates a context whose warnings are logged, as when the rules are loaded, for a language with no imports of
     * its own.
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
     * @throws IllegalArgumentException if a package import has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or a fact is declared with a blank name or the name
     *                                  {@code output}
     */
    public EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                Class<?> outputType, Map<String, String> options, Map<String, Class<?>> declaredFacts,
                                boolean allFactsDeclared) {
        this(packageImports, classImports, classLoader, outputType, options, declaredFacts, allFactsDeclared, true,
                List.of());
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
            Warnings.LOG.warn("{} has a warning{}: {}", Failures.expression(source.kind(), source.ruleName()),
                    Failures.position(issue), Failures.clip(issue.message()));
        }
    }

    /**
     * The logger warnings go to, in a class of its own so that this one has no static initializer: the builder checks
     * each declared fact with {@link #checkDeclaration(String, Class)}, maybe deep in a stack, before the engine has
     * made room for initializing classes, and creating the logger may start SLF4J. The engine initializes it when it's
     * built (see RunClasses).
     */
    static final class Warnings {

        static final Logger LOG = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

        private Warnings() {
        }
    }
}
