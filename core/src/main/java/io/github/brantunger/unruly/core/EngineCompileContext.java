package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.Expression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public record EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                   Class<?> outputType, Map<String, String> options,
                                   Map<String, Class<?>> declaredFacts,
                                   boolean allFactsDeclared) implements CompileContext {

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    /**
     * Keeps unmodifiable copies of the imports and options, so the context can't change after it's created.
     *
     * @throws NullPointerException if an argument, or an element of a set or of the options, is {@code null}
     */
    public EngineCompileContext {
        packageImports = Set.copyOf(packageImports);
        classImports = Set.copyOf(classImports);
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(outputType, "outputType");
        options = Map.copyOf(options);
        declaredFacts = Map.copyOf(declaredFacts);
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
     * Logs a warning at WARN, such as {@code Condition for rule 'prime-rate' has a warning at line 2, column 5: ...}.
     */
    @Override
    public void warn(Expression source, InvalidExpressionException.Issue issue) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(issue, "issue");
        log.warn("{} has a warning{}: {}", Failures.expression(source.kind(), source.ruleName()),
                Failures.position(issue), issue.message());
    }
}
