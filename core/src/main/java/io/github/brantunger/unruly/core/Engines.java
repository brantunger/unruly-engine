package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngine;

import java.util.function.Supplier;

/**
 * Creates the engines for {@link io.github.brantunger.unruly.api.RulesEngineBuilder}, which is the only supported way
 * to create one. The engine classes are package-private, so this is the one public entry point into this package.
 *
 * <p>
 * <b>Internal:</b> this class may change in any release. Use {@link io.github.brantunger.unruly.api.RulesEngineBuilder}
 * instead.
 * </p>
 */
public final class Engines {

    private Engines() {
    }

    /**
     * Initializes the classes with a static initializer that engines use, the engine's own and the JDK's and libraries'
     * that building, loading and running rules would otherwise be the first to use, unless an engine built before has.
     * The builder calls it before it resolves its settings, as each method here does before it creates the engine,
     * because building, loading or running may be done deep in a stack, and a {@link StackOverflowError} inside a
     * class's static initializer leaves the class unusable for the life of the JVM. So the room for them is checked
     * first, and an engine built too deep throws {@link StackOverflowError} before any of them is touched, and before
     * its settings are checked; the next build checks again.
     *
     * @throws StackOverflowError if the classes aren't initialized yet and the thread has too little stack left to
     *                            initialize them
     */
    public static void initialize() {
        RunClasses.initialize();
    }

    /**
     * Creates an engine that fires the highest-priority matching rule.
     *
     * @param outputFactory Creates the output object
     * @param configuration The builder's settings
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are
     *                                  given for a language the engine doesn't have
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if an argument is {@code null}
     * @throws StackOverflowError       if this is the JVM's first engine and the thread has too little stack left to
     *                                  initialize the classes engines use, checked first, before any of them is
     *                                  touched and before the settings are (see {@link #initialize()}); or if a
     *                                  language the builder names, the default language included if it names one, is
     *                                  of a class no engine has prepared yet and too little stack is left to prepare
     *                                  it, checked before any language is prepared (see
     *                                  {@link io.github.brantunger.unruly.api.language.ExpressionLanguage#prepare()})
     */
    public static <O> RulesEngine<O> firstMatch(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        initialize();
        return new StatelessRulesEngine<>(outputFactory, configuration);
    }

    /**
     * Creates an engine that fires every matching rule in priority order.
     *
     * @param outputFactory Creates the output object
     * @param configuration The builder's settings
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are
     *                                  given for a language the engine doesn't have
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if an argument is {@code null}
     * @throws StackOverflowError       if this is the JVM's first engine and the thread has too little stack left to
     *                                  initialize the classes engines use, checked first, before any of them is
     *                                  touched and before the settings are (see {@link #initialize()}); or if a
     *                                  language the builder names, the default language included if it names one, is
     *                                  of a class no engine has prepared yet and too little stack is left to prepare
     *                                  it, checked before any language is prepared (see
     *                                  {@link io.github.brantunger.unruly.api.language.ExpressionLanguage#prepare()})
     */
    public static <O> RulesEngine<O> allMatches(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        initialize();
        return new StatefulRulesEngine<>(outputFactory, configuration);
    }

    /**
     * Creates an engine that fires the one matching rule, and fails a run in which more than one rule matches.
     *
     * @param outputFactory Creates the output object
     * @param configuration The builder's settings
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are
     *                                  given for a language the engine doesn't have
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if an argument is {@code null}
     * @throws StackOverflowError       if this is the JVM's first engine and the thread has too little stack left to
     *                                  initialize the classes engines use, checked first, before any of them is
     *                                  touched and before the settings are (see {@link #initialize()}); or if a
     *                                  language the builder names, the default language included if it names one, is
     *                                  of a class no engine has prepared yet and too little stack is left to prepare
     *                                  it, checked before any language is prepared (see
     *                                  {@link io.github.brantunger.unruly.api.language.ExpressionLanguage#prepare()})
     */
    public static <O> RulesEngine<O> uniqueMatch(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        initialize();
        return new UniqueMatchRulesEngine<>(outputFactory, configuration);
    }
}
