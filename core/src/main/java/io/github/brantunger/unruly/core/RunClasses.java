package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.Session;

import java.lang.invoke.MethodHandles;
import java.util.List;

/**
 * The classes with a static initializer that a run would otherwise be the first to use, the engine's and the JDK's,
 * initialized when the JVM's first engine is built. A run may be nested deep in another run's stack, and the
 * stack-headroom check (see StackHeadroom) makes room for the engine's own steps, not for initializing classes. A
 * {@link StackOverflowError} thrown inside a class's static initializer leaves the class unusable for the life of the
 * JVM: every later use of it, by any engine, throws {@link NoClassDefFoundError}. Initializing them here, once
 * {@link StackHeadroom#checkInitializing()} has made room for it, leaves a run nothing to initialize. In a native
 * image, the classes it can only name are left to the image, which can't look a name up that it has no metadata for.
 *
 * <p>
 * Each is initialized as the JVM initializes a class, by running its static initializer and nothing else, so nothing
 * here depends on what a method of the class does. {@code FirstRunClassInitializationTest} checks the lists against
 * the first runs of the engines its scenario builds in a new JVM: map and bean outputs, declared facts, a fact of the
 * wrong type and a missing one, every listener callback, a failing condition, a write to read-only facts, and nested
 * runs that throw a fatal error or pass their deadline. It fails if those runs initialize any class with a static
 * initializer, the engine's, the JDK's or a library's, other than the hidden classes the JDK makes for method
 * handles. A path the scenario doesn't take may still initialize one. This class has no static initializer of its
 * own, which an engine built deep in a stack could break, as the lists are built only once the room is checked.
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
        // The session of a language that keeps no state: no lookup here can reach it, as its package keeps it to
        // itself, and naming it fails in a native image, so it is initialized by getting it.
        Session.none();
        initialize(MethodHandles.lookup(), engineClasses(), namedClasses());
        initialized = true;
    }

    /**
     * Initializes {@code classes} through {@code lookup}, or by name where it can't reach one, and the classes
     * {@code named}, skipping one that isn't there, and every one of those in a native image.
     *
     * @param lookup  The lookup to initialize the classes through
     * @param classes The classes to initialize through it
     * @param named   The names of the classes to initialize by name
     */
    static void initialize(MethodHandles.Lookup lookup, List<Class<?>> classes, List<String> named) {
        for (Class<?> type : classes) {
            try {
                lookup.ensureInitialized(type);
            } catch (IllegalAccessException e) {
                initialize(type.getName());
            }
        }
        // An image built with strict reachability metadata throws an error, not ClassNotFoundException, for a name it
        // has no metadata for, and catching that error would catch a StackOverflowError too.
        if (inNativeImage()) {
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

    // Initialized through a lookup in this package, which can reach each of them. FlightRecorderEvents initializes
    // RunEvent and RuleEvent, which register the engine's Flight Recorder events, where Flight Recorder is there, and
    // decides whether they can be used. They aren't named, as naming a class loads it, and they extend a class that
    // may not be there.
    private static List<Class<?>> engineClasses() {
        return List.of(FlightRecorderEvents.class, Cancellation.class, Cancellation.Reason.class, Deadline.class,
                RuleSet.Kind.class, RuleSet.Held.class, RuleSet.Source.class, RuleSet.Warning.class, Closing.class,
                Widening.class, LoggedFailures.LoggedAt.class, RunOptions.class, RuleEvaluation.Outcome.class,
                ConditionResult.class, ActionResult.class, FactProperties.class);
    }

    // Initialized by name, as this package can't name them: the JDK's that a run may be the first to use. Those are
    // the streams' that describe a failure (Failures builds some messages with streams), ClassValue's, which caches an
    // output's setters and a fact's properties, and those of the method handles that call them and convert a widened
    // value, which differ between JDK releases: 21's, then 25's and later ones'. A class that isn't there, as in a JDK
    // release that has none of the name, is skipped, and in a native image none is named. The method handles' hidden
    // classes, which the JDK makes when they are first needed, can't be named.
    private static List<String> namedClasses() {
        return List.of("java.util.stream.MatchOps$MatchKind", "java.util.stream.Collectors",
                "java.util.stream.Collector$Characteristics", "java.lang.ClassValue$ClassValueMap",
                "java.lang.invoke.MethodHandleImpl$BindCaller", "java.lang.invoke.MethodHandleImpl$ArrayAccess",
                "java.lang.invoke.MethodHandleImpl$ArrayAccessor", "java.util.Collections$CopiesList",
                "sun.invoke.util.ValueConversions$1", "java.lang.invoke.ClassSpecializer$Factory$1Var",
                "java.lang.ClassValue$RemovalToken", "java.lang.ClassValue$Entry", "java.lang.invoke.MethodHandles$1",
                "java.lang.invoke.ClassSpecializer$Factory$1$1Var", "java.lang.invoke.ClassSpecializer$Factory$1$5$1");
    }
}
