package io.github.brantunger.unruly.core;

/**
 * Checks that the caller's stack has room for what {@code run()}, {@code load()}, {@code validate()} and
 * {@code close()} count, borrow and give back, before any of them has taken anything. Each takes engine-wide state in
 * steps that assume they finish: a copy permit, the rule list's count of runs using it, the thread's deadline and its
 * counts of runs in progress, the engine's current run. A {@link StackOverflowError} that strikes one of those steps
 * on the way out, because the caller called in near the end of its own stack, would leave the state taken for good.
 * The check makes the call overflow here instead, where nothing has been taken, and the error reaches the caller as
 * it would have.
 *
 * <p>
 * It is best effort: it makes room for the engine's own steps, not for a language's {@code newSession()} or
 * {@code close()}, or a listener, called while a step is under way, which can use any amount of stack; nor for a
 * rule's expressions, but for the more a language's first runs may need (see {@link #FIRST_RUN_FRAMES}).
 * </p>
 *
 * <p>
 * Nor does it make room for the JVM to initialize the classes the steps use: registering the engine's Flight Recorder
 * events alone takes several times the room it makes. A {@link StackOverflowError} inside a class's static
 * initializer leaves the class unusable for the life of the JVM, for every engine. That's why the classes with a
 * static initializer that a run, a load, {@code validate()} or {@code close()} uses, or a build itself, every one of
 * the engine's, SLF4J's and those of the JDK that {@link RunClasses} names or reaches, are initialized when the JVM's
 * first engine is built, after {@link #checkInitializing()} has made room for them, before anything else the build
 * does, not by the first call that uses them; and a language's own by its {@code prepare()}, after the same check,
 * when an engine built to use it is built, or else when a rule list first uses it.
 * {@code FirstRunClassInitializationTest} fails if the first loads and runs of its scenario, which takes the paths
 * that {@code RunClasses} lists, initialize any class with a static initializer other than the JDK's hidden ones, or
 * if its first build initializes any but the application's own before {@code RunClasses}; a path it doesn't take may
 * still initialize one. An overflow while a class is only loaded or linked, or while a lambda is set up, isn't
 * remembered: its next use tries again. On JDK 25 and later, one while a string concatenation of several values is
 * first linked is: that concatenation fails for good. So the engine is compiled to build its strings without the JDK's
 * string concatenation (#965). The hidden classes the JDK makes for method handles when they are first needed can't be
 * named, so a run may still initialize those. One whose initialization overflows is left unusable, and HotSpot records
 * that with an {@link ExceptionInInitializerError}, whose own static initializer would overflow too and leave it
 * unusable for every class in the JVM, so {@code RunClasses} initializes that class as well (#1010).
 * </p>
 */
final class StackHeadroom {

    /**
     * How many frames the check recurses: four times as many as the fewest that left nothing behind when measured.
     * {@code StackEndSweepTest} calls each checked method at every one of the last depths of a stack and fails if
     * it leaves anything behind; with the check recursing 30 frames it failed, and with 40 it passed, on JDK 21 and
     * 26, compiled by both JIT tiers, by the first alone, and with the engine's own classes kept interpreted while
     * this one was compiled, which is the case that needs most: a cold handler's interpreted frames are several times
     * the size of this one's compiled frames. A frame here takes about 50 bytes compiled and 170 interpreted on x64,
     * so the check reserves about 8 KB, and takes a few hundred nanoseconds.
     */
    static final int FRAMES = 160;

    /**
     * How many frames {@link #checkFirstRun()} recurses: room for a run whose rule list uses a language that hasn't
     * finished a run in this JVM yet (see {@link RuleSet#firstRun()}). A rule's first call of a method makes the JDK
     * build the method's reflective accessor, which for a signature of a shape no call has had yet generates classes,
     * and the language may load classes of its own for it. Once the JIT's last tier has compiled the recursion every
     * check shares, its frames are a third of the size, and {@link #check()} lets such a run through where the rule
     * then overflows (#1066). Sized from a sweep with the check compiled, on JDK 21, 25 and 26 on x64, each depth in
     * a new JVM, of a first run of a rule, in the language #1066 measured, that calls one method: with this check at
     * {@link #FRAMES}, the argument shapes measured needed up to 104 frames more. Measured again with this check and
     * that language's {@code prepare()} also calling a method with a literal argument, 181 depths for each shape on
     * each JDK: no such run failed its rule, for a call of one method of any shape measured, one with a constructor
     * call among its arguments or one with another call among them, and none overflowed in the engine's own steps; a
     * call with three calls nested in its arguments still failed its rule at 11 to 14 of the 181 depths, in the JDK's
     * reflective accessor. No reserve covers every shape.
     */
    static final int FIRST_RUN_FRAMES = 270;

