package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.CompileContext;

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
}
