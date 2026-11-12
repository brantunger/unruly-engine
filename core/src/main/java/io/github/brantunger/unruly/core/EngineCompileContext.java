package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactType;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.InvalidExpressionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
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
 * @param declaredFacts    The declared type of each fact, by name: a {@link Class}, or a
 *                         {@link java.lang.reflect.ParameterizedType} whose raw type is one
 * @param allFactsDeclared Whether a run may supply only the declared facts
 * @param warningsLogged   Whether {@link #warn(Expression, InvalidExpressionException.Issue)} logs: it does when the
 *                         rules are loaded, and not when they are only validated
 * @param languageImportNames This language's own imports, as written and in order, which a language reads with
 *                            {@link CompileContext#languageImports()}. Not named {@code languageImports}, so that
 *                            method stays the interface's default, which reads them with
 *                            {@link #languageImports(CompileContext)}
 * @param reservedFactNames   The fact names the engine's languages reserve (see
 *                            {@link io.github.brantunger.unruly.api.language.ExpressionLanguage#reservedFactNames()}),
 *                            which the engine checks the declarations against: those its languages reserve for every
 *                            rule list, and those of the languages the rule list uses that reserve them only for the
 *                            rule lists that use them. For a
 *                            context made outside an engine, the names the language reserves, for one made with
 *                            {@link #forLanguage}, or else the name the default reserves,
 *                            {@value ActionContext#OUTPUT_NAME}; each checks the declarations against them
 */
public record EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                   Class<?> outputType, Map<String, String> options,
                                   Map<String, Type> declaredFacts,
                                   boolean allFactsDeclared, boolean warningsLogged,
                                   List<String> languageImportNames, Set<String> reservedFactNames)
        implements CompileContext {

    /**
     * Keeps unmodifiable copies of the imports, options, declarations and reserved names, so the context can't change
     * after it's created. A declared primitive type is kept as its wrapper, and a {@link FactType} as the type it
     * stands for, as {@link #declaredType(String, Type)} says, so a language sees the same declarations from the test
     * kit as from an engine. An engine's own declarations, which it checked when it was built, are kept as they are.
     * Package imports are checked for size and for a package name's shape, and language imports for size, as the
     * engine's {@code build()} checks them, so a language's tests can't be handed one no engine would accept. What a
     * package import resolves to isn't checked, as no class loader is asked: {@code build()} imports
     * {@code java.util.Map.Entry} as a class, and a context keeps it as a package.
     *
     * @throws NullPointerException     if an argument, or an element of a set, of the language imports, of the options
     *                                  or of the declarations, is {@code null}
     * @throws IllegalArgumentException if a package import has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or isn't a valid package name, with the message
     *                                  {@code build()} gives; if a language import has more than 1,000 characters; or
     *                                  if a fact is declared with a blank name, or with a type that is neither a
     *                                  class nor a parameterized type
     * @throws StackOverflowError       if a package import has a code point above U+00FF, the JDK's tables of such
     *                                  characters aren't known to be built yet, as no engine has been built and no
     *                                  such import checked yet, and the stack has too little room left to
     *                                  build them, before any is touched (#1366); the next call checks again
     */
    public EngineCompileContext {
        Objects.requireNonNull(packageImports, "packageImports must not be null");
        Objects.requireNonNull(classImports, "classImports must not be null");
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        Objects.requireNonNull(outputType, "outputType must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(declaredFacts, "declaredFacts must not be null");
        Objects.requireNonNull(languageImportNames, "languageImports must not be null");
        Objects.requireNonNull(reservedFactNames, "reservedFactNames must not be null");
        for (String packageName : packageImports) {
            Objects.requireNonNull(packageName, "packageImports must not contain null");
            ImportSize.checkSize(packageName);
            // The check of its name reads the JDK's table of the plane of each code point, which for one above U+00FF
            // the JVM builds on its first use, and a context may be made deep in a stack with no engine built (#1366).
            CharacterTables.checkRoom(packageName);
            ImportSize.checkPackageName(packageName);
        }
        for (Class<?> importedClass : classImports) {
            Objects.requireNonNull(importedClass, "classImports must not contain null");
        }
        // Loops rather than lambdas, here and for the declarations below: a language's tests make a context with no
        // engine built, maybe deep in a stack, and linking a lambda's call site initializes the JDK's method handle
        // classes, and java.lang.Long on JDK 21, whose first initialization there could overflow (#1260, #1271). This
        // class links none, and core.Widening, which a declared fact initializes, has no static initializer
        // (#1322).
        for (Map.Entry<String, String> option : options.entrySet()) {
            Objects.requireNonNull(option.getKey(), "options must not contain null");
            Objects.requireNonNull(option.getValue(), "options must not contain null");
        }
        for (String name : languageImportNames) {
            Objects.requireNonNull(name, "languageImports must not contain null");
            ImportSize.checkLength(name);
        }
        for (String name : reservedFactNames) {
            Objects.requireNonNull(name, "reservedFactNames must not contain null");
        }
        packageImports = Set.copyOf(packageImports);
        classImports = Set.copyOf(classImports);
        options = Map.copyOf(options);
        reservedFactNames = Set.copyOf(reservedFactNames);
        // An engine's declarations were checked when it was built, and are kept as they are: checking one again asks
        // a parameterized type an application wrote for its raw type, which could answer otherwise by now, or throw.
        if (!(declaredFacts instanceof DeclaredFacts)) {
            // declaredType names a null fact name or type itself.
            Map<String, Type> declared = new LinkedHashMap<>();
            for (Map.Entry<String, Type> fact : declaredFacts.entrySet()) {
                declared.put(fact.getKey(), declaredType(fact.getKey(), fact.getValue()));
            }
            declaredFacts = Map.copyOf(declared);
        }
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
     * Checks a fact's declaration before the engine's languages are known, and returns the type to keep for it. The
     * builder and the engine keep a class as it was declared, a primitive type included, so a run can widen a boxed
     * primitive to it, a parameterized type as it was given, and the type a {@link FactType} stands for rather than
     * the token. A name one of the engine's languages reserves, such as {@value ActionContext#OUTPUT_NAME}, is
     * rejected by {@code build()}, once the languages are known, or by {@code load()} of a rule list that uses a
     * language that reserves it only for such rule lists.
     *
     * @param name The fact's name
     * @param type The type it was declared with
     * @return The type the fact is declared with: a {@link Class}, or a {@link java.lang.reflect.ParameterizedType}
     *         whose raw type is one
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank; or, checked after the name, if {@code type} is
     *                                  neither a class, a parameterized type whose raw type is a class nor a
     *                                  {@code FactType}, with the message
     *                                  {@link CompileContext#rawClass(Type)} gives
     */
    public static Type checkDeclaration(String name, Type type) {
        Type declared = named(name, type);
        // Rejects a type that has no class of its own, naming it.
        CompileContext.rawClass(declared);
        return declared;
    }

    // Checks a declaration's name, and returns its type, a FactType as the type it stands for, not yet checked.
    private static Type named(String name, Type type) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        // The name isn't null, as checked above, and no name is reserved here, so the only problem it can have is
        // being blank.
        if (FactNames.check(name, Set.of()) != null) {
            throw new IllegalArgumentException(FactNames.BLANK_MESSAGE);
        }
        return type instanceof FactType<?> token ? token.type() : type;
    }

    /**
     * Checks an engine's declarations when it's built, as {@link #checkDeclaration(String, Type)} checks each, and
     * works out, once, what they mean from then on: the class a run's value is checked against, and the type
     * languages are told. A parameterized type is asked for its raw type once here and never again, so one an
     * application wrote can't answer differently for the engine's check and for a language, nor fail a
     * {@code load()}.
     *
     * @param declarations The type each fact was declared with, by name
     * @param classes      Receives the class of each fact's type, a primitive class as it was declared
     * @return The type to tell languages of each fact, by name, as a {@link DeclaredFacts}, which a context keeps
     *         as it is
     * @throws NullPointerException     if a name or a type is {@code null}
     * @throws IllegalArgumentException if a name is blank, or a type is one no fact can be declared with
     */
    static Map<String, Type> resolve(Map<String, Type> declarations, Map<String, Class<?>> classes) {
        Map<String, Type> told = new LinkedHashMap<>();
        for (Map.Entry<String, Type> fact : declarations.entrySet()) {
            Type declared = named(fact.getKey(), fact.getValue());
            // The engine's one read of a parameterized type's raw type, which rejects a type that has no class.
            Class<?> type = CompileContext.rawClass(declared);
            classes.put(fact.getKey(), type);
            told.put(fact.getKey(), declared instanceof Class<?> ? Widening.wrap(type) : declared);
        }
        return new DeclaredFacts(told);
    }

    /**
     * Returns the type a language is told a fact is declared with: the type {@link #checkDeclaration(String, Type)}
     * keeps, or its wrapper if it's a primitive class, which a run's value is an instance of once the engine has
     * widened it.
     *
     * @param name The fact's name
     * @param type The type it was declared with
     * @return The type to tell languages
     * @throws NullPointerException     if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank, or {@code type} is one no fact can be declared with
     */
    public static Type declaredType(String name, Type type) {
        Type declared = checkDeclaration(name, type);
        return declared instanceof Class<?> declaredClass ? Widening.wrap(declaredClass) : declared;
    }

    // A map of declarations as one of the types they all are, to read only: the canonical constructor copies it, and
    // reports a null map itself, after the arguments before it.
    @SuppressWarnings("unchecked")
    private static Map<String, Type> readOnly(Map<String, ? extends Type> declaredFacts) {
        return (Map<String, Type>) declaredFacts;
    }

    /**
     * Creates a context for a language with no options and no output type of its own.
     *
     * @param packageImports Package names, imported with all their classes
     * @param classImports   Classes imported one by one
     * @param classLoader    The class loader that finds the classes in {@code packageImports}
     * @throws NullPointerException     if an argument, or an element of a set, is {@code null}
     * @throws IllegalArgumentException if a package import has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or isn't a valid package name
     * @throws StackOverflowError       as the canonical constructor says, for a package import above U+00FF (#1366)
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
     *                                  dot-separated parts, or isn't a valid package name, or a fact is declared with
     *                                  a blank name or the name {@value ActionContext#OUTPUT_NAME}, which the default
     *                                  reserves, or with a type that is neither a class nor a parameterized type
     * @throws StackOverflowError       as the canonical constructor says, for a package import above U+00FF (#1366)
     */
    public EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                Class<?> outputType, Map<String, String> options,
                                Map<String, ? extends Type> declaredFacts, boolean allFactsDeclared) {
        this(packageImports, classImports, classLoader, outputType, options, declaredFacts, allFactsDeclared, true,
                List.of());
    }

    /**
     * Creates a context whose declared facts can't have the name {@value ActionContext#OUTPUT_NAME}, which the default
     * {@link io.github.brantunger.unruly.api.language.ExpressionLanguage#reservedFactNames()} reserves, as the test
     * kit makes one outside an engine. A released test kit calls it, so it stays.
     *
     * @param packageImports      Package names, imported with all their classes
     * @param classImports        Classes imported one by one
     * @param classLoader         The class loader that finds the classes in {@code packageImports}
     * @param outputType          The type of the output object, or {@link Object} if the engine wasn't told one
     * @param options             This language's options, by name
     * @param declaredFacts       The declared type of each fact, by name
     * @param allFactsDeclared    Whether a run may supply only the declared facts
     * @param warningsLogged      Whether {@link #warn(Expression, InvalidExpressionException.Issue)} logs
     * @param languageImportNames This language's own imports, as written and in order
     * @throws NullPointerException     if an argument, or an element of a set, of the language imports, of the options
     *                                  or of the declarations, is {@code null}
     * @throws IllegalArgumentException if a package import has more than 1,000 characters or more than 64
     *                                  dot-separated parts, or isn't a valid package name, a language import has more
     *                                  than 1,000 characters, or a fact is declared with a blank name or the name
     *                                  {@value ActionContext#OUTPUT_NAME}, or with a type that is neither a class nor
     *                                  a parameterized type
     * @throws StackOverflowError       as the canonical constructor says, for a package import above U+00FF (#1366)
     */
    public EngineCompileContext(Set<String> packageImports, Set<Class<?>> classImports, ClassLoader classLoader,
                                Class<?> outputType, Map<String, String> options,
                                Map<String, ? extends Type> declaredFacts, boolean allFactsDeclared,
                                boolean warningsLogged, List<String> languageImportNames) {
        this(packageImports, classImports, classLoader, outputType, options, readOnly(declaredFacts),
                allFactsDeclared, warningsLogged, languageImportNames, Set.of(ActionContext.OUTPUT_NAME));
        // After the canonical constructor's checks, so a null argument or an import too long is reported first, as
        // it was while every context rejected the name. An engine checked its own declarations when it was built.
        if (this.declaredFacts.containsKey(ActionContext.OUTPUT_NAME)) {
            throw new IllegalArgumentException(FactNames.outputMessage(true));
        }
    }

    /**
     * Creates a context whose warnings are logged, outside an engine, as the test kit makes one for a language: its
     * declared facts can't have a name the language reserves, so it rejects what an engine with only this language
     * rejects when it's built. The language is asked for its reserved names once, as an engine asks it.
     *
     * @param language            The language the context is for
     * @param packageImports      Package names, imported with all their classes
     * @param classImports        Classes imported one by one
     * @param classLoader         The class loader that finds the classes in {@code packageImports}
     * @param outputType          The type of the output object, or {@link Object} if the engine wasn't told one
     * @param options             This language's options, by name
     * @param declaredFacts       The declared type of each fact, by name
     * @param allFactsDeclared    Whether a run may supply only the declared facts
     * @param languageImportNames This language's own imports, as written and in order
     * @return The context, whose {@link #reservedFactNames()} are the language's
     * @throws NullPointerException     if an argument, or an element of a set, of the language imports, of the options
     *                                  or of the declarations, is {@code null}
     * @throws IllegalArgumentException if the language's name is {@code null} or blank; if a package import has more
     *                                  than 1,000 characters or more than 64 dot-separated parts, or isn't a valid
     *                                  package name, a language import has more than 1,000 characters, or a fact is
     *                                  declared with a blank name, or with a type that is neither a class nor a
     *                                  parameterized type; or if a fact is declared with a name the language
     *                                  reserves, checked last
     * @throws IllegalStateException    if the language's
     *                                  {@link ExpressionLanguage#reservedFactNames()} returns {@code null} or a set
     *                                  holding {@code null}, as {@code build()} throws it
     * @throws StackOverflowError       as the canonical constructor says, for a package import above U+00FF (#1366)
     */
    public static EngineCompileContext forLanguage(ExpressionLanguage language, Set<String> packageImports,
                                                   Set<Class<?>> classImports, ClassLoader classLoader,
                                                   Class<?> outputType, Map<String, String> options,
                                                   Map<String, ? extends Type> declaredFacts,
                                                   boolean allFactsDeclared, List<String> languageImportNames) {
        Objects.requireNonNull(language, "language must not be null");
        String name = language.name();
        if (LanguageNames.check(name, Map.of()) != null) {
            throw new IllegalArgumentException("An expression language's name must not be null or blank: "
                    + language.getClass().getName());
        }
        Map<String, String> reserved = FactNames.reserved(Map.of(name, language));
        EngineCompileContext context = new EngineCompileContext(packageImports, classImports, classLoader,
                outputType, options, readOnly(declaredFacts), allFactsDeclared, true, languageImportNames,
                reserved.keySet());
        // Last, as build() checks them once the builder has checked each name for null and blank.
        for (String fact : context.declaredFacts().keySet()) {
            String reservedBy = reserved.get(fact);
            if (reservedBy != null) {
                throw new IllegalArgumentException(FactNames.reservedMessage(fact, reservedBy, true));
            }
        }
        return context;
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
            FailurePolicy.logWarn(Warnings.LOG, "{} has a warning{}: {}",
                    FailurePolicy.expression(source.kind(), source.ruleName()), FailurePolicy.position(issue),
                    Text.clip(issue.message()));
        }
    }

    /**
     * The logger warnings go to, in a class of its own so that this one has no static initializer: the builder checks
     * each declared fact with {@link #checkDeclaration(String, Type)}, maybe deep in a stack, before the engine has
     * made room for initializing classes, and creating the logger may start SLF4J. The engine initializes it when it's
     * built (see FirstUseClasses).
     */
    static final class Warnings {

        static final Logger LOG = LoggerFactory.getLogger(CoreRulesEngine.LOGGER_NAME);

        private Warnings() {
        }
    }

    // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods, which
    // can fail for good when first called deep in the stack (#996). equals compares the components last first, as
    // ObjectMethods does.
    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof EngineCompileContext that
                && Objects.equals(reservedFactNames, that.reservedFactNames)
                && Objects.equals(languageImportNames, that.languageImportNames)
                && warningsLogged == that.warningsLogged && allFactsDeclared == that.allFactsDeclared
                && Objects.equals(declaredFacts, that.declaredFacts) && Objects.equals(options, that.options)
                && Objects.equals(outputType, that.outputType) && Objects.equals(classLoader, that.classLoader)
                && Objects.equals(classImports, that.classImports)
                && Objects.equals(packageImports, that.packageImports);
    }

    @Override
    public final int hashCode() {
        int hash = Objects.hashCode(packageImports);
        hash = hash * 31 + Objects.hashCode(classImports);
        hash = hash * 31 + Objects.hashCode(classLoader);
        hash = hash * 31 + Objects.hashCode(outputType);
        hash = hash * 31 + Objects.hashCode(options);
        hash = hash * 31 + Objects.hashCode(declaredFacts);
        hash = hash * 31 + Boolean.hashCode(allFactsDeclared);
        hash = hash * 31 + Boolean.hashCode(warningsLogged);
        hash = hash * 31 + Objects.hashCode(languageImportNames);
        return hash * 31 + Objects.hashCode(reservedFactNames);
    }

    @Override
    public final String toString() {
        return "EngineCompileContext[packageImports=" + packageImports + ", classImports=" + classImports
                + ", classLoader=" + classLoader + ", outputType=" + outputType + ", options=" + options
                + ", declaredFacts=" + declaredFacts + ", allFactsDeclared=" + allFactsDeclared
                + ", warningsLogged=" + warningsLogged + ", languageImportNames=" + languageImportNames
                + ", reservedFactNames=" + reservedFactNames + "]";
    }
}
