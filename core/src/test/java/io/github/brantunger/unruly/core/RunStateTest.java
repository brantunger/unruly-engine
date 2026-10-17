package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.EvaluationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("#1246: the state of a run, which its evaluation context and each of its action contexts share")
class RunStateTest {

    private static final Instant START = Instant.parse("2027-06-01T09:00:00Z");

    private static void assertNullMessage(String expected, Executable creation) {
        assertEquals(expected, assertThrows(NullPointerException.class, creation).getMessage());
    }

    @Test
    @DisplayName("a run's state gives back what it was given, the tags as the same set")
    void keepsWhatItIsGiven() {
        Deadline deadline = Deadline.from(Duration.ofMinutes(1));
        RunScope scope = new RunScope();
        Set<String> tags = Set.of("eu");

        RunState run = new RunState(deadline, scope, START, tags);

        assertAll(
                () -> assertSame(deadline, run.deadline()),
                () -> assertSame(scope, run.scope()),
                () -> assertSame(START, run.startedAt()),
                () -> assertSame(tags, run.tags()));
    }

    @Test
    @DisplayName("a run's state needs each of its parts, and names the one that is null")
    void needsItsParts() {
        RunScope scope = new RunScope();

        assertAll(
                () -> assertNullMessage("runDeadline must not be null",
                        () -> new RunState(null, scope, START, Set.of())),
                () -> assertNullMessage("runScope must not be null",
                        () -> new RunState(Deadline.NONE, null, START, Set.of())),
                () -> assertNullMessage("runStartedAt must not be null",
                        () -> new RunState(Deadline.NONE, scope, null, Set.of())),
                () -> assertNullMessage("runTags must not be null",
                        () -> new RunState(Deadline.NONE, scope, START, null)));
    }

    @Test
    @DisplayName("the state of a run that starts now started between before and after, by the system clock, and has"
            + " no tags")
    void startingNow() {
        RunScope scope = new RunScope();
        Instant before = Instant.now();

        RunState run = RunState.startingNow(Deadline.NONE, scope);

        Instant after = Instant.now();
        assertAll(
                () -> assertSame(Deadline.NONE, run.deadline()),
                () -> assertSame(scope, run.scope()),
                () -> assertFalse(run.startedAt().isBefore(before) || run.startedAt().isAfter(after),
                        "started at " + run.startedAt()),
                () -> assertEquals(Set.of(), run.tags()));
    }

    @Test
    @DisplayName("an evaluation or action context needs a run's state, and an action context in the same run as"
            + " another has the same one")
    void contextsHoldTheRun() {
        Map<String, Object> output = new HashMap<>();
        RunState run = new RunState(Deadline.NONE, new RunScope(), START, Set.of("eu"));

        EngineEvaluationContext evaluation = new EngineEvaluationContext(Map.of(), run);
        EngineActionContext action = new EngineActionContext(Map.of(), output, run);
        EngineActionContext next = new EngineActionContext(action, output);

        assertAll(
                () -> assertSame(run, evaluation.run()),
                () -> assertSame(run, action.run()),
                () -> assertSame(run, next.run()),
                () -> assertSame(run, EngineEvaluationContext.runOf(evaluation)),
                () -> assertSame(run, EngineEvaluationContext.runOf(action)),
                () -> assertSame(run, EngineEvaluationContext.runOf((EvaluationContext) next)),
                () -> assertNullMessage("run must not be null",
                        () -> new EngineEvaluationContext(Map.of(), (RunState) null)),
                () -> assertNullMessage("run must not be null",
                        () -> new EngineActionContext(Map.of(), output, (RunState) null)));
    }
}
