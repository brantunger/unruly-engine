package io.github.brantunger.unruly.core;

/**
 * Steps of a run's, a load's or a close's set-up and clean-up that a test can make fail, as a
 * {@link StackOverflowError} or an {@link OutOfMemoryError} can at any call, to show that the state each takes is
 * put back and the steps after it still run. It is a deliberate test seam: nothing but a test sets a fault, and with
 * none set each step costs a read of one field. A fault fails a step only on the thread it was set for, so a thread
 * of another test that reaches the same step meanwhile is never failed, nor uses the fault up.
 */
final class Faults {

    /** A step a test can make fail. */
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
        RUN_ENDING_COMBINED
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
     */
    static void inject(Thread on, Step step, int reach, int times, Error thrown) {
        thread = on;
        reaches = reach;
        failures = times;
        error = thrown;
        failing = step;
    }

    /** Takes back a fault that hasn't been thrown yet. */
    // No fault is set until a test sets one.
    @SuppressWarnings("PMD.NullAssignment")
    static void clear() {
        failing = null;
        thread = null;
        error = null;
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
                if (failures == 0) {
                    failing = null;
                }
                throw error;
            }
        }
    }
}
