package io.github.brantunger.unruly.api.language;

import java.util.Set;

/**
 * What every compilation of a rule list is given: the imports registered with
 * {@link io.github.brantunger.unruly.api.RulesEngine#addImports(Set)} before
 * {@link io.github.brantunger.unruly.api.RulesEngine#setRuleList(java.util.List)}, and the class loader to look
 * classes up with. A language without imports ignores them.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a language. It's sealed, so a language can't implement it; a
 * language's unit tests create one with {@code io.github.brantunger.unruly.test.LanguageTestContexts}, from the
 * {@code unruly-engine-test} artifact. Because only the engine implements it, a later release can add methods to it
 * without breaking languages.
 * </p>
 */
public sealed interface CompileContext permits io.github.brantunger.unruly.core.EngineCompileContext {

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
