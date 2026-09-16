package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;

import java.util.Map;
import java.util.Set;

/**
 * What every compilation of a rule list is given: the imports the engine was built with, from
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#imports(String...)}, the class loader to look classes up
 * with, the type of the output object, this language's options, and a way to report warnings. A language uses what it
 * needs and ignores the rest.
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
     * thread that called {@code load()}, or the engine's own class loader if that thread has none.
     *
     * @return The class loader, never {@code null}
     */
    ClassLoader classLoader();

    /**
     * Returns the type of the output object, which the engine was built with through
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#outputType(Class)}. A language may use it, for example
     * to check the properties its actions return; the engine doesn't.
     *
     * @return The output type, or {@link Object} if the engine wasn't told one
     */
    Class<?> outputType();

    /**
     * Returns the facts the engine was declared with, through
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#fact(String, Class)}, by fact name. A language may
     * check its expressions against them, for example to reject a misspelled property when the rules load, or ignore
     * them; the engine checks a run's values against them whatever the language does.
     *
     * <p>
     * A declared type is what a run's value must be an instance of. It isn't a promise that the fact is present,
     * unless {@link #allFactsDeclared()} is {@code true}.
     * </p>
     *
     * @return The declared type of each fact, by name, empty if the engine was told none; unmodifiable
     */
    Map<String, Class<?>> declaredFacts();

    /**
     * Returns whether a run may supply only the facts in {@link #declaredFacts()}, which the engine was told with
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#requireDeclaredFacts()}. When it's {@code true}, every
     * name a rule can legitimately refer to is declared, so a language may reject an expression that refers to
     * anything else. When it's {@code false}, a run may supply facts nobody declared, so an undeclared name isn't a
     * mistake.
     *
     * @return {@code true} if every fact a run may supply is declared
     */
    boolean allFactsDeclared();

    /**
     * Returns this language's options, set with
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#option(String, String, String)}. What an option means
     * is up to the language.
     *
     * @return The values by option name, empty if this language was given none; unmodifiable
     */
    Map<String, String> options();

    /**
     * Reports a problem that doesn't stop an expression compiling, such as use of a deprecated function. The engine
     * logs it at WARN, naming the rule, whether the expression is its condition or its action, and where the problem
     * is, and loading carries on. To stop the expression compiling, throw an {@link InvalidExpressionException} instead.
     *
     * @param source The expression the problem is in
     * @param issue  The problem, reported as a warning whatever its severity
     * @throws NullPointerException if {@code source} or {@code issue} is {@code null}
     */
    void warn(Expression source, InvalidExpressionException.Issue issue);
}
