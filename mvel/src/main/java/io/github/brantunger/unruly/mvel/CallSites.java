package io.github.brantunger.unruly.mvel;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Counts MVEL's calls for the class loader in one analysis pass or one run by where they come from, each call site
 * told by a hash of the call's stack, and tells when one site has made more than a bound allows (#861). On each round
 * of the loops that never end (#840, #857), MVEL asks from the same place, with the same stack. A valid expression
 * that asks many times, such as a long chain in many levels of brackets, or calls nested hundreds deep, asks from many
 * places: about as many times from one as the parts of a chain, about one for each two characters.
 *
 * <p>
 * Walking the stack costs far more than the call, and more for each frame, so it is walked only once a pass or run
 * has asked {@value #UNCOUNTED_CALLS} times, which an ordinary expression never does, and then only for about one call
 * in {@value #SAMPLED_EVERY}: the gap to the next walk is drawn anew each time, from 1 to twice that less one, so calls
 * that come round in a fixed order aren't always walked at the same place in it. Each walk counts the calls since the
 * last walk, the walked one included, as made from the walked call's site. A valid expression's calls are counted
 * there on average, but not exactly: a site that makes half the calls of a long expression may be counted more than
 * it made. So once a site's count passes the bound, nothing is said yet: the counts start again, and the stack is
 * walked for every call from then on, each counted once at its own site, and only a site whose count passes the bound
 * then is a loop. Every call of a loop that never ends is from one site, so every walk counts its calls there, and it
 * is stopped after at most {@value #UNCOUNTED_CALLS} calls, plus the bound, plus one gap, plus the bound and one more.
 * </p>
 *
 * <p>
 * Counting every call stays on until the pass or run ends, even if no site passes the bound again (#938). A valid
 * expression that turned it on walks the stack for each of its later calls, about {@value #SAMPLED_EVERY} times as
 * many walks as before. No valid expression tried has turned it on early. A valid expression's busiest site makes
 * at most about one call for each two characters, and the bound allows 1,000 more, so the walks must count at least
 * about 1,000 calls too many there. The counts drawn from the walks stray that far only after some 50,000 calls or
 * more at one site (a 77,000-part chain did, at its last few calls; 40,000 parts never did), so they pass the bound,
 * if ever, near the end of a very long chain, and few calls are left to pay for.
 * </p>
 *
 * <p>
 * More than {@value #MAX_SITES} sites walked in one pass or run count as a loop too, which bounds what the count keeps
 * for a loop whose site keeps changing. The sites are counted afresh once every call is counted, as the counts drawn
 * from the walks are dropped then, so a pass or run may walk about twice as many in all, but never keeps more. Once
 * every call is counted, every call's site is walked, not about one in {@value #SAMPLED_EVERY}, so sites that make a
 * call or two each reach the limit up to about {@value #SAMPLED_EVERY} times sooner. A valid expression would have to
 * turn on counting every call, which only a very long chain has done, near its end, and then ask from more than
 * {@value #MAX_SITES} sites after that, before the pass or run ends.
 * </p>
 *
 * <p>
 * A walk takes more room on the stack than the engine's checks of the room make sure of, the same whatever the stack's
 * depth, and with too little it overflows in the JDK's code that walks it, usually as the walk starts, sometimes as it
 * fetches more frames (#1102); nothing is counted until the walk returns. Deep in a stack, where a walk of the stack
 * has no room, the walk is skipped and its calls count at no site: the walks after it
 * come where they would have, and the next that has room counts only the calls since the one skipped. So a valid
 * expression's calls are never counted as more than they were, and where no walk has room, no site passes the bound:
 * only the limit in all stops a loop then, with the loop's own error, after far more calls than the bound allows.
 * </p>
 */
final class CallSites {

    // How many calls a pass or run makes before the next ones are counted by where they come from.
    static final long UNCOUNTED_CALLS = 100;
    // How many calls, on average, each walk of the stack counts.
    static final int SAMPLED_EVERY = 16;
    // How many sites a pass or run may walk before it counts as a loop.
    static final int MAX_SITES = 50_000;
    // How many frames from the top of the stack tell a call site in a run, with the stack's depth. With the depth,
    // the most calls a valid expression made from one site hardly changed from 1 frame to 32 (113 to 111 for calls
    // nested 450 deep), and each loop at run time asked from one site whatever the frames. Without the depth, 4 were
    // the fewest that told apart the three places MVEL asks from in new java.lang.StringBuilder(java.lang.String.class
    // (2)), so 8 leave a margin: counting the depth costs the walk, and hashing a few frames more hardly does.
    static final int RUN_FRAMES = 8;

    // Reads each frame's class by its name, not as a class: a reference to it would need a permission a security
    // manager may refuse, on the JDKs that still have one. The name costs no more to read.
    private static final StackWalker WALKER = StackWalker.getInstance();
    // Counts the frames without reading their methods where the JVM allows it, from Java 22 (DROP_METHOD_INFO),
    // which halves what a deep stack costs to count. Java 21 reads each frame's method, at about 130 nanoseconds a
    // frame.
    private static final StackWalker COUNTER = StackWalker.getInstance(EnumSet.allOf(StackWalker.Option.class)
            .stream().filter(option -> "DROP_METHOD_INFO".equals(option.name())).collect(Collectors.toSet()));
    private static final long PRIME = 0x9E37_79B9_7F4A_7C15L;
    // Where the gaps between walks start, the same for every pass and run, so a count can be repeated.
    private static final long FIRST_GAP_STATE = 0x2545_F491_4F6C_DD1DL;
    // What tells the site of the call under way.
    private static final LongSupplier WHOLE_STACK = CallSites::hashOfStack;
    private static final LongSupplier TOP_AND_DEPTH = CallSites::hashOfTopAndDepth;
    // What the walks read the stack with: classes of their own, not lambdas or method references, which the JVM links
    // the first time each runs, and linking one makes a class. The first walk may be deep in a stack, where that could
    // overflow (#1099). Created here, so preparing MVEL, which initializes this class, loads them.
    private static final Function<Stream<StackWalker.StackFrame>, Long> WHOLE_STACK_HASH =
            new FramesHash(Long.MAX_VALUE);
    private static final Function<Stream<StackWalker.StackFrame>, Long> TOP_HASH = new FramesHash(RUN_FRAMES);
    private static final Function<Stream<StackWalker.StackFrame>, Long> DEPTH = new FrameCount();

    // Whether the whole stack tells a site, as in MVEL's analysis, or its top frames and its depth, as in a run.
    private final boolean wholeStack;
    // How many calls each site may be counted.
    private final long callsPerSite;
    // How many sites may be walked.
    private final int maxSites;
    // How many calls the pass or run has made.
    private long callsMade;
    // The call the stack was last walked for, or UNCOUNTED_CALLS before the first walk.
    private long lastWalk;
    // The call the stack is walked for next.
    private long nextWalk;
    // What draws the gaps between walks (xorshift).
    private long gapState;
    // Whether every call is walked and counted at its own site, once a count drawn from the walks has passed the bound.
    // It stays on until the pass ends or restart() starts a new run, whether or not a site passes the bound again.
    private boolean exact;
    // How many calls each site was counted, once the pass or run has made more than UNCOUNTED_CALLS, or null until
    // then: since every call has been counted, once it is, and from the walks until then.
    private Map<Long, long[]> callsBySite;

    /**
     * Creates the count for an analysis pass, or for each run of an expression.
     *
     * @param wholeStack   {@code true} to tell a site by the whole stack, as MVEL's analysis needs: there, what tells
     *                     the levels of brackets apart is at the stack's root. {@code false} to tell it by the
     *                     {@value #RUN_FRAMES} frames at its top and its depth, as a run's calls nested in one another
     *                     need, which costs less to read.
     * @param callsPerSite How many calls each site may be counted
     */
    CallSites(boolean wholeStack, long callsPerSite) {
        this(wholeStack, callsPerSite, MAX_SITES);
    }

    /**
     * Creates the count for an analysis pass, or for each run of an expression, that may walk only so many sites. Only
     * for tests: the engine allows {@value #MAX_SITES}.
     *
     * @param wholeStack   {@code true} to tell a site by the whole stack, {@code false} by its top and its depth
     * @param callsPerSite How many calls each site may be counted
     * @param maxSites     How many sites may be walked
     */
    CallSites(boolean wholeStack, long callsPerSite, int maxSites) {
        this.wholeStack = wholeStack;
        this.callsPerSite = callsPerSite;
        this.maxSites = maxSites;
    }

    /**
     * Starts the count again, for a new run, with no call counted.
     */
    @SuppressWarnings("PMD.NullAssignment")
    void restart() {
        callsMade = 0;
        // Dropped, not cleared: a table left as large as the biggest run's would cost every later run to clear.
        callsBySite = null;
    }

    /**
     * Counts a call for the class loader.
     *
     * @return {@code true} if the call's site has now made more calls than it may, counted at every call, or more sites
     *         were walked than this count may keep: {@value #MAX_SITES}, unless a test gave it fewer
     */
    boolean counted() {
        return countedAt(wholeStack ? WHOLE_STACK : TOP_AND_DEPTH);
    }

    /**
     * Counts a call for the class loader, from the site a function tells, which is asked only for a call that is
     * walked. Only for tests, which give each call's site: {@link #counted()} reads it from the stack. If the function
     * throws {@link StackOverflowError}, as a walk of the stack with no room does, or an {@link InternalError} with one
     * in its cause chain, as such a walk may on JDK 25 and 26, the walk is skipped: the calls it would have counted
     * count at no site, and the next walk comes where it would have (#1102). Any other {@link InternalError} is thrown
     * as it is.
     *
     * @param site Tells the call's site
     * @return {@code true} if the call's site has now made more calls than it may, counted at every call, or more sites
     *         were walked than this count may keep; {@code false} for a walk that was skipped
     */
    boolean countedAt(LongSupplier site) {
        callsMade++;
        if (callsMade <= UNCOUNTED_CALLS) {
            return false;
        }
        if (callsBySite == null) {
            callsBySite = new HashMap<>();
            exact = false;
            gapState = FIRST_GAP_STATE;
            lastWalk = UNCOUNTED_CALLS;
            nextWalk = UNCOUNTED_CALLS + nextGap();
        }
        if (!exact && callsMade < nextWalk) {
            return false;
        }
        // The calls this walk counts, and the next walk, set before the stack is walked, so a walk with no room counts
        // the calls since the last walk at no site, and the next walk counts none of them.
        long walkedCalls = 1;
        if (!exact) {
            walkedCalls = callsMade - lastWalk;
            lastWalk = callsMade;
            nextWalk = callsMade + nextGap();
        }
        Long walked;
        try {
            walked = site.getAsLong();
        } catch (StackOverflowError noRoom) {
            // A walk takes more room than is left, the same whatever the stack's depth, and overflows in the JDK's
            // code that walks it, usually as the walk starts, sometimes as it fetches more frames (#1102); nothing is
            // counted until the walk returns. Its calls count at no site: the limit in all still stops a loop, as
            // getClassLoader() counts it down before each call is counted here.
            return false;
        } catch (InternalError wrapped) {
            // On JDK 25 and 26, and 24 by its source, the JDK creates the frames it reads reflectively, usually as the
            // walk starts, sometimes as it fetches more, and wraps an overflow there in an InternalError, as the cause
            // of its cause. Anything else is thrown as it is.
            for (Throwable link : ExceptionReads.causeChain(wrapped)) {
                if (link instanceof StackOverflowError) {
                    return false;
                }
            }
            throw wrapped;
        }
        // Not computeIfAbsent with a lambda, which the first walk would link (#1099). The count belongs to one pass
        // or one run, which one thread makes at a time.
        long[] siteCalls = callsBySite.get(walked);
        if (siteCalls == null) {
            siteCalls = new long[1];
            callsBySite.put(walked, siteCalls);
        }
        siteCalls[0] += walkedCalls;
        if (callsBySite.size() > maxSites) {
            return true;
        }
        if (siteCalls[0] <= callsPerSite) {
            return false;
        }
        if (exact) {
            return true;
        }
        // A count drawn from the walks passed the bound, which a valid expression's may too: from here on, every call
        // is walked and counted once, at its own site.
        exact = true;
        callsBySite = new HashMap<>();
        return false;
    }

    /**
     * Returns how many calls the pass or run has made.
     *
     * @return The calls counted
     */
    long calls() {
        return callsMade;
    }

    /**
     * Returns how many sites the pass or run has walked: since every call has been counted, once it is.
     *
     * @return The sites, or 0 before the first walk
     */
    int sites() {
        return callsBySite == null ? 0 : callsBySite.size();
    }

    // The number of calls to the next walk, from 1 to twice SAMPLED_EVERY less one.
    private long nextGap() {
        gapState ^= gapState << 13;
        gapState ^= gapState >>> 7;
        gapState ^= gapState << 17;
        return 1 + Math.floorMod(gapState, 2L * SAMPLED_EVERY - 1);
    }

    /**
     * Walks the stack once each way a site is told, by the whole stack and by its top and its depth, so the JDK's
     * classes that walk it are loaded, and those that have a static initializer initialized, before a first pass or run
     * walks it, maybe deep in a stack (#1099). Only {@link MvelExpressionLanguage#prepare()} calls this.
     */
    static void warmUp() {
        hashOfStack();
        hashOfTopAndDepth();
    }

    // A hash of every frame of the stack.
    private static long hashOfStack() {
        return WALKER.walk(WHOLE_STACK_HASH);
    }

    // A hash of the RUN_FRAMES frames at the top of the stack, and how many frames it has.
    private static long hashOfTopAndDepth() {
        long top = WALKER.walk(TOP_HASH);
        return top * PRIME + COUNTER.walk(DEPTH);
    }

    // A frame's class name and the index in its method's code of the call it is making. The method's name isn't read:
    // that costs more than the rest of the frame, and two of the class's methods at the same index can only make two
    // sites count as one.
    private static long frame(StackWalker.StackFrame frame) {
        return frame.getClassName().hashCode() * 31L + frame.getByteCodeIndex();
    }

    /**
     * A hash of the frames at the top of the stack, up to a number of them, read with a loop rather than a stream's
     * {@code limit}, whose first use would link the JDK's own lambdas (#1099).
     */
    private static final class FramesHash implements Function<Stream<StackWalker.StackFrame>, Long> {

        // How many frames are read: Long.MAX_VALUE for every one.
        private final long frames;

        FramesHash(long frames) {
            this.frames = frames;
        }

        @Override
        public Long apply(Stream<StackWalker.StackFrame> stack) {
            long hash = 0;
            Iterator<StackWalker.StackFrame> each = stack.iterator();
            for (long read = 0; read < frames && each.hasNext(); read++) {
                hash = hash * PRIME + frame(each.next());
            }
            return hash;
        }
    }

    /**
     * How many frames the stack has, counted with a loop rather than a stream's {@code count}, whose first use would
     * load the JDK's classes that reduce a stream (#1099).
     */
    private static final class FrameCount implements Function<Stream<StackWalker.StackFrame>, Long> {

        @Override
        public Long apply(Stream<StackWalker.StackFrame> stack) {
            long count = 0;
            for (Iterator<StackWalker.StackFrame> each = stack.iterator(); each.hasNext(); each.next()) {
                count++;
            }
            return count;
        }
    }
}
