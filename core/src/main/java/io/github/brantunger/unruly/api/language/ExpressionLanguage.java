package io.github.brantunger.unruly.api.language;

/**
 * A language that rule conditions and actions are written in, such as MVEL.
 *
 * <p>
 * The engine compiles each rule list with a new {@link ExpressionCompiler}, which keeps whatever the language caches
 * for that list. The engine enforces the rest of the rule contract itself, whatever the language: rule names are
 * unique, conditions and actions aren't blank, rules run in priority order, a condition evaluates to a
 * {@link Boolean}, no fact has a blank name or is named {@value ActionContext#OUTPUT_NAME}, and failures are reported
 * as {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} or
 * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} and to listeners.
 * </p>
 *
 * <p>
 * Implementations must be thread-safe: an engine can compile rule lists on several threads.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface in a later 2.x release is a
 * {@code default} method, so an existing language keeps compiling and working.
 * </p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/custom.md">Writing an expression
 *      language</a>
 */
public interface ExpressionLanguage {

    /**
     * Returns the language's name, such as {@code "mvel"}. It must return the same name every time. The engine reads
     * it once, when the language is given to the builder or found with {@link java.util.ServiceLoader}, and knows the
     * language by that name from then on.
     *
     * @return The name, never {@code null} or blank
     */
    String name();

    /**
     * Creates the compiler for one rule list.
     *
     * @param context The imports and class loader the rule list is compiled with
     * @return A new compiler, used for this rule list only
     */
    ExpressionCompiler newCompiler(CompileContext context);

    /**
     * Initializes the classes with a static initializer that the language's first compile or run would otherwise be
     * the first to use: the language's own and those of any library it runs on. A rule list may be loaded, or a rule
     * run, deep in another run's stack, for example from an action, and a {@link StackOverflowError} inside a class's
     * static initializer leaves the class unusable for the life of the JVM: every later use of it, by any engine,
     * throws {@link NoClassDefFoundError}. Initializing them here leaves a later load or run nothing to initialize.
     *
     * <p>
     * The engine calls it before anything is compiled with the language, having first checked that the thread has
     * room on its stack for it if no engine has prepared a language of the same class yet. It calls it while an
     * engine is built for each language the builder names, by giving it, naming it the default, or giving it options
     * or imports, on every build of such an engine. It calls it for another language the engine has, one found with
     * {@link java.util.ServiceLoader}, even the only one found, which is then the default language, when a rule list
     * first uses it, once for the language's class. So it must be cheap once it has done its work, as initializing a
     * class that is already initialized is. It should do nothing else: it creates no state an engine uses, and may be
     * called on a language that never compiles anything. Anything it throws while an engine is built fails the build,
     * unchanged; anything it throws when a rule list first uses the language fails the language for that rule list, as
     * what {@link #newCompiler(CompileContext)} throws does, reported as the language failing to prepare, and the
     * language's next use prepares it again. By default it does nothing.
     * </p>
     */
    default void prepare() {
        // Nothing to initialize.
    }
}
