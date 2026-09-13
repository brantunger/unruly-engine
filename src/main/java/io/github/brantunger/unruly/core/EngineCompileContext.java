package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.CompileContext;

import java.util.Set;

/**
 * The imports and class loader one rule list is compiled with.
 *
 * @param packageImports Package names, imported with all their classes
 * @param classImports   Classes imported one by one
 * @param classLoader    The class loader that finds the classes in {@code packageImports}
 */
record EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader)
        implements CompileContext {
}
