package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest.Nesting;
import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.brantunger.unruly.test.KitFailures.describe;
import static io.github.brantunger.unruly.test.KitFailures.runAroundNestedFailed;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * How the contract kit's nested-run checks run the rules around a run nested in that run, which a {@link Nesting}
 * fact's getter starts, and how they tell a nested run that failed as the check planned from one that failed for
 * another reason.
 */
final class NestedRuns {

    /** The fact whose getter starts a nested run. */
    static final String NEST = "nest";

    /**
     * What the getter of a {@link Nesting} made to fail puts in its message, so that the checks recognize the failure
     * a language rethrows with that message, rather than with the exception as a cause.
     */
    static final String PLANNED_FAILURE = "[unruly contract kit: planned nested-run failure]";

    private NestedRuns() {
    }

    /**
     * Runs the rules with {@code x} 1 and {@code nest}, and reports a failed run by what happened in it: a run that
     * failed before its expression started the nested run is reported as the engine reported it.
     *
     * @param where   Where the nested run starts, {@code "condition"} or {@code "action"}, which the failure says
     * @param failing The {@link Nesting} the check makes the nested run fail with, or {@code null}: a nested failure
     *                that is the one it planned isn't a second defect
     */
    static @Nullable Map<String, Object> runAround(RulesEngine<Map<String, Object>> engine, Nesting nest,
                                                   String where, @Nullable Nesting failing) {
        try {
            return engine.run(new FactMap<>(new Fact<>("x", 1), new Fact<>(NEST, nest)));
        } catch (RuleExecutionException e) {
            if (!nest.started) {
                throw e;
            }
            RuntimeException nestedFailure = nest.nestedFailure;
            throw runAroundNestedFailed(e, where, nestedFailure,
                    failing != null && nestedFailure != null && failedAsPlanned(nestedFailure, failing));
        }
    }

    /**
     * Checks that {@code nest} started its nested run, and that the run failed because it read {@code failing}, as
     * {@link #failedAsPlanned} decides. The check is skipped when the nested run neither failed nor read
     * {@code failing}, since it can't be made to fail then; a nested run that read it and didn't fail, or failed for
     * another reason, proves nothing about what a failed one leaves, and fails the check.
     *
     * @param where Where the nested run starts, {@code "condition"} or {@code "action"}, which the failure says
     */
    static void assertNestedRunFailed(Nesting nest, Nesting failing, String where) {
        assertTrue(nest.started, "the " + where + " didn't read nest.value, so no run was started inside it");
        RuntimeException failure = nest.nestedFailure;
        String nested = "the run started inside the " + where;
        if (failure == null) {
            assumeTrue(failing.started, nested + " never read its own nest.value, whose getter makes it fail, as a"
                    + " language that reads the right side of a condition first may not");
            throw new AssertionFailedError(nested + " didn't fail, though a getter its rule read threw: it returned "
                    + describe(nest.nestedOutput));
        }
        if (!failedAsPlanned(failure, failing)) {
            throw new AssertionFailedError(nested + (failing.started
                    ? " failed, but its failure doesn't carry what its nest.value threw, as a cause, a suppressed"
                    + " exception or by its message: "
                    : " failed for another reason than its nest.value, which throws: ") + describe(failure), failure);
        }
    }

    /**
     * Whether a nested run's failure is the one {@code failing} made: a {@link RuleExecutionException} that carries,
     * among its causes and suppressed exceptions and theirs, one of the exceptions the getter of {@code failing}
     * threw, or an exception whose message contains {@link #PLANNED_FAILURE}, as one a language rethrows with that
     * message does. The walk is bounded, in case exceptions name each other.
     */
    // By identity: the instances the getter threw are what the failure must carry.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean failedAsPlanned(RuntimeException failure, Nesting failing) {
        if (!(failure instanceof RuleExecutionException)) {
            return false;
        }
        Deque<Throwable> toVisit = new ArrayDeque<>();
        toVisit.add(failure);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!toVisit.isEmpty() && visited.size() < 100) {
            Throwable next = toVisit.pop();
            if (!visited.add(next)) {
                continue;
            }
            if (failing.thrown.stream().anyMatch(thrown -> thrown == next) || carriesPlannedFailure(next)) {
                return true;
            }
            toVisit.addAll(linked(next));
        }
        return false;
    }

    /** An exception's cause and suppressed exceptions; none, for one whose own methods throw when they're read. */
    private static List<Throwable> linked(Throwable thrown) {
        try {
            List<Throwable> linked = new ArrayList<>(Arrays.asList(thrown.getSuppressed()));
            Throwable cause = thrown.getCause();
            if (cause != null) {
                linked.add(0, cause);
            }
            return linked;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Whether an exception's message contains {@link #PLANNED_FAILURE}; one that can't be read doesn't. */
    private static boolean carriesPlannedFailure(Throwable thrown) {
        try {
            String message = thrown.getMessage();
            return message != null && message.contains(PLANNED_FAILURE);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
