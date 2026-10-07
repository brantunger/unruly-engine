package io.github.brantunger.unruly.core;

/**
 * Steps of a run's, a load's or a close's set-up and clean-up that a test can make fail, as a
 * {@link StackOverflowError} or an {@link OutOfMemoryError} can at any call, to show that the state each takes is
 * put back and the steps after it still run; and steps a test can only watch, to act at a point no other call can
 * reach. It is a deliberate test seam: nothing but a test sets a fault or a watch, and with none set each step costs a
 * read of one field. A fault fails a step, and a watch runs, only on the thread it was set for, so a thread of another
 * test that reaches the same step meanwhile is never failed, nor uses the fault or the watch up.
 */
final class Faults {

    /** A step a test can make fail, or, for one reached with {@link #reached(Step)}, only watch. */
    enum Step {
        /** {@link LoggedFailures#enter()} counting a run, before it has counted anything. */
        RUN_COUNTED,
        /** {@link LoggedFailures#leave()} uncounting a run, once it has. */
        RUN_UNCOUNTED,
        /** {@link Cancellation#enter(Deadline)} setting a run's deadline, once it has. */
        DEADLINE_SET,
        /** {@link Cancellation#leave(Deadline)} putting back the deadline before a run, once it has. */
        DEADLINE_PUT_BACK,
        /** Giving back a permit or a build slot a borrow or a copy holds, before anything is given back. */
        GIVING_BACK,
        /** {@link CopyPermits#giveBack()}, once the permit is back. */
        PERMIT_RELEASED,
        /** {@link CopyPermits#giveBackSlot()}, once the build slot is back. */
        SLOT_RELEASED,
        /** A rule set lending the sessions a run took or made as its copy, before it has. */
        COPY_LENT,
        /** {@link RuleSet#retire()} setting aside an idle copy it has taken, before it has. */
        COPY_TAKEN,
        /** {@link RuleSet#retire()} marking the rule set retired, before it has. */
        RETIRE_MARKED,
        /** A rule set closing a queue of copies, retired or idle, before it has closed any. */
        COPIES_CLOSING,
        /** {@link RuleSet#retire()}, once it has closed the copies it took, before it counts that done. */
        RETIRED_COPIES_CLOSED,
        /** {@code load()} making the copies of the rules it makes at load, before it has made any. */
        COPIES_PREPARED,
        /** A {@code load()} or {@code close()} retiring the rule sets it has claimed, before it has retired any. */
        CLAIMED_RETIRING,
        /** Settling the engine's list of rule sets to retire, before it looks at each rule set in it. */
        SETTLING,
        /** A run that stopped setting its thread's interrupt status again, before it has. */
        INTERRUPT_KEPT,
        /** A run that has taken the values its languages kept to be closed, before it has closed any. */
        RUN_VALUES_CLOSING,
        /**
         * A run handling what a value its languages kept threw from {@code close()}: before it logs it, and again, if
         * logging it failed, before it keeps what it threw.
         */
        RUN_VALUE_FAILURE_LOGGED,
        /** A run that has closed every value its languages kept, before it combines what they threw. */
        RUN_VALUES_CLOSED,
        /** A run that has closed its values and given back its copy, before it combines what they threw. */
        RUN_ENDING_COMBINED,
        /** {@link RunScope#end()} waiting for another thread to give back the scope's turn, before it parks. */
        RUN_SCOPE_END_WAITING,
        /** {@link RunScope#end()} setting the interrupt status again once its wait was interrupted, before it has. */
        RUN_SCOPE_END_INTERRUPTING,
        /**
         * A run's scope refusing a value whose init ended the run, once it has closed the value, before it builds the
         * failure it throws.
         */
        RUN_VALUE_REFUSING,
        /**
         * {@link EngineEvaluationContext#endRun} keeping a failure on what it throws, before it has: what a value's
         * {@code close()} threw, each time one did, and once every value is closed, what failed before.
         */
        TEST_RUN_FAILURE_KEPT,
        /** A compile that overflowed the stack, checking the room left to tell why, before it has. */
        OVERFLOW_ROOM_CHECKED,
        /** A run whose rules need the room for a language's first run, before it checks that room. */
        FIRST_RUN_ROOM_CHECKING,
        /** Registering a cancel action that has to start the cancel timer, before it starts the timer's thread. */
        CANCEL_TIMER_STARTING,
        /**
         * The cancel timer logging at WARN: what an action threw, that one is slow or couldn't be started, or why the
         * timer failed, before it has.
         */
        CANCEL_FAILURE_LOGGED,
        /** The cancel timer starting an action whose deadline has passed, before it makes the action's thread. */
        CANCEL_ACTION_STARTING,
        /**
         * The cancel timer starting an action on a platform thread, as it couldn't start it on a virtual one, before it
         * starts the thread.
         */
        CANCEL_ACTION_FALLING_BACK,
        /**
         * {@code close()} marking the engine closed, once it has, before it lets go of the rules. Watched only, never
         * failed (see {@link #reached(Step)}).
         */
        CLOSE_MARKED(true),
        /**
         * A run's scope making its map for the first value asked for, before it has, holding the scope's monitor.
         * Watched only, never failed (see {@link #reached(Step)}).
         */
        RUN_SCOPE_MAP_MAKING(true),
        /**
         * {@link RunScope#end()} taking the values to hand back, once it has read them, before it marks the scope
         * ended, holding the scope's monitor. Watched only, never failed (see {@link #reached(Step)}).
         */
        RUN_SCOPE_VALUES_TAKING(true),
        /**
         * The cancel timer's thread exiting as no action is pending, once it has given up its place, so a registration
         * now starts another, outside the timer's lock. Watched only, never failed (see {@link #reached(Step)}).
         */
        CANCEL_TIMER_EXITED(true),
        /**
         * The cancel timer having started an action whose deadline has passed, before it looks for the next, outside
         * the timer's lock. Watched only, never failed (see {@link #reached(Step)}).
         */
        CANCEL_ACTION_HANDED_OFF(true),
        /**
         * The cancel timer about to wait for the next action due, holding the timer's lock. Watched only, never failed
         * (see {@link #reached(Step)}).
         */
        CANCEL_TIMER_WAITING(true);

