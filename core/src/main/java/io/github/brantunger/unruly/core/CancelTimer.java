package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.CancelRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Waits for the deadlines of the actions languages register with
 * {@link io.github.brantunger.unruly.api.language.EvaluationContext#onCancel(Runnable)}, and starts each action when
 * its deadline passes. One timer serves every engine in the JVM, and the test kit's contexts too.
 *
 * <p>
 * The timer is a daemon platform thread, named {@value #THREAD_NAME}, that starts when an action is registered and
 * none is pending, and exits as soon as none is: a JVM whose languages never register one never starts it, nor any
 * thread to run one, and the threads the actions ran on end once they have had none to run for a second. The thread
 * is started, and decides to exit, in sections synchronized on one lock, as registering is, so a registration either
 * finds the thread there to wait for it or starts another. If it ever fails, it gives up its place all the same, logs
 * why at WARN, and starts another at once for the actions still pending, if it had started or logged one since it
 * started; otherwise the next registration starts another, so an error that recurs at once can't start timer after
 * timer.
 * </p>
 *
 * <p>
 * The timer never runs a language's code itself: it hands each action to a pool of daemon platform threads, named
 * {@value #ACTION_THREAD_NAME}, shared by every engine, which starts a thread when none is free and ends one that has
 * had no action to run for a second. So an action that is slow, or blocks, delays no other action, of any engine, and
 * an action runs however busy the carriers of virtual threads are, as one on a virtual thread doesn't while runs that
 * never block hold every carrier. Only if the pool can't take it, as when no thread can be made, is the action started
 * on a virtual thread of its own. Neither the timer nor an action's thread inherits the inheritable thread-locals of
 * the run that started it, nor any context class loader, and a pooled thread's context class loader and interrupt
 * status are cleared after each action. But a pooled thread then runs the actions of other runs and engines, so an
 * action must not leave thread-locals behind, such as a logging context: the next action on its thread would see
 * them. What an action throws is logged at WARN, and its thread goes on to run others, even after a fatal
 * {@link Error}; one thrown by an action that runs on the thread that registered it, its deadline having passed, is
 * thrown there. An action still pending a second after it was handed to a thread is logged at WARN once: while it
 * runs, as an action should only tell the runtime to stop, or as not started, if no thread was free to run it by then.
 * The timer keeps it pending until it returns or that second is up.
 * </p>
 *
 * <p>
 * The pending actions are kept unordered, and the timer finds those due, and the next one due, by comparing each one's
 * due time with one {@link System#nanoTime()} reading, never one with another (see {@link Deadline}). That is one scan
 * of every pending action each time the timer wakes, and there is one pending for each run that registered an action
 * and hasn't ended, and for each action running less than a second; registering and closing one are a store each. All
 * the actions a scan finds due are started once it ends, in the order it found them, not that of their deadlines, so
 * many falling due at once cost one scan, not one each. The timer sleeps until the next one is due, and is woken when
 * one due sooner is registered, or the one it waits for is closed.
 * </p>
 */
final class CancelTimer {

    /** The timer thread's name, so it can be told in a thread dump. */
    static final String THREAD_NAME = "unruly-cancel-timer";

    /** The name of the thread each action runs on. */
    static final String ACTION_THREAD_NAME = "unruly-cancel-action";

    /** How long an action may run before it is logged as slow: a second. */
    static final long SLOW_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** What a run that registers nothing gets: closing it does nothing, and touches nothing shared. */
    static final CancelRegistration NOTHING = Nothing.INSTANCE;

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);
    private static final Object LOCK = new Object();
    private static final Runnable LOOP = new Loop();
    private static final String FAILED = "An action a language registered for the run's deadline failed: {}";
    private static final String NOT_STARTED = "An action a language registered for the run's deadline couldn't be"
            + " started: {}";
    private static final String STILL_RUNNING = "An action a language registered for the run's deadline has run for"
            + " over {} ms, and is still running: it should only tell the runtime to stop, such as by setting a flag"
            + " the runtime reads";
    private static final String NO_THREAD_FREE = "An action a language registered for the run's deadline hasn't"
            + " started after {} ms: no thread was free";

    // How long an action may run before it's logged as slow: SLOW_NANOS, but in a test. Read when it's started.
    private static volatile long slowNanos = SLOW_NANOS;

    // Guarded by LOCK: the pending actions, unordered, made with the first; how many there are; the timer thread, null
    // when none is running; and the action it sleeps until, null while it isn't sleeping.
    private static Registration[] pending;
    private static int count;
    private static Thread thread;
    private static Registration waitingFor;
    // Guarded by LOCK: whether the running timer has started an action, or logged one as slow, since it started. A
    // timer that dies is replaced at once only if it had, so an error that recurs before it can do anything can't
    // start timer after timer.
    private static boolean progressed;

    private CancelTimer() {
    }

    /** The registration of a run that registers nothing. */
    private enum Nothing implements CancelRegistration {
        INSTANCE;

        @Override
        public void close() {
            // Nothing was registered.
        }
    }

    /** An action a run registered, and where it is in the timer's list. */
    static final class Registration implements CancelRegistration {
        // Its states, in order, set holding LOCK: made, not yet handed to the timer; waiting for its deadline; handed
        // to a thread, and pending until it returns or is found slow; handed, and pending no more; closed before that.
        // The last three never go back to the first two, so a read without the lock that sees one of them is right.
        static final int MADE = 0;
        static final int PENDING = 1;
        static final int RUNNING = 2;
        static final int STARTED = 3;
        static final int CLOSED = 4;

        private final Runnable action;
        // Its deadline's nanoTime() value (see Deadline#passesAt()).
        private final long nanos;
        private volatile int state;
        // Whether its thread has begun to run it, once it's RUNNING: set on that thread, read by the timer once it's
        // found slow, to tell an action that runs from one no thread was free to run.
        private volatile boolean began;
        // The nanoTime() value at which it's logged as slow if it hasn't returned, once it's RUNNING. Guarded by LOCK.
        private long slowAt;
        // Where it is in pending while it is pending or running.
        private int index;
        // The registration its run made before it, guarded by the run's scope (see RunScope#addCancel).
        private Registration previous;

        Registration(Runnable action, long nanos, int state) {
            this.action = action;
            this.nanos = nanos;
            this.state = state;
        }

        /**
         * Tells whether the action has started or been closed, so its run needn't keep it to close.
         *
         * @return {@code true} if it has
         */
        boolean finished() {
            return state >= RUNNING;
        }

        /**
         * Returns the registration its run made before it.
         *
         * @return It, or {@code null} if this is the run's first
         */
        Registration before() {
            return previous;
        }

        /**
         * Records the registration its run made before it.
         *
         * @param earlier It, or {@code null} if this is the run's first
         */
        void before(Registration earlier) {
            previous = earlier;
        }

        // Allocates nothing, so a run's end can close it whatever has run out.
        @Override
        public void close() {
            synchronized (LOCK) {
                if (state == PENDING) {
                    remove(this);
                }
                if (state < RUNNING) {
                    state = CLOSED;
                }
            }
        }

        // When the timer must look at it next: its deadline while pending, and when it's slow while running.
        private long dueAt() {
            return state == PENDING ? nanos : slowAt;
        }
    }

    /** The timer thread's loop. */
    private static final class Loop implements Runnable {
        @Override
        public void run() {
            serve();
        }
    }

    /** An action whose deadline has passed, run on a pooled thread, or a virtual thread of its own. */
    private static final class Started implements Runnable {
        private final Registration registration;

        Started(Registration registration) {
            this.registration = registration;
        }

        // Any Throwable: whatever the action threw is logged, and the thread then goes on as it would have, to the next
        // action if it's pooled; nothing is left on it to stop, and an uncaught error would only be printed a second
        // time.
        @Override
        public void run() {
            registration.began = true;
            try {
                Throwable failed = CancelTimer.run(registration.action);
                if (failed != null) {
                    warn(FAILED, failed);
                }
            } finally {
                returned(registration);
            }
        }
    }

    /**
     * Has {@code action} run when {@code deadline} passes, unless it's closed first, starting the timer if none is
     * running. A registration the caller closed before this, as its run's end can, is left closed.
     *
     * @param registration What a run registered, made with {@link #registration}
     * @throws Error if the timer has to be started and starting it fails, as it can when the JVM is out of memory or
     *               threads: the registration is then closed
     */
    // Any Throwable: a registration the timer can't serve is closed, not left pending with no thread to run it.
    static void schedule(Registration registration) {
        synchronized (LOCK) {
            if (registration.state != Registration.MADE) {
                return;
            }
            add(registration);
            registration.state = Registration.PENDING;
            if (thread == null) {
                try {
                    startTimer();
                } catch (Throwable t) {
                    remove(registration);
                    registration.state = Registration.CLOSED;
                    throw t;
                }
            } else if (waitingFor != null) {
                // Woken only for one due before the one it sleeps until: each compared with now, not with the other.
                long now = System.nanoTime();
                if (registration.nanos - now < waitingFor.dueAt() - now) {
                    LOCK.notifyAll();
                }
            }
        }
    }

    // Holding LOCK, with no timer running. Starts one, which hasn't yet started or dropped anything.
    private static void startTimer() {
        Faults.at(Faults.Step.CANCEL_TIMER_STARTING);
        Thread timer = Thread.ofPlatform().name(THREAD_NAME).daemon(true).priority(Thread.NORM_PRIORITY)
                .inheritInheritableThreadLocals(false).unstarted(LOOP);
        // Nothing of the run that starts it: not its inheritable thread-locals, which the builder leaves out, nor its
        // class loader, which would be kept as long as the timer runs.
        timer.setContextClassLoader(null);
        timer.start();
        thread = timer;
        progressed = false;
    }

    /**
     * Makes the registration of an action for a deadline, for {@link #schedule} once the run has recorded it.
     *
     * @param action   What to run
     * @param deadline When to run it; set, and not passed
     * @return The registration
     */
    static Registration registration(Runnable action, Deadline deadline) {
        return new Registration(action, deadline.passesAt(), Registration.MADE);
    }

    /**
     * Runs an action whose deadline had passed when it was registered, on the thread that registered it. What it
     * throws is logged at WARN and ignored, unless it is, or carries, a fatal {@link Error} (see
     * {@link Failures#fatalError}): that error is thrown, as the engine throws one from a language's code.
     *
     * @param action The action
     * @throws Error the fatal error the action threw
     */
    static void fire(Runnable action) {
        Throwable failed = run(action);
        if (failed != null) {
            Failures.throwIfPresent(Failures.fatalError(failed));
            warn(FAILED, failed);
        }
    }

    /**
     * Sets how long an action may run before it's logged as slow, for a test.
     *
     * @param nanos The time, in nanoseconds: {@link #SLOW_NANOS} but in a test
     */
    static void slowAfter(long nanos) {
        slowNanos = nanos;
    }

    /**
     * Returns the timer thread, for a test to wait for it to exit.
     *
     * @return The thread, or {@code null} if none is running
     */
    static Thread runningThread() {
        synchronized (LOCK) {
            return thread;
        }
    }

    /**
     * Returns how many actions are pending, waiting for their deadlines or running, for a test.
     *
     * @return The number
     */
    static int pendingCount() {
        synchronized (LOCK) {
            return count;
        }
    }

    // Any Throwable: whatever an action throws is its caller's to handle, as fire() and Started decide.
    private static Throwable run(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    // Starts an action whose deadline has passed on a pooled daemon platform thread, which runs however busy the
    // carriers of virtual threads are, or on a virtual thread of its own if the pool can't take it, so the timer runs
    // no language code; neither inherits anything of the timer's. Any Throwable: an action that can't be started
    // either way, as when the JVM is out of memory, is logged and dropped, and the timer goes on. What each thread runs
    // is made inside its try, so running out of memory making it is a failure to start too.
    private static void start(Registration registration) {
        try {
            ActionPool.ACTIONS.execute(new PooledAction(registration));
        } catch (Throwable e) {
            try {
                Faults.at(Faults.Step.CANCEL_ACTION_FALLING_BACK);
                Thread virtual = Thread.ofVirtual().name(ACTION_THREAD_NAME).inheritInheritableThreadLocals(false)
                        .unstarted(new Started(registration));
                virtual.setContextClassLoader(null);
                virtual.start();
            } catch (Throwable again) {
                returned(registration);
                warn(NOT_STARTED, again);
            }
        }
    }

    /**
     * The actions' pool, made the first time an action is started, so at run time even in a native image: daemon
     * platform threads, one made whenever none is free, each ended once it has had no action to run for a second.
     */
    private static final class ActionPool {
        static final ThreadPoolExecutor ACTIONS = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 1, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ActionThreads());

        private ActionPool() {
        }
    }

    /**
     * Makes the pool's threads: daemon platform threads that inherit nothing of the thread that makes them. It runs on
     * the timer's thread, in the pool's {@code execute()}, which throws what it throws.
     */
    private static final class ActionThreads implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Faults.at(Faults.Step.CANCEL_ACTION_STARTING);
            Thread pooled = Thread.ofPlatform().name(ACTION_THREAD_NAME).daemon(true).priority(Thread.NORM_PRIORITY)
                    .inheritInheritableThreadLocals(false).unstarted(task);
            pooled.setContextClassLoader(null);
            return pooled;
        }
    }

    /**
     * An action run on a pooled thread, which then clears the thread's context class loader, which the action may have
     * set, and its interrupt status, for the next action; the action's thread-locals are its own to leave none behind.
     */
    private static final class PooledAction implements Runnable {
        private final Started started;

        PooledAction(Registration registration) {
            started = new Started(registration);
        }

        @Override
        public void run() {
            try {
                started.run();
            } finally {
                Thread.currentThread().setContextClassLoader(null);
                Thread.interrupted();
            }
        }
    }

    // On the action's thread once it has returned, or on the timer's if it couldn't be started: it's no longer
    // pending, so the timer neither logs it as slow nor waits for it.
    private static void returned(Registration registration) {
        synchronized (LOCK) {
            if (registration.state == Registration.RUNNING) {
                remove(registration);
            }
            registration.state = Registration.STARTED;
        }
    }

    // Logs at WARN, with what failed described, or another argument as it is. Any Throwable: logging can fail too, as
    // it can when the stack or the heap runs out, and the message is then lost, but never the thread that logs it.
    private static void warn(String message, Object argument) {
        try {
            Faults.at(Faults.Step.CANCEL_FAILURE_LOGGED);
            log.warn(message, argument instanceof Throwable failure ? Failures.describeWithClass(failure) : argument);
        } catch (Throwable ignored) {
            // Lost.
        }
    }

    // Holding LOCK, which PMD can't see from here. Adds one to the pending, making room first.
    @SuppressWarnings("PMD.NonThreadSafeSingleton")
    private static void add(Registration registration) {
        if (pending == null) {
            pending = new Registration[8];
        } else if (count == pending.length) {
            pending = Arrays.copyOf(pending, count * 2);
        }
        registration.index = count;
        pending[count] = registration;
        count++;
    }

    // Holding LOCK. Moves the last pending action into the removed one's place, and wakes the timer if it sleeps until
    // the removed one, so it doesn't wait for a time nothing needs, nor stay when none is pending.
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.NullAssignment", "PMD.CloseResource"})
    private static void remove(Registration registration) {
        count--;
        int last = count;
        Registration moved = pending[last];
        pending[registration.index] = moved;
        moved.index = registration.index;
        pending[last] = null;
        if (registration == waitingFor) {
            waitingFor = null;
            LOCK.notifyAll();
        }
    }

    // The timer thread: starts each action once its deadline passes, logs one not returned once it's slow, and exits
    // once none is pending.
    // Only this thread clears thread, and only as it exits, so while it runs thread is this one. Any Throwable: a timer
    // that dies is logged and replaced, or left for the next registration to replace, and the thread then ends.
    private static void serve() {
        try {
            serveUntilIdle();
        } catch (Throwable e) {
            died(e);
            return;
        }
        Faults.reached(Faults.Step.CANCEL_TIMER_EXITED);
    }

    // A timer that died gives up its place, and if actions are still pending, and it had started or logged one since
    // it started, starts another at once to serve them. One that had done neither is replaced only by the next
    // registration, so an error that recurs as soon as a timer runs can't start timer after timer. Any Throwable:
    // starting another can fail too, and it's then left to the next registration as well.
    @SuppressWarnings("PMD.NullAssignment")
    private static void died(Throwable e) {
        boolean restarted = false;
        boolean left;
        synchronized (LOCK) {
            thread = null;
            waitingFor = null;
            left = count > 0;
            if (left && progressed) {
                try {
                    startTimer();
                    restarted = true;
                } catch (Throwable t) {
                    e.addSuppressed(t);
                }
            }
        }
        warn(restarted ? "The cancel timer failed, and another has been started for the actions still pending: {}"
                : left ? "The cancel timer failed, and the actions still pending wait for the next one registered to"
                + " start another: {}" : "The cancel timer failed with no action pending: {}", e);
    }

    // The registrations are the runs' to close; waitingFor is read by the threads that register and close them while
    // this one waits. Returns once it has given up its place, none being pending. Each time it wakes, it looks at every
    // pending action once, holding LOCK, and marks all those due, and takes off the list those found slow; once it has
    // let go of LOCK, it starts the due ones, in the order it found them, then logs the slow ones.
    @SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource", "PMD.UnusedAssignment"})
    private static void serveUntilIdle() {
        for (;;) {
            // One array for both: the actions found due, from the front, and those found slow, from the back.
            Registration[] dueAndSlow;
            int starts;
            int slows;
            long slowFor;
            synchronized (LOCK) {
                for (;;) {
                    if (count == 0) {
                        // In the section that saw none pending, so a registration after it starts another timer.
                        thread = null;
                        return;
                    }
                    dueAndSlow = null;
                    starts = 0;
                    slows = 0;
                    long now = System.nanoTime();
                    slowFor = slowNanos;
                    Registration next = null;
                    long left = 0;
                    // Downwards, so the last one, which remove() moves into the place of one found slow, has been
                    // looked at already.
                    for (int i = count - 1; i >= 0; i--) {
                        Registration it = pending[i];
                        long itsLeft = it.dueAt() - now;
                        if (itsLeft > 0) {
                            if (next == null || itsLeft < left) {
                                next = it;
                                left = itsLeft;
                            }
                            continue;
                        }
                        if (dueAndSlow == null) {
                            // Made before any is marked, so running out of memory here marks none: the timer dies with
                            // every action as it was. No more than the i + 1 not yet looked at can be due or slow.
                            dueAndSlow = new Registration[i + 1];
                        }
                        if (it.state == Registration.PENDING) {
                            // Pending still, until it returns or is found slow.
                            it.slowAt = now + slowFor;
                            it.state = Registration.RUNNING;
                            dueAndSlow[starts] = it;
                            starts++;
                        } else {
                            remove(it);
                            it.state = Registration.STARTED;
                            slows++;
                            dueAndSlow[dueAndSlow.length - slows] = it;
                        }
                    }
                    if (dueAndSlow != null) {
                        progressed = true;
                        break;
                    }
                    waitingFor = next;
                    Faults.reached(Faults.Step.CANCEL_TIMER_WAITING);
                    try {
                        TimeUnit.NANOSECONDS.timedWait(LOCK, left);
                    } catch (InterruptedException ignored) {
                        // Nothing of the engine's interrupts this thread: it looks again.
                    }
                    waitingFor = null;
                }
            }
            // The actions first: a slow one's WARN can wait, and logging can be slow.
            for (int i = 0; i < starts; i++) {
                start(dueAndSlow[i]);
                Faults.reached(Faults.Step.CANCEL_ACTION_HANDED_OFF);
            }
            // Each as running, or as not started if its thread hasn't begun to run it.
            for (int i = 1; i <= slows; i++) {
                warn(dueAndSlow[dueAndSlow.length - i].began ? STILL_RUNNING : NO_THREAD_FREE,
                        TimeUnit.NANOSECONDS.toMillis(slowFor));
            }
        }
    }
}
