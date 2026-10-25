package io.github.brantunger.unruly.core;

/**
 * The rule sets an engine's {@link RuleSetLifecycle} still has to retire, and the claims its {@code load()} and
 * {@code close()} calls retire them by: each rule set a reload replaced, {@code close()} detached or a failed
 * {@code load()} couldn't swap in, from before the engine lets go of it until it's retired for good. The lifecycle's
 * lock guards it, and it reads and writes the links of the rule sets in it ({@link CopyPool}'s {@code nextUnretired}
 * and {@code retiringClaim}), which nothing else touches. Made with the lifecycle, so with the engine.
 */
final class RetireQueue {

    // The lock of the engine's RuleSetLifecycle, which guards the fields below and the links of the rule sets in the
    // list.
    private final Object lifecycle;
    // The rule sets still to retire, linked through CopyPool.nextUnretired, so adding one allocates nothing: each one a
    // reload replaced, close() detached or a failed load() couldn't swap in, from before the engine lets go of it
    // until it's retired for good. A load() or close() claims the ones no other call has claimed
    // (CopyPool.retiringClaim) and retires them; one whose retiring fails part way, as it can when it runs out of
    // stack, stays for the next load() or close(). So none is only in a call's locals at any point, and none is
    // retired by two calls at once. Guarded by lifecycle.
    private CopyPool unretired;
    // The number of the last load() or close() that claimed rule sets to retire, so each claims with its own. Guarded
    // by lifecycle.
    private long lastClaim;

    /**
     * Creates the rule sets still to retire of an engine, with none in it.
     *
     * @param lifecycle The lock of the engine's {@link RuleSetLifecycle}
     */
    RetireQueue(Object lifecycle) {
        this.lifecycle = lifecycle;
    }

    // The number a load() or close() claims rule sets to retire with, its own. Called holding lifecycle.
    long newClaim() {
        lastClaim++;
        return lastClaim;
    }

    // Adds a rule set to the rule sets still to retire, claimed by no call. Called holding lifecycle.
    void add(CopyPool rules) {
        rules.nextUnretired = unretired;
        unretired = rules;
    }

    // Adds a rule set to the rule sets still to retire, claimed by the call holding the claim, which claims no other.
    // Called holding lifecycle.
    void addClaimed(CopyPool loaded, long claim) {
        loaded.nextUnretired = unretired;
        loaded.retiringClaim = claim;
        unretired = loaded;
    }

    // Claims the rule sets still to retire that no other call has claimed, for the call holding the claim to retire.
    // Called holding lifecycle.
    void claim(long claim) {
        for (CopyPool rules = unretired; rules != null; rules = rules.nextUnretired) {
            if (rules.retiringClaim == 0) {
                rules.retiringClaim = claim;
            }
        }
    }

    /**
     * Retires the rule sets still to retire that the caller claimed, the one it replaced or detached first, each even
     * after retiring one before it threw (see {@link CopyPool#retire()}). Each stays among the rule sets still to
     * retire until it's retired for good, and the claim on it is given up after each attempt, so one whose retiring
     * fails part way is left for the next {@code load()} or {@code close()}; the claims are given up however this
     * ends, even when calling it fails, as it can when the stack runs out, so none is claimed for good.
     *
     * @param claim The number the caller claimed them with
     * @return The first fatal {@link Error} retiring them threw or returned, carrying the others, or else the last
     *         failure retiring them threw, carrying those before it, or {@code null} if they were all retired
     */
    // Each claim this call still holds is given up, with no call in the loop.
    Throwable retireClaimed(long claim) {
        try {
            return retireEachClaimed(claim);
        } finally {
            synchronized (lifecycle) {
                for (CopyPool rules = unretired; rules != null; rules = rules.nextUnretired) {
                    if (rules.retiringClaim == claim) {
                        rules.retiringClaim = 0;
                    }
                }
            }
        }
    }

    private Throwable retireEachClaimed(long claim) {
        Faults.at(Faults.Step.CLAIMED_RETIRING);
        Error fatal = null;
        Throwable failure = null;
        for (CopyPool rules = claimed(claim); rules != null; rules = claimed(claim)) {
            try {
                fatal = Failures.first(fatal, rules.retire());
            } catch (Throwable t) {
                // Any Throwable: the rest are retired all the same, and what it was is reported once they are.
                Error thrownFatal = Failures.fatalError(t);
                if (thrownFatal == null) {
                    Failures.keepAlso(t, failure);
                    failure = t;
                } else {
                    fatal = Failures.first(fatal, thrownFatal);
                }
            } finally {
                settle(rules);
            }
        }
        Failures.keepAlso(fatal, failure);
        return fatal != null ? fatal : failure;
    }

    // The first rule set still to retire that the claim holds, or null if it holds none any more.
    private CopyPool claimed(long claim) {
        synchronized (lifecycle) {
            CopyPool rules = unretired;
            while (rules != null && rules.retiringClaim != claim) {
                rules = rules.nextUnretired;
            }
            return rules;
        }
    }

    // Gives up the claim on a rule set once retiring it has been tried, and takes every rule set retired for good out
    // of the rule sets still to retire.
    @SuppressWarnings("PMD.NullAssignment")
    private void settle(CopyPool tried) {
        synchronized (lifecycle) {
            tried.retiringClaim = 0;
            // Unlinked in place, so the list is whole, and in order, at every step, even if one fails part way.
            CopyPool previous = null;
            CopyPool rules = unretired;
            while (rules != null) {
                CopyPool next = rules.nextUnretired;
                Faults.at(Faults.Step.SETTLING);
                if (rules.retiredForGood()) {
                    if (previous == null) {
                        unretired = next;
                    } else {
                        previous.nextUnretired = next;
                    }
                    rules.nextUnretired = null;
                } else {
                    previous = rules;
                }
                rules = next;
            }
        }
    }
}
