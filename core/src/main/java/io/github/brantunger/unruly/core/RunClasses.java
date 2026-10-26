package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputConverter;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.MessageText;
import io.github.brantunger.unruly.api.language.ImportLimits;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.Throwables;
import io.github.brantunger.unruly.internal.Causes;
import io.github.brantunger.unruly.internal.ImportSize;
import io.github.brantunger.unruly.internal.Lookups;
import io.github.brantunger.unruly.internal.Text;
import io.github.brantunger.unruly.internal.Widening;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The classes with a static initializer that building an engine, loading its rules, validating them, running them or
 * closing the engine would otherwise be the first to use, the engine's, the JDK's and SLF4J's, initialized when the
 * JVM's first engine is built, before the builder resolves its settings. Any of those may be called deep in another
 * run's stack, for example from an action, and the stack-headroom check (see StackHeadroom) makes room for the
 * engine's own steps, not for initializing classes. A {@link StackOverflowError} thrown inside a class's static
 * initializer leaves the class unusable for the life of the JVM: every later use of it, by any engine, throws
 * {@link NoClassDefFoundError}. Initializing them here, once {@link StackHeadroom#checkInitializing()} has made room
 * for it, leaves those calls nothing to initialize. So are the few of the engine's classes without one that a rule's
 * or a run's first failure would otherwise be the first to load, as a class's first load deep in a stack can overflow
 * too (#1066, #1093), and those that the JVM's first load and first run would otherwise read from the engine's jar or
 * class directory (#1210). So are the JDK's classes that a first read of a generic type or of a method's annotations,
 * or a first reflective call of a native method, initializes, in a language's load or run or a write through a generic
 * setter, which it reaches by doing each on the JDK's own methods (see {@link #readMembers}) (#1115). So are the JDK's
 * tables of the Unicode characters above U+00FF, one class a plane, which the first {@code Character} method called on
 * such a character builds, in a check of an import's name, a read of a fact's property or a rule's own code, and which
 * it reaches by reading the type of one character of each plane (#1286). In a native image, the classes it can only
 * name are left to the image, which can't look a name up that it has no metadata for, and no method is read. A
 * language's own classes are its to initialize, in {@link ExpressionLanguage#prepare()}, which
 * {@link #prepare(Collection)} calls with room made for it in the same way.
 *
 * <p>
 * Each is initialized as the JVM initializes a class, by running its static initializer and nothing else, so nothing
 * here depends on what a method of the class does, except for the few a method call initializes, which say why.
 * {@code FirstRunClassInitializationTest} checks the lists against the first builds, loads and runs of the engines its
 * scenario builds in a new JVM: map and bean outputs, declared facts, a fact of the wrong type and a missing one, a
 * rejected fact name, every listener callback, a failing condition, a write to read-only facts, nested runs that throw
 * a fatal error or pass their deadline, an action that throws an exception or an error, a setter and a listener that
 * throw, a failing load, a load with two failures, a write no setter accepts, a run that waits for a copy, one on a
 * virtual thread that looks for a build slot, a failed borrow of rules a load replaced, a rule with a validity window,
 * {@code validate()} and {@code close()}. It fails if those
 * initialize any class with a static initializer, the engine's, the JDK's or a library's, other than the hidden classes
 * the JDK makes for method handles, and if the first build initializes one before this class does, other than the
 * application's own, and if its first loads and its first run, a bean output's, load any class from a file but the
 * application's and its languages'. A path the scenario doesn't take may still initialize or load one,
 * and so does the first message the engine logs, with a provider that logs: with slf4j-simple, it initializes SLF4J's
 * {@code Level} and {@code FormattingTuple}, which aren't initialized here. Deep in a stack, that can overflow inside
 * the engine's own log call, which then loses the line, and the failure is still reported or rethrown (see
 * {@link Failures#logError}, #1208). This class has no static initializer of its
 * own, which an engine built deep in a stack could break, as the lists are built only once the room is checked. What a
 * builder's settings reject may still initialize classes before any check, maybe deep in a stack, though nothing a
 * builder accepts does: {@code language()} initializes {@link LanguageNames.Problem} when it rejects a language's name,
 * and {@code fact()} and {@code facts()} initialize {@link FactNames.Problem} when they reject a fact's name. The
 * message of a setting a builder rejects isn't the JVM's first use of the JDK's string concatenation, as the engine is
 * compiled to build its strings without it (#965).
 * </p>
 */
final class RunClasses {

    // The name of the method readMembers calls reflectively. A constant, so this class has no static initializer.
    private static final String HASH_CODE = "hashCode";

    // Set once every class has been initialized, so a later engine checks no room and initializes nothing. Set last,
    // under this class's lock, so an engine built meanwhile waits for it, and one that threw leaves it unset.
    private static volatile boolean initialized;

    private RunClasses() {
    }

    /**
     * Initializes the classes, unless an engine built before has. An engine built on another thread meanwhile waits
     * for this one to finish, then initializes nothing, unless this one threw. One that throws, as it does when the
     * stack is too shallow, leaves them to the next, which checks the room again. The lock is this class's own, so it
     * has no static initializer.
     *
     * @throws StackOverflowError if the thread has too little stack left to initialize them, before any is touched
     */
    static void initialize() {
        if (initialized) {
            return;
        }
        synchronized (RunClasses.class) {
            if (initialized) {
                return;
            }
            StackHeadroom.checkInitializing();
            // The session of a language that keeps no state, and the builder's default output writer: no lookup here
            // can reach either, as its package keeps it to itself, and naming one fails in a native image, so each is
            // initialized by getting it. So is the builder's default clock, the JDK's, which the builder reads only
            // now, with the instant a load records as its time.
            Session.none();
            OutputWriter.beansAndMaps();
            Clock.systemUTC().instant();
            // What a load and validate() do with the JDK's classes, which differ between JDK releases, so those are
            // initialized by doing the same: computing a checksum, which finds the digest in the JDK's security
            // settings, and sorting and filtering a rule list with streams, here two rules, one without a priority, and
            // a null.
            Checksums.ofRules(List.of());
            Rule rule = Rule.builder().ruleName("a").condition("a").action("a").build();
            RuleListCompiler.inPriorityOrder(RuleListCompiler.withoutNulls(Arrays.asList(rule, null,
                    rule.toBuilder().ruleName("b").priority(1).build())));
            // What a Character method does for a character above U+00FF, such as the engine's check of an import's
            // name, a converter's or a language's read of a name, or Character.getType itself in a rule: it reads
            // the JDK's table for the character's plane, one class for each, which the JVM builds on its first use;
            // only the Latin-1 one is built at startup. So each is read here, by the type of one character of its
            // plane: the Basic Multilingual Plane (CharacterData00), planes 1 to 3 (01, 02, 03), the unassigned
            // planes 4 to 13 (Undefined), plane 14 (0E), and the private-use planes 15 and 16 (PrivateUse) (#1286).
            // The first reads the character from a string, as the JDK's code for a string with such a character,
            // StringUTF16, is initialized by the first read of a character of one, and nothing at startup does that
            // on Linux or macOS; a JVM on Windows, and the read of the JDK's security settings above, initialize it
            // before this, so this reads one in case.
            Character.getType("\u03c0".codePointAt(0));
            Character.getType(0x10000);
            Character.getType(0x20000);
            Character.getType(0x30000);
            Character.getType(0x40000);
            Character.getType(0xE0001);
            Character.getType(0xF0000);
            initialize(MethodHandles.lookup(), engineClasses(), namedClasses());
            initialize(MethodHandles.lookup(), loadAndRunClasses(), loadAndRunNamedClasses());
            // What a language's first load or run, or a first write through a generic setter, may do with the JDK's
            // reflection, which differs between JDK releases, so that is done here on the JDK's own classes (#1115).
            readMembers(List.of(Object.class, Class.class, Math.class, String.class, Thread.class, System.class,
                    Iterable.class));
            // A rule list's first use of a language reads whether its class is prepared before it checks any room, as
            // it must not check on every load, so what the first read of a class does is done here: creating the flag,
            // whose class's static initializer sets up the JDK's field access.
            Prepared.CLASSES.get(Prepared.class);
            initialized = true;
        }
    }

    /**
     * Prepares the languages an engine is built to use, as {@link ExpressionLanguage#prepare()} describes, on every
     * build: those the builder named, its default language if named (see CoreRulesEngine). If one is of a class no
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
        // built with strict reachability metadata, with an error (see Lookups.isMissingRegistration).
        // Skipping the lookups, rather than telling that error apart, also leaves CI's strict image build a check that
        // none is made.
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
     * Reads the generic return and parameter types and the annotations of the public methods of {@code types}, and
     * the class or method that declares each of those types that is a type variable, then calls
     * {@link Object#hashCode()} reflectively. A JVM's first read of a generic type initializes the JDK's classes that
     * parse and stand for one, its first read of a method's annotations initializes the proxy class of each
     * annotation type it finds, on JDK 26 its first {@link TypeVariable#getGenericDeclaration()} links the JDK's
     * pattern switch in it, and on JDK 21 its first reflective call of a native method initializes the JDK's accessor
     * for one. A language's first load or run may do any of these: MVEL's analysis reads the generic return type of a
     * method a rule calls, and its run calls the method reflectively, which reads the method's annotations to tell
     * whether it's caller-sensitive. So may the default output writer's first write through a generic setter's bridge,
     * which reads the setter's generic parameter type and its type variable's declaration. Deep in a stack, any of
     * them could overflow in a static initializer of the JDK's, and leave that class unusable for the life of the JVM,
     * in the application's own code too. Done here, they find those classes initialized, for the shapes of generic
     * type and the annotation types the methods of {@code types} have: a shape or an annotation type they don't have
     * is still left to its first use. In a native image nothing is read or called, as nothing is named: which
     * classes' methods an image can reflect on is its application's metadata to decide.
     *
     * @param types The classes whose public methods are read
     */
    static void readMembers(List<Class<?>> types) {
        if (inNativeImage()) {
            return;
        }
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                // Read for what reading them initializes: an array, never null.
                Objects.requireNonNull(method.getDeclaredAnnotations());
                readDeclaration(method.getGenericReturnType());
                for (Type parameter : method.getGenericParameterTypes()) {
                    readDeclaration(parameter);
                }
            }
        }
        // Found among the public methods, which hold it, rather than looked up by name, which could fail.
        for (Method method : Object.class.getMethods()) {
            if (HASH_CODE.equals(method.getName())) {
                invoke(method, new Object());
            }
        }
    }

    /**
     * Calls a method with no arguments reflectively, on {@code target}, and ignores what it returns or throws.
     *
     * @param method The method
     * @param target The object to call it on
     */
    static void invoke(Method method, Object target) {
        try {
            method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            // Called all the same: what it threw is no part of the call's first use.
        }
    }

    // Reads the class or method that declares a type that is a type variable, which is never null.
    private static void readDeclaration(Type type) {
        if (type instanceof TypeVariable<?> variable) {
            Objects.requireNonNull(variable.getGenericDeclaration());
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

    // Initialized through a lookup in this package, which can reach each of them. CoreRulesEngine creates the engine's
    // logger, which starts SLF4J and its provider if the application hasn't, and Failures.Log, beside the holder of
    // EngineCompileContext's logger, creates Failures' own logger, kept out of Failures because Throwables reaches
    // Failures before any engine is built (#1260). FlightRecorderEvents initializes RunEvent and RuleEvent, which
    // register the engine's Flight Recorder events, where Flight Recorder is there, and decides whether they can be
    // used. They aren't named, as naming a class loads it, and they extend a class that may not be there. OneStepStack
    // makes the field updater that every change to a rule set's idle copies and open compilers goes through, which a
    // load would otherwise make (#1220). The thirteen before the last twelve have no static initializer: they are what
    // a rule's or a run's first failure would otherwise be the first to load, which can overflow deep in a stack as
    // initializing a class can (#1066, #1093), so they are loaded here. So are the next five, which the JVM's first
    // wait for a copy and its first failed load, in any language, would otherwise be the first to load or initialize
    // (#1097). The last seven are what the api and core packages share and what api.language publishes of it: Text has
    // the table of the characters it escapes, and a failure's description is the first to read it; the rest have no
    // static initializer, and a language's or the kit's first failure, read through Throwables, ImportLimits or
    // FatalErrors, would otherwise be the first to load them (#1241). MatchPolicy, after the engine, makes the three
    // match policies, which Engines names only once this has run.
    private static List<Class<?>> engineClasses() {
        return List.of(CoreRulesEngine.class, MatchPolicy.class, LanguageRegistry.class, ImportResolver.class,
                LanguageNames.Problem.class, FactNames.Problem.class, RuleListCompiler.Mode.class,
                EngineCompileContext.Warnings.class, Failures.Log.class, Prepared.class, Ran.class,
                LoggedFailures.class, Faults.Step.class, Failures.class, ExpressionKind.class,
                InvalidExpressionException.Issue.Severity.class, CopyPool.class, OneStepStack.class,
                FlightRecorderEvents.class, Cancellation.class, Cancellation.Reason.class, Deadline.class,
                CopyPool.Kind.class, CopyPool.Held.class, Closing.class,
                Widening.class, LoggedFailures.LoggedAt.class, RunOptions.class, RuleEvaluation.Outcome.class,
                ConditionResult.class, ActionResult.class, FactProperties.class, RunScope.class,
                CancelTimer.class, RuleExecutionException.class, ReportedFailure.class, LoggedFailures.Logged.class,
                LoggedFailures.Outermost.class, ListenerNotifier.OnError.class, ListenerNotifier.OnRunError.class,
                CoreRulesEngine.BeforeRun.class, NestedFailures.class, NestedFailures.Below.class,
                Failures.Reach.class, Failures.Deeper.class, ListenerNotifier.ListenerFatal.class, IsoInstant.class,
                CopyPermits.Returned.class,
                RuleCompilationException.class, InvalidExpressionException.class,
                InvalidExpressionException.Issue.class, MessageText.class,
                Text.class, Causes.class, Lookups.class, ImportSize.class, Throwables.class, ImportLimits.class,
                FatalErrors.class);
    }

    // Loaded through the same lookup: the engine's classes, none with a static initializer, that the JVM's first load
    // and first run would otherwise be the first to load, from the engine's jar or class directory. The first eleven,
    // the load's, may come after load()'s own check of the room: CompiledCondition and CompiledAction with the first
    // class of a language's that implements one, unless the language's prepare() or the application loaded it before,
    // OutputConverter and LanguageOutputConverter as each rule's converter is made (#1127), and LoadedRules and its
    // LanguagesRan once the rules have compiled. No test catches the two compiled kinds, as no scenario's first load
    // is the first to read them. Of the run's,
    // RunBody, CopyPool's Copy, RunFacts and RunResult are loaded when the run links the lambda it runs its rules in,
    // after run()'s own check of the room and before the check for a language's first run, and the rest after that;
    // Accessors is what the default output writer's first write to a bean loads. Deep in a stack, once the JIT has
    // compiled the checks, a class's first load could overflow there, and on JDK 21 a run on a virtual thread that was
    // the JVM's first to read a file could leave every later run of every engine, and every file read on a virtual
    // thread, failing for the life of the JVM (#1210). Failures' Teardown is what every run makes as it ends, before it
    // closes its values, and LoggedFailures' Ring is what the first failure a run reports, or a nested run logs,
    // makes. Last, OneStepStack's Node, which a load makes for its compilers, and Walk, which the JVM's
    // first retire() makes to read the copies an earlier call took, are preloaded too (#1220). And
    // EngineCompileContext, which a load's first compile context would otherwise be the first to load when no fact is
    // declared, since the build checks a declared fact with it only when there is one.
    // Initializing a class doesn't load its nested or outer classes, so CopyPool's and RuleEvaluation.Outcome's, in the
    // list above, don't load these.
    private static List<Class<?>> loadAndRunClasses() {
        return List.of(Faults.class, LoggedFailures.Runs.class, RuleListCompiler.Compilation.class,
                LanguageCompilers.class, Expression.class, CompiledCondition.class, CompiledAction.class,
                OutputConverter.class, LanguageOutputConverter.class, LoadedRules.class, LanguagesRan.class,
                RuleExecutor.RunBody.class, CopyPool.Copy.class,
                RunFacts.class, RunResult.class, RuleSelection.class, RunState.class, ReadOnlyFacts.class,
                EngineEvaluationContext.class, CopyPool.Loan.class, EngineRunContext.class, RuleListener.class,
                RuleEvaluation.class, RuleExecutor.Matches.class, EngineActionContext.class, Accessors.class,
                Failures.Teardown.class, LoggedFailures.Ring.class, OneStepStack.Node.class, OneStepStack.Walk.class,
                EngineCompileContext.class);
    }

    // Loaded by name, as this package can't name them: the default output writer's classes that its first write to a
    // bean would otherwise be the first to load, as loadAndRunClasses says (#1210). In a native image, which loads no
    // class from a file, none is named.
    private static List<String> loadAndRunNamedClasses() {
        return List.of("io.github.brantunger.unruly.api.BeansAndMapsWriter$Lookup",
                "io.github.brantunger.unruly.api.BeansAndMapsWriter$Setter");
    }

    // Initialized by name, as this package can't name them: the JDK's that a load or a run may be the first to use.
    // Those are the streams' that a load uses, to sort its rules and to read their tags for its checksum (a failure's
    // description uses none, #1093, nor does saying which rules failed to compile, #1097), and MatchOps' kinds, which a
    // language's first load may be the first to use, as MVEL's does to check a name it looks up as a class's
    // (ExactNameClassLoader.ClassLookup.isBinaryName and ExactNameClassLoader.inNestedLookup), ClassValue's, which
    // caches an output's setters and a fact's properties, and those of the method handles that call them and convert a
    // widened value, which differ between JDK releases: 21's, then 25's and later ones'. Then the method handle a
    // load's first method reference to a compiler's method makes. None is the JDK's string concatenation's: the engine
    // is compiled to build its strings without it (#965). A class that isn't there, as in a JDK release that has none
    // of the name, is skipped, and in a native image none is named. The method handles' hidden classes, which the JDK
    // makes when they are first needed, can't be named. Last, ExceptionInInitializerError, which no load or run uses,
    // but which HotSpot creates to record that a class's static initializer failed: a load whose first initialization
    // of a hidden class overflowed deep in a stack overflowed in its static initializer too, which left it unusable for
    // the life of the JVM, so that every class's static initializer that throws, anywhere in the JVM, was reported as
    // NoClassDefFoundError: Could not initialize class java.lang.ExceptionInInitializerError, without its cause
    // (#1010). And WrongMethodTypeException, which an output's setter that throws is the first to load (#1093). And
    // the JDK's lock classes that a run's wait for a copy or for a build slot uses, in a semaphore: on JDK 25 and
    // later, the JVM's first wait in a lock is the first to load the nodes of its queue, Node and the two kinds a wait
    // makes, and to initialize Node and LockSupport, which have static initializers. JDK 21 initializes Node,
    // ExclusiveNode and LockSupport as it starts, but no release loads SharedNode before a wait that shares. So a run
    // that was the JVM's first to wait, deep in a stack, could leave every wait in a lock, anywhere in the JVM,
    // failing (#1112). Last, Blocker$ForkJoinPools, which JDK 21 and 22 have and JDK 23 and later don't: on JDK 21 the
    // JVM's first blocking file read on a virtual thread initializes it, and the engine's lookup of a fact's name as a
    // class's, which looks for its class file, or a language's first load of a class of its own, such as MVEL's, can be
    // that read, deep in a stack. Its static initializer overflowing there left every later file read on a virtual
    // thread, anywhere in the JVM, and so every later run, failing (#1210). Initializing it here initializes the JDK's
    // ForkJoinPool too, and about 12 other classes of the JDK's, when the first engine is built, so a
    // java.util.concurrent.ForkJoinPool.common property the application sets after that has no effect.
    private static List<String> namedClasses() {
        return List.of("java.util.stream.MatchOps$MatchKind", "java.util.stream.Collectors",
                "java.util.stream.Collector$Characteristics", "java.lang.ClassValue$ClassValueMap",
                "java.lang.invoke.MethodHandleImpl$BindCaller", "java.lang.invoke.MethodHandleImpl$ArrayAccess",
                "java.lang.invoke.MethodHandleImpl$ArrayAccessor", "java.util.Collections$CopiesList",
                "sun.invoke.util.ValueConversions$1", "java.lang.invoke.ClassSpecializer$Factory$1Var",
                "java.lang.ClassValue$RemovalToken", "java.lang.ClassValue$Entry", "java.lang.invoke.MethodHandles$1",
                "java.lang.invoke.ClassSpecializer$Factory$1$1Var", "java.lang.invoke.ClassSpecializer$Factory$1$5$1",
                "java.lang.invoke.DirectMethodHandle$Interface", "java.lang.ExceptionInInitializerError",
                "java.lang.invoke.WrongMethodTypeException",
                "java.util.concurrent.locks.AbstractQueuedSynchronizer$Node",
                "java.util.concurrent.locks.AbstractQueuedSynchronizer$ExclusiveNode",
                "java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode",
                "java.util.concurrent.locks.LockSupport", "jdk.internal.misc.Blocker$ForkJoinPools");
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

    /**
     * Whether a run has returned normally after running an action of a rule in a language of each class, which tells
     * a run whether it needs the room for a language's first run (see {@link LanguagesRan#needsRoom()}). A class
     * value, so it keeps no class from being unloaded. In a class of its own, initialized with the engine's, as
     * {@link Prepared} is.
     */
    static final class Ran {

        static final ClassValue<AtomicBoolean> CLASSES = new ClassValue<>() {
            @Override
            protected AtomicBoolean computeValue(Class<?> type) {
                return new AtomicBoolean();
            }
        };

        private Ran() {
        }
    }
}
