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

    // Written by a check that finds them different, so the recursion has an effect the JIT can't drop. Each is the
    // same every time, so the checks of other threads only read it.
    private static long sink;
    private static long giveBackSink;

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

    // Each frame adds four values once the call below it returns, so it keeps them on the stack across that call,
    // compiled or not: a compiled frame is then about three times the smallest a frame can be, which reserves more
    // stack for the time the check takes, and depends less on whether the JIT has compiled it.
    private static long descend(int frames, long a, long b, long c, long d) {
        return frames == 0 ? a : descend(frames - 1, b, c, d, a + 1) + a + b + c + d;
    }
}