    /**
     * How many frames {@link #checkGiveBack()} recurses: room for giving back a permit or a build slot, which takes
     * about six frames below the caller's, in the JDK's semaphore and the wake-up of a waiting thread, each at most a
     * few hundred bytes interpreted, twice over when this is compiled. With it, a give-back that fails does so before
     * it has given anything back, so it can be tried again without giving back twice.
     */
    static final int GIVE_BACK_FRAMES = 32;

    /**
     * How many frames {@link #checkInitializing()} recurses: room for initializing the classes {@link RunClasses}
     * names or reaches, SLF4J's and the JDK's security settings among them, or the classes a language's
     * {@code prepare()} initializes, of which MVEL's take most. Four times as many as the fewest that left nothing
     * behind when measured. It was measured with SLF4J not yet started and the JVM's first build preparing MVEL along
     * with the engine's classes, as a build whose builder names MVEL does: made from each of the last 900 depths of a
     * 512 KB stack, each in a new JVM, a check of 220 frames left MVEL's {@code FactNames} unusable, at 6 depths on
     * JDK 21 and 2 on JDK 25, and 240 left nothing, on both, and twice on JDK 21; the engine's own classes were left
     * unusable by no check, even of 80 frames. The JVM's first build runs it while it is still interpreted, at about
     * 170 bytes a frame on x64, once for the engine's classes and, if it prepares a language, again for that: it
     * reserves about 160 KB, which the JVM's first engine needs left on the stack it's built on. A later build, or a
     * load or {@code validate()} that first uses a language, as a load does with MVEL found with
     * {@link java.util.ServiceLoader} and not named, that prepares a language of a class no engine has prepared yet
     * runs it again, compiled if runs have made it hot, at about 50 bytes a frame, so it reserves about 48 KB:
     * measured with it compiled at the first build, 640 frames left {@code FactNames} unusable at 29 of 1,500 depths,
     * and 800 left nothing, so compiled it has a fifth more than the least, not four times. A first use wasn't swept
     * on its own: it prepares the language alone, without the engine's classes the measured build initialized too.
     * The reserve was measured for the classes a language's {@code prepare()} initializes, not for a warm-up after them
     * that, when the engine prepares the language, at {@code build()} or a first load, initializes no class with a
     * static initializer that it hasn't, as MVEL's evaluation of a property read and two method calls, one with a
     * literal argument, does: that may still overflow, and the MVEL language's {@code prepare()} catches the overflow
     * and skips the warm-up. A bare {@code prepare()} call in an otherwise empty JVM may also initialize JDK classes
     * the reflective call needs.
     */
    static final int INITIALIZING_FRAMES = 960;

    // Written by a check that finds them different, so the recursion has an effect the JIT can't drop. Each is the
    // same every time, so the checks of other threads only read it.
    private static long sink;
    private static long firstRunSink;
    private static long giveBackSink;
    // Written by each check that makes room for initializing classes, the engine's or a language's, for the same
    // reason, and read by nothing.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private static long initializingSink;

    private StackHeadroom() {
    }

    /**
     * Recurses {@link #FRAMES} frames.
     *
     * @throws StackOverflowError if the stack can't hold them
     */
    static void check() {
        long sum = descend(FRAMES, 1, 2, 3, 4);
        if (sink != sum) {
            sink = sum;
        }
    }

    /**
     * Recurses {@link #FIRST_RUN_FRAMES} frames.
     *
     * @throws StackOverflowError if the stack can't hold them
     */
    static void checkFirstRun() {
        long sum = descend(FIRST_RUN_FRAMES, 1, 2, 3, 4);
        if (firstRunSink != sum) {
            firstRunSink = sum;
        }
    }

    /**
     * Recurses {@link #GIVE_BACK_FRAMES} frames.
     *
     * @throws StackOverflowError if the stack can't hold them
     */
    static void checkGiveBack() {
        long sum = descend(GIVE_BACK_FRAMES, 1, 2, 3, 4);
        if (giveBackSink != sum) {
            giveBackSink = sum;
        }
    }

    /**
     * Recurses {@link #INITIALIZING_FRAMES} frames.
     *
     * @throws StackOverflowError if the stack can't hold them
     */
    static void checkInitializing() {
        initializingSink = descend(INITIALIZING_FRAMES, 1, 2, 3, 4);
    }

    // Each frame adds four values once the call below it returns, so it keeps them on the stack across that call,
    // compiled or not: a compiled frame is then about three times the smallest a frame can be, which reserves more
    // stack for the time the check takes, and depends less on whether the JIT has compiled it.
    private static long descend(int frames, long a, long b, long c, long d) {
        return frames == 0 ? a : descend(frames - 1, b, c, d, a + 1) + a + b + c + d;
    }
}