        // Whether a test can only watch the step, which the code reaches with reached(Step), rather than make it fail.
        private final boolean watchOnly;

        Step() {
            this(false);
        }

        Step(boolean watchOnly) {
            this.watchOnly = watchOnly;
        }
    }

    // The step that fails, the thread it fails on, how many more times that thread reaches it before it does, how many
    // times in a row it fails from then on, and what it throws. Set before that thread reaches the step, on the thread
    // itself or before it starts; another thread reads no more than the step, and a fault set for another thread never
    // fails it.
    private static Step failing;
    private static Thread thread;
    private static int reaches;
    private static int failures;
    private static Error error;
    // The fault that follows once that one has failed for the last time, on the same thread: the step that fails the
    // next time the thread reaches it, once, and what it throws.
    private static Step then;
    private static Error thenError;
    // The step that is watched, the thread it is watched on, and what that thread runs the next time it reaches it.
    // Set as a fault is, the step last: it's volatile, so a thread that reads the step sees the thread and the action
    // set with it, never those of an earlier watch.
    private static volatile Step watched;
    private static Thread watching;
    private static Runnable onReach;

    private Faults() {
    }

    /**
     * Makes {@code step} throw {@code thrown} the {@code reach}th time the current thread reaches it from now on, once.
     *
     * @param step   The step
     * @param reach  Which time it fails, from 1
     * @param thrown What it throws
     */
    static void inject(Step step, int reach, Error thrown) {
        inject(Thread.currentThread(), step, reach, thrown);
    }

    /**
     * Makes {@code step} throw {@code thrown} the {@code reach}th time {@code on} reaches it, once. Call it before
     * {@code on} starts, so the fault is published to it.
     *
     * @param on     The thread the step fails on
     * @param step   The step
     * @param reach  Which time it fails, from 1
     * @param thrown What it throws
     */
    static void inject(Thread on, Step step, int reach, Error thrown) {
        inject(on, step, reach, 1, thrown);
    }

