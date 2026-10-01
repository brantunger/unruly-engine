package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.MessageText;
import org.mvel2.util.ErrorUtil;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MVEL 2 as an expression language for the engine, named {@value #LANGUAGE_NAME}. A rule whose language is
 * {@code null} is written in MVEL when MVEL is the engine's only language, or when
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#defaultLanguage(String)} names it.
 *
 * <ul>
 *     <li>Conditions and actions have the same access to the JVM as Java code, including processes, files and
 *     reflection. There is no sandbox, so only use rules from trusted sources.</li>
 *     <li>A condition is rejected when its text contains an assignment, {@code ++}, {@code --}, or the keywords
 *     {@code with}, {@code def}, {@code function} or {@code import_static}. A write made by calling a method, such as
 *     {@code claim.setApproved(true)}, can't be detected.</li>
 *     <li>Variables an action declares stay local to that action, and assigning to {@code output} fails.</li>
 *     <li>A fact name must be a Java identifier that isn't one of MVEL's reserved words, such as {@code empty} or
 *     {@code in}, and isn't a class name MVEL resolves instead, such as {@code Math} or an imported class.</li>
 *     <li>MVEL caches accessors in a compiled expression without synchronization, so each session, which one run
 *     uses at a time, runs its own compiled copy of each expression.</li>
 *     <li>MVEL's imports are Java packages and classes, given with
 *     {@link io.github.brantunger.unruly.api.RulesEngineBuilder#imports(String...)}. It takes no imports of its own:
 *     given any with {@link io.github.brantunger.unruly.api.RulesEngineBuilder#languageImports(String, String...)},
 *     {@link #newCompiler(CompileContext)} throws {@link IllegalArgumentException}. The engine creates MVEL's compiler
 *     when a rule list given to {@code load()} or {@code validate()} has an MVEL rule, and for a list with no rules
 *     too when MVEL is the default language. That rule list then fails: {@code load()} throws, and
 *     {@code validate()} returns, a {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} with
 *     the throw as its cause. A rule list that doesn't use MVEL never has its imports checked.</li>
 * </ul>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/mvel.md">MVEL</a>
 */
public final class MvelExpressionLanguage implements ExpressionLanguage {

    /** The language's name. */
    public static final String LANGUAGE_NAME = "mvel";

    /**
     * Creates the MVEL language. It holds no state: each rule list's state lives in its compiler.
     */
    public MvelExpressionLanguage() {
        // Nothing to set up.
    }

    @Override
    public String name() {
        return LANGUAGE_NAME;
    }

    /**
     * Creates the compiler for one rule list, with the context's Java imports, class loader, declared facts and
     * options.
     *
     * @param context The imports and class loader the rule list is compiled with
     * @return A new compiler, used for this rule list only
     * @throws IllegalArgumentException if the context has {@link CompileContext#languageImports() language imports},
     *                                  which MVEL doesn't take, or an option MVEL can't apply
     */
    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        Objects.requireNonNull(context, "context must not be null");
        rejectLanguageImports(context.languageImports());
        ErrorReporting.initialize();
        return new MvelExpressionCompiler(new Imports(Set.copyOf(context.packageImports()),
                Set.copyOf(context.classImports()), context.classLoader(), DeclaredTypes.inputsFor(context)));
    }

    /**
     * Rejects imports given to MVEL alone: its imports are Java packages and classes, which every language of an engine
     * is given from {@code imports(...)}, so one given to it alone is a mistake rather than something to ignore.
     *
     * @param languageImports The imports given to MVEL alone
     * @throws IllegalArgumentException if there are any, naming them
     */
    private static void rejectLanguageImports(List<String> languageImports) {
        if (!languageImports.isEmpty()) {
            throw new IllegalArgumentException("MVEL takes no language imports; give Java imports with imports(...): "
                    + languageImports.stream().map(name -> "'" + MessageText.quote(name) + "'")
                            .collect(Collectors.joining(", ")));
        }
    }

    /**
     * MVEL formats every compile error with ErrorUtil, whose static initializer creates a logger. When the first error
     * in the JVM is a stack overflow, such as a deeply nested rule on a small stack, that initializer fails for lack of
     * stack, and the JVM marks the class unusable: every later MVEL compile error in the JVM then throws
     * NoClassDefFoundError. This holder initializes it when a rule list starts loading, before any rule is compiled, so
     * an overflow only fails the rule that caused it. It isn't done when the language is created, because an engine
     * creates its languages when it's built, and building an engine loads no MVEL class. It also installs
     * {@link MvelWarningFilter} on MVEL's logger, once for each class loader that loads this module, before any rule
     * runs, for the same reason: a run would otherwise initialize it first, possibly deep in the stack of a run nested
     * in an action, where a stack overflow would leave it unusable and fail every later run.
     */
    private static final class ErrorReporting {

        static {
            new ErrorUtil();
            MvelWarningFilter.initialize();
        }

        private ErrorReporting() {
        }

        /** Initializes ErrorUtil and the filter, the first time it's called, by initializing this class. */
        static void initialize() {
            // The static initializer does the work.
        }
    }
}
