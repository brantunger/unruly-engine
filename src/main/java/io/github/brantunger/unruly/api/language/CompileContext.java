package io.github.brantunger.unruly.api.language;

import java.util.Set;

/**
 * What every compilation of a rule list is given: the imports registered with
 * {@link io.github.brantunger.unruly.api.RulesEngine#addImports(Set)} before
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)}, and the class loader to look
 * classes up with. A language without imports ignores them.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a language. Don't implement it: in 2.0 it may be restricted to
 * the engine's own implementation. A method added to it in a 1.x release is a {@code default} method.
 * </p>
 */
public interface CompileContext {

    /**
     * Returns the imported packages, whose classes rules can refer to by their simple names.
     *
     * @return Package names such as {@code java.util}; unmodifiable
     */
    Set<String> packageImports();

    /**
     * Returns the classes imported one by one.
     *
     * @return The classes; unmodifiable
     */
    Set<Class<?>> classImports();

    /**
     * Returns the class loader to look up classes in {@link #packageImports()} with: the context class loader of the
     * thread that called {@code setRuleList()}, or the engine's own class loader if that thread has none.
     *
     * @return The class loader, never {@code null}
     */
    ClassLoader classLoader();
}
