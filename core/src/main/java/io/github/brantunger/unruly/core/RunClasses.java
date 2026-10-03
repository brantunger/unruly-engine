package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.Session;

import java.lang.invoke.MethodHandles;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The classes with a static initializer that building an engine, loading its rules, validating them, running them or
 * closing the engine would otherwise be the first to use, the engine's, the JDK's and SLF4J's, initialized when the
 * JVM's first engine is built, before the builder resolves its settings. Any of those may be called deep in another
 * run's stack, for example from an action, and the stack-headroom check (see StackHeadroom) makes room for the
 * engine's own steps, not for initializing classes. A {@link StackOverflowError} thrown inside a class's static
 * initializer leaves the class unusable for the life of the JVM: every later use of it, by any engine, throws
 * {@link NoClassDefFoundError}. Initializing them here, once {@link StackHeadroom#checkInitializing()} has made room
 * for it, leaves those calls nothing to initialize. In a native image, the classes it can only name are left to the
 * image, which can't look a name up that it has no metadata for. A language's own classes are its to initialize, in
 * {@link ExpressionLanguage#prepare()}, which {@link #prepare(Collection)} calls with room made for it in the same way.
 *
 * <p>
 * Each is initialized as the JVM initializes a class, by running its static initializer and nothing else, so nothing
 * here depends on what a method of the class does, except for the few a method call initializes, which say why.
 * {@code FirstRunClassInitializationTest} checks the lists against the first builds, loads and runs of the engines its
 * scenario builds in a new JVM: map and bean outputs, declared facts, a fact of the wrong type and a missing one, a
 * rejected fact name, every listener callback, a failing condition, a write to read-only facts, nested runs that
 * throw a fatal error or pass their deadline, a failing load, a rule with a validity window, {@code validate()} and
 * {@code close()}. It fails if those initialize any class with a static initializer, the engine's, the JDK's or a
 * library's, other than the hidden classes the JDK makes for method handles, and if the first build initializes one
 * before this class does, other than the application's own. A path the scenario doesn't take may still initialize
 * one. This class has no static initializer of its own, which an engine built deep in a stack could break, as the
 * lists are built only once the room is checked. What a builder's settings reject may still initialize classes before
 * any check, maybe deep in a stack, though nothing a builder accepts does: {@code language()} initializes
 * {@link LanguageNames.Problem} when it rejects a language's name, {@code fact()} and {@code facts()} initialize
 * {@link FactNames.Problem} when they reject a fact's name, and the message of a setting a builder rejects may be
 * the JVM's first use of the JDK's string concatenation.
 * </p>
 */
final class RunClasses {

    // Set once every class has been initialized, so a later engine checks no room and initializes nothing.
    private static volatile boolean initialized;

    private RunClasses() {
    }

    /**
     * Initializes the classes, unless an engine built before has.
     *
     * @throws StackOverflowError if the thread has too little stack left to initialize them, before any is touched
     */
    static void initialize() {
        if (initialized) {
            return;
        }
        StackHeadroom.checkInitializing();
        // The session of a language that keeps no state, and the builder's default output writer: no lookup here can
        // reach either, as its package keeps it to itself, and naming one fails in a native image, so each is
        // initialized by getting it. So is the builder's default clock, the JDK's, which the builder reads only now,
        // with the instant a load records as its time.
        Session.none();
        OutputWriter.beansAndMaps();
        Clock.systemUTC().instant();
        // What a load and validate() do with the JDK's classes, which differ between JDK releases, so those are
        // initialized by doing the same: computing a checksum, which finds the digest in the JDK's security settings,
        // and sorting and filtering a rule list with streams, here two rules, one without a priority, and a null.
        Checksums.ofRules(List.of());
        Rule rule = Rule.builder().ruleName("a").condition("a").action("a").build();
        RuleListCompiler.inPriorityOrder(RuleListCompiler.withoutNulls(Arrays.asList(rule, null,
                rule.toBuilder().ruleName("b").priority(1).build())));
        initialize(MethodHandles.lookup(), engineClasses(), namedClasses());
        // A rule list's first use of a language reads whether its class is prepared before it checks any room, as it
        // must not check on every load, so what the first read of a class does is done here: creating the flag, whose
        // class's static initializer sets up the JDK's field access.
        Prepared.CLASSES.get(Prepared.class);
        initialized = true;
    }

    /**
     * Prepares the languages an engine is built to use, as {@link ExpressionLanguage#prepare()} describes, on every
     * build: those the builder named, its default language if named (see AbstractRulesEngine). If one is of a class no
     * engine has prepared a language of without a throw, the room for initializing classes is checked first, as the
     * JVM's first engine checks it for the engine's own, so a language first used by a later engine is covered too.
     * The engine's other languages are prepared when a rule list first uses them, by {@link #firstUse} and
     * {@link #prepare(ExpressionLanguage)}.
     *
     * <p>
     * A language is known by its class: a language of a class prepared before, such as a wrapper prepared with
     * another language inside it, has the room for it checked no more, though it is still prepared on every build.
     * </p>
     *
     * @param languages The languages to prepare
     * @throws StackOverflowError if a language is of a class not prepared yet, and the thread has too little stack left
     *                            to prepare it, before any is prepared
     */
    static void prepare(Collection<ExpressionLanguage> languages) {
        for (ExpressionLanguage language : languages) {
            if (!Prepared.CLASSES.get(language.getClass()).get()) {
                StackHeadroom.checkInitializing();
                break;
            }
        }
        // Anything prepare() throws fails the build unchanged, and leaves the class to be checked and prepared again.
        for (ExpressionLanguage language : languages) {
            prepare(language);
        }
    }

    /**
     * Tells whether a rule list's first use of a language must prepare it, as no engine has prepared a language of
     * its class, and if so checks the room for that first, as {@link #prepare(Collection)} does at build.
     *
     * @param language The language a rule list is about to create its compiler with
     * @return {@code true} if the caller must call {@link #prepare(ExpressionLanguage)} before it creates the compiler
     * @throws StackOverflowError if the language must be prepared and the thread has too little stack left for it
     */
    static boolean firstUse(ExpressionLanguage language) {
        if (Prepared.CLASSES.get(language.getClass()).get()) {
            return false;
        }
        StackHeadroom.checkInitializing();
        return true;
    }

    /**
     * Prepares a language, and records its class as prepared once {@link ExpressionLanguage#prepare()} returns.
     *
     * @param language The language
     */
    static void prepare(ExpressionLanguage language) {
        language.prepare();
        Prepared.CLASSES.get(language.getClass()).set(true);
    }

    /**
     * Initializes {@code classes} through {@code lookup}, or by name where it can't reach one, and the classes
     * {@code named}, skipping one that isn't there. In a native image, nothing is initialized by name: neither a class
     * the lookup can't reach nor any of those named.
     *
     * @param lookup  The lookup to initialize the classes through
     * @param classes The classes to initialize through it
     * @param named   The names of the classes to initialize by name
     */
    static void initialize(MethodHandles.Lookup lookup, List<Class<?>> classes, List<String> named) {
        // In a native image nothing is looked up by name: which names an image has is its application's metadata to
        // decide, not this library's, and a lookup of one it lacks fails, with ClassNotFoundException or, in an image
        // built with strict reachability metadata, with an error (see ImportResolver.isMissingRegistration). Skipping
        // the lookups, rather than telling that error apart, also leaves CI's strict image build a check that none
        // is made.
        boolean image = inNativeImage();
        for (Class<?> type : classes) {
            try {
                lookup.ensureInitialized(type);
            } catch (IllegalAccessException e) {
                if (!image) {
                    initialize(type.getName());
                }
            }
        }
        if (image) {
            return;
        }
        for (String name : named) {
            initialize(name);
        }
    }

    /**
     * Tells whether this call is running in a native image, as GraalVM's own {@code ImageInfo.inImageRuntimeCode}
     * does, without a dependency on its SDK. The property is {@code "runtime"} only in an image, and
     * {@code "buildtime"} while one is being built, so the value is compared and not merely tested for. It is read on
     * every call and never into a field: a field an image's build filled in would answer {@code "buildtime"} for the
     * image's whole life.
     */
    private static boolean inNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    private static void initialize(String name) {
        try {
            Class.forName(name, true, ImportResolver.LIBRARY_CLASS_LOADER);
        } catch (ClassNotFoundException ignored) {
            // Not there, so no run can initialize it either.
        }
    }

    // Initialized through a lookup in this package, which can reach each of them. AbstractRulesEngine creates the
    // engine's logger, which starts SLF4J and its provider if the application hasn't. FlightRecorderEvents initializes
    // RunEvent and RuleEvent, which register the engine's Flight Recorder events, where Flight Recorder is there, and
    // decides whether they can be used. They aren't named, as naming a class loads it, and they extend a class that
    // may not be there.
    private static List<Class<?>> engineClasses() {
        return List.of(AbstractRulesEngine.class, LanguageRegistry.class, ImportResolver.class,
                LanguageNames.Problem.class, FactNames.Problem.class, RuleListCompiler.Mode.class,
                EngineCompileContext.Warnings.class, Prepared.class, LoggedFailures.class, Faults.Step.class,
                Failures.class, ExpressionKind.class, InvalidExpressionException.Issue.Severity.class, RuleSet.class,
                FlightRecorderEvents.class, Cancellation.class, Cancellation.Reason.class, Deadline.class,
                RuleSet.Kind.class, RuleSet.Held.class, RuleSet.Source.class, RuleSet.Warning.class, Closing.class,
                Widening.class, LoggedFailures.LoggedAt.class, RunOptions.class, RuleEvaluation.Outcome.class,
                ConditionResult.class, ActionResult.class, FactProperties.class);
    }

    // Initialized by name, as this package can't name them: the JDK's that a load or a run may be the first to use.
    // Those are the streams' that describe a failure (Failures builds some messages with streams), ClassValue's, which
    // caches an output's setters and a fact's properties, and those of the method handles that call them and convert a
    // widened value, which differ between JDK releases: 21's, then 25's and later ones'. Then the method handle a
    // load's first method reference to a compiler's method makes, and, in 25 and later releases, the strategy that
    // links a string concatenation of several values, as a load's first failure message is. A class that isn't there,
    // as in a JDK release that has none of the name, is skipped, and in a native image none is named. The method
    // handles' hidden classes, which the JDK makes when they are first needed, can't be named.
    private static List<String> namedClasses() {
        return List.of("java.util.stream.MatchOps$MatchKind", "java.util.stream.Collectors",
                "java.util.stream.Collector$Characteristics", "java.lang.ClassValue$ClassValueMap",
                "java.lang.invoke.MethodHandleImpl$BindCaller", "java.lang.invoke.MethodHandleImpl$ArrayAccess",
                "java.lang.invoke.MethodHandleImpl$ArrayAccessor", "java.util.Collections$CopiesList",
                "sun.invoke.util.ValueConversions$1", "java.lang.invoke.ClassSpecializer$Factory$1Var",
                "java.lang.ClassValue$RemovalToken", "java.lang.ClassValue$Entry", "java.lang.invoke.MethodHandles$1",
                "java.lang.invoke.ClassSpecializer$Factory$1$1Var", "java.lang.invoke.ClassSpecializer$Factory$1$5$1",
                "java.lang.invoke.DirectMethodHandle$Interface",
                "java.lang.invoke.StringConcatFactory$InlineHiddenClassStrategy");
    }

    /**
     * Whether a language of each class has been prepared: whether its {@link ExpressionLanguage#prepare()} has returned
     * without a throw, called by an engine's build or by a rule list's first use of it. A class value, so it keeps no
     * class from being unloaded. In a class of its own, initialized with the engine's, so that this class has no static
     * initializer.
     */
    static final class Prepared {

        static final ClassValue<AtomicBoolean> CLASSES = new ClassValue<>() {
            @Override
            protected AtomicBoolean computeValue(Class<?> type) {
                return new AtomicBoolean();
            }
        };

        private Prepared() {
        }
    }
}
