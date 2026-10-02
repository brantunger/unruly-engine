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
 * {@code close()}, or a listener, called while a step is under way, which can use any amount of stack.
 * </p>
 *
 * <p>
 * Nor does it make room for the JVM to initialize the classes the steps use: registering the engine's Flight Recorder
 * events alone takes several times the room it makes. A {@link StackOverflowError} inside a class's static
 * initializer leaves the class unusable for the life of the JVM, for every engine. That's why the classes with a
 * static initializer that a run uses, every one of the engine's and those of the JDK that {@link RunClasses} names,
 * are initialized when the JVM's first engine is built, after {@link #checkInitializing()} has made room for them, not
 * by its first run. {@code FirstRunClassInitializationTest} fails if the first runs of its scenario, which takes the
 * paths that {@code RunClasses} lists, initialize any class with a static initializer other than the JDK's hidden
 * ones; a path it doesn't take may still initialize one. An overflow while a class is only loaded or linked, or
 * while a lambda is set up, isn't remembered: its next use tries again. The hidden classes the JDK makes for method
 * handles when they are first needed can't be named, so a run may still initialize those.
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
     * How many frames {@link #checkGiveBack()} recurses: room for giving back a permit or a build slot, which takes
     * about six frames below the caller's, in the JDK's semaphore and the wake-up of a waiting thread, each at most a
     * few hundred bytes interpreted, twice over when this is compiled. With it, a give-back that fails does so before
     * it has given anything back, so it can be tried again without giving back twice.
     */
    static final int GIVE_BACK_FRAMES = 32;

    /**
     * How many frames {@link #checkInitializing()} recurses: room for initializing the classes {@link RunClasses}
     * names, of which registering the Flight Recorder events takes most. Four times as many as the fewest that left
     * nothing behind when measured: with SLF4J started, as an engine's own logger starts it before this, and
     * {@code RunClasses} started from each of the last depths of a 512 KB stack, each in a new JVM on JDK 21, a check
     * of 120 frames left {@code FlightRecorderEvents} unusable and 160 left nothing. It runs once in a JVM, when the
     * first engine is built, before any other check has run, so interpreted, at about 170 bytes a frame on x64: it
     * reserves about 105 KB, which the JVM's first engine needs left on the stack it's built on.
     */
    static final int INITIALIZING_FRAMES = 640;

    // Written by a check that finds them different, so the recursion has an effect the JIT can't drop. Each is the
    // same every time, so the checks of other threads only read it.
    private static long sink;
    private static long giveBackSink;
    // Written by the one check of the JVM that makes room for initializing classes, for the same reason, and read by
    // nothing.
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
