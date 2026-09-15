package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.Expression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;

/**
 * The imports and class loader one rule list is compiled with. <b>Internal:</b> public only because
 * {@link CompileContext} is sealed to it.
 *
 * @param packageImports Package names, imported with all their classes
 * @param classImports   Classes imported one by one
 * @param classLoader    The class loader that finds the classes in {@code packageImports}
 */
public record EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader)
        implements CompileContext {

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    /**
     * Keeps unmodifiable copies of the imports, so the context can't change after it's created.
     *
     * @throws NullPointerException if an argument, or an element of a set, is {@code null}
     */
    public EngineCompileContext {
        packageImports = Set.copyOf(packageImports);
        classImports = Set.copyOf(classImports);
        Objects.requireNonNull(classLoader, "classLoader");
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