    /**
     * Makes {@code step} throw {@code thrown} the {@code reach}th time {@code on} reaches it, and the {@code times - 1}
     * times it reaches it next. Call it before {@code on} starts, so the fault is published to it.
     *
     * @param on     The thread the step fails on
     * @param step   The step
     * @param reach  Which time it first fails, from 1
     * @param times  How many times in a row it fails
     * @param thrown What it throws
     * @throws IllegalArgumentException if the step can only be watched
     */
    static void inject(Thread on, Step step, int reach, int times, Error thrown) {
        if (step.watchOnly) {
            throw new IllegalArgumentException(step + " can only be watched");
        }
        thread = on;
        reaches = reach;
        failures = times;
        error = thrown;
        failing = step;
    }

    /**
     * Makes {@code step} throw {@code thrown} the next time the thread of the fault set last reaches it once that
     * fault has failed for the last time, once: so one run can fail at two steps. Call it after that fault is set,
     * before its thread reaches it; {@link #clear()} takes it back with that fault.
     *
     * @param step   The step, which the thread reaches after the step of the fault set last
     * @param thrown What it throws
     * @throws IllegalArgumentException if the step can only be watched
     */
    static void injectThen(Step step, Error thrown) {
        if (step.watchOnly) {
            throw new IllegalArgumentException(step + " can only be watched");
        }
        thenError = thrown;
        then = step;
    }

    /**
     * Makes the current thread run {@code action} the next time it reaches {@code step}, once.
     *
     * @param step   The step, one the code reaches with {@link #reached(Step)}
     * @param action What the thread runs there
     * @throws IllegalArgumentException if the step is one a test makes fail, which is never watched
     */
    static void watch(Step step, Runnable action) {
        watch(Thread.currentThread(), step, action);
    }

    /**
     * Makes {@code on} run {@code action} the next time it reaches {@code step}, once, for a step only a thread the
     * engine owns reaches. Call it while {@code on} hasn't reached the step yet.
     *
     * @param on     The thread that runs it
     * @param step   The step, one the code reaches with {@link #reached(Step)}
     * @param action What the thread runs there
     * @throws IllegalArgumentException if the step is one a test makes fail, which is never watched
     */
    static void watch(Thread on, Step step, Runnable action) {
        if (!step.watchOnly) {
            throw new IllegalArgumentException(step + " is made to fail, not watched");
        }
        watching = on;
        onReach = action;
        watched = step;
    }

    /** Takes back a fault that hasn't been thrown yet, and a watch that hasn't run yet. */
    // No fault or watch is set until a test sets one.
    @SuppressWarnings("PMD.NullAssignment")
    static void clear() {
        failing = null;
        thread = null;
        error = null;
        then = null;
        thenError = null;
        watched = null;
        watching = null;
        onReach = null;
    }

    /**
     * Reaches a step, which throws if a test made it fail this time on this thread.
     *
     * @param step The step reached
     */
    // A fault is thrown once, and on the very thread it was set for.
    @SuppressWarnings({"PMD.NullAssignment", "PMD.CompareObjectsWithEquals"})
    static void at(Step step) {
        if (step == failing && Thread.currentThread() == thread) {
            reaches--;
            if (reaches == 0) {
                // The next time fails too, while failures are left.
                reaches = 1;
                failures--;
                Error thrown = error;
                if (failures == 0) {
                    // The fault that follows, if one was set, fails the next time this thread reaches its step.
                    failing = then;
                    error = thenError;
                    failures = 1;
                    then = null;
                    thenError = null;
                }
                throw thrown;
            }
        }
    }

    /**
     * Reaches a step a test can only watch, which runs what a test set for it this time on this thread. No fault is
     * ever thrown here: it is for a step where a failure would leave state that no later call can mend. It doesn't
     * catch what the test's action throws, though, nor can it stop a call to it running out of stack, so the caller
     * keeps its state whole around it, as {@code close()} does by letting go of the rules in a finally.
     *
     * @param step The step reached
     */
    // A watch runs once, and on the very thread it was set for.
    @SuppressWarnings({"PMD.NullAssignment", "PMD.CompareObjectsWithEquals"})
    static void reached(Step step) {
        if (step == watched && Thread.currentThread() == watching) {
            watched = null;
            onReach.run();
        }
    }
}
