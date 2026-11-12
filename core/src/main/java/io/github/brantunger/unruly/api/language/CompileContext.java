package io.github.brantunger.unruly.api.language;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What every compilation of a rule list is given: the imports the engine was built with, from
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#imports(String...)}, the class loader to look classes up
 * with, the type of the output object, this language's options and its own imports, and a way to report warnings. A
 * language uses what it needs and ignores the rest.
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
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#fact(String, Type)}, by fact name. A language may
     * check its expressions against them, for example to reject a misspelled property when the rules load, or ignore
     * them; the engine checks a run's values against them whatever the language does. A fact declared with a primitive
     * type, such as {@code int}, is given as its wrapper, {@link Integer}.
     *
     * <p>
     * Each type is one of two kinds: a {@link Class}, or a {@link java.lang.reflect.ParameterizedType} whose raw type
     * is a {@code Class}, such as {@code java.util.List<java.lang.String>}, for a fact declared with its type
     * arguments. A fact declared with a {@link io.github.brantunger.unruly.api.FactType} is given as the type the
     * token stands for, never as the token: a {@code FactType<List<String>>} as that parameterized type, and a
     * {@code FactType<String>} as {@code String.class}. A parameterized type's own type arguments are as the
     * application wrote them, and can be any {@link Type}, a wildcard or a type variable included. A language with no
     * use for type arguments reads each type's class with {@link #rawClass(Type)}; one that casts a value to
     * {@code Class} fails with a {@link ClassCastException} for a fact declared with type arguments.
     * </p>
     *
     * <p>
     * A language that reads type arguments, such as a list's element type, reads a {@link WildcardType} as its upper
     * bound, {@code getUpperBounds()[0]}. An argument can be a wildcard that the application's source doesn't show:
     * Kotlin's read-only {@code List}, {@code Set} and {@code Map} are covariant, so a Kotlin
     * {@code FactType<List<String>>} is given as {@code java.util.List<? extends java.lang.String>}.
     * </p>
     *
     * <p>
     * A declared type's class is what the value a language is given is an instance of; for a fact declared with a
     * primitive type, the engine first widens a narrower boxed primitive the run supplied, as
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#fact(String, Class)} describes. The engine checks no
     * more than the class: a value's type arguments are erased, so a fact declared as a {@code List<String>} can hold
     * a list of anything. It isn't a promise that the fact is present, unless {@link #allFactsDeclared()} is
     * {@code true}.
     * </p>
     *
     * @return The declared type of each fact, by name, empty if the engine was told none; unmodifiable
     */
    Map<String, Type> declaredFacts();

    /**
     * Returns the class of a declared fact's type, as {@link #declaredFacts()} gives one: the class itself, or a
     * parameterized type's raw class, such as {@link java.util.List} for {@code List<String>}. It's the class the
     * engine checks a run's value against, and all that a language with no use for type arguments reads of a
     * declaration. It allocates nothing for a type the engine accepted.
     *
     * <p>
     * It's for a declared type, not for one of a parameterized type's type arguments. An argument can be a wildcard or
     * a type variable, which this method rejects with a message about a fact's type: read a wildcard's upper bound
     * first, as {@link #declaredFacts()} describes.
     * </p>
     *
     * @param type A declared fact's type: a value of {@code declaredFacts()}, or a type a fact could be declared with
     * @return The type's class, which is primitive only if {@code type} is that primitive class
     * @throws NullPointerException     if {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code type} is neither, so no fact can be declared with it: a
     *                                  {@link TypeVariable}, a {@link WildcardType}, a {@link GenericArrayType}, a
     *                                  {@link ParameterizedType} whose raw type isn't a {@code Class}, or is a
     *                                  primitive one, such as {@code int.class}, or another implementation of
     *                                  {@code Type}. That includes an
     *                                  {@link io.github.brantunger.unruly.api.FactType} passed as it is, which
     *                                  {@code declaredFacts()} never holds: pass the type it stands for, its
     *                                  {@code type()}. The message names the type
     */
    static Class<?> rawClass(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        if (type instanceof Class<?> declared) {
            return declared;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw
                && !raw.isPrimitive()) {
            return raw;
        }
        String kind;
        if (type instanceof TypeVariable<?>) {
            kind = "the type variable ";
        } else if (type instanceof WildcardType) {
            kind = "the wildcard ";
        } else if (type instanceof GenericArrayType) {
            kind = "the generic array type ";
        } else if (type instanceof ParameterizedType) {
            kind = "a parameterized type whose raw type isn't a class or an interface, ";
        } else {
            kind = "a " + type.getClass().getName() + ", ";
        }
        throw new IllegalArgumentException("A fact's type must be a class or a parameterized type, such as"
                + " List<String>, not " + kind + type.getTypeName());
    }

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
     * Returns the imports given for this language only, with
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#languageImports(String, String...)}, as written and in
     * order, duplicates included. The engine doesn't resolve or check them, beyond their length, and the engine's other
     * languages don't see them. What one means, such as a module, a file or a namespace, is up to the language, which
     * can reject one it can't use by throwing from {@link ExpressionLanguage#newCompiler(CompileContext)}. The engine
     * creates a language's compiler when a rule list given to {@code load()} or {@code validate()} has a rule in that
     * language, and creates the default language's compiler for a list with no rules too. A throw from
     * {@code newCompiler} then fails that rule list: {@code load()} throws, and {@code validate()} returns, a
     * {@link io.github.brantunger.unruly.api.RuleCompilationException} with the throw as its cause. A
     * language no such rule list uses never has its imports checked.
     *
     * <p>
     * Unlike {@link #packageImports()} and {@link #classImports()}, which hold the engine's Java imports for every
     * language, these suit a language whose imports aren't Java classes or packages.
     * </p>
     *
     * @return The imports, empty if this language was given none; unmodifiable
     */
    default List<String> languageImports() {
        return io.github.brantunger.unruly.core.EngineCompileContext.languageImports(this);
    }

    /**
     * Reports a problem that doesn't stop an expression compiling, such as use of a deprecated function. The engine
     * logs it at WARN, naming the rule, whether the expression is its condition or its action, and where the problem
     * is, and loading carries on. While the rules are only validated, with
     * {@link io.github.brantunger.unruly.api.RulesEngine#validate(java.util.List)}, the warning is discarded. To stop
     * the expression compiling, throw an {@link InvalidExpressionException} instead.
     *
     * @param source The expression the problem is in
     * @param issue  The problem, reported as a warning whatever its severity
     * @throws NullPointerException if {@code source} or {@code issue} is {@code null}
     */
    void warn(Expression source, InvalidExpressionException.Issue issue);
}
