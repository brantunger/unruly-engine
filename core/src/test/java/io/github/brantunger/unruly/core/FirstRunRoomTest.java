package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1066: once the JIT has compiled the run's check of its stack's room, it lets a run through with a third of the
 * room it makes interpreted, and a language's first run deep in a stack can then overflow in a rule, as its first
 * calls make the JDK generate classes. So a run whose rule list uses a language of a class that no run has finished
 * with yet in this JVM checks for {@value StackHeadroom#FIRST_RUN_FRAMES} frames rather than
 * {@value StackHeadroom#FRAMES}, until a run that ran an action of that language returns. The flag is the JVM's, per
 * class of language, so each test uses languages of classes of its own, which no other test runs.
 */
@DisplayName("a run checks for more room until a run has run an action of each language its rules use (#1066)")
class FirstRunRoomTest {

    // What a value kept for a run throws when it's closed.
    private static final String CLOSE_FAILED = "the run's value failed to close";

    @AfterEach
    void clearFaults() {
        Faults.clear();
    }

    @Test
    @DisplayName("a language's first run checks the larger room, before it is numbered or reaches a listener; once a "
            + "run of it has run an action, no run of a rule list of it does")
    void firstRunOnce() {
        List<Long> runsStarted = new CopyOnWriteArrayList<>();
        AbstractRulesEngine<Map<String, Object>> engine = (AbstractRulesEngine<Map<String, Object>>)
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(new FirstLanguage())
                        .listener(new RuleListener() {
                            @Override
                            public void beforeRun(RunContext run) {
                                runsStarted.add(run.runId());
                            }
                        }).build();
        engine.load(List.of(rule("r", "yes", "done")));
        assertTrue(engine.currentRules().firstRun(), "before the language's first run");
        StackOverflowError overflow = new StackOverflowError();
        // The check overflowing, as it does deep in a stack: the run throws it as it is.
        Faults.inject(Faults.Step.FIRST_RUN_ROOM_CHECKING, 1, overflow);
        assertSame(overflow, assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>())));
        assertEquals(List.of(), runsStarted, "runs a listener was told of");
        assertTrue(engine.currentRules().firstRun(), "after a run the check refused");

        assertEquals(Map.of(), engine.run(new FactMap<>()));

        // The refused run took no number: the next run is the engine's first.
        assertEquals(List.of(1L), runsStarted, "runs a listener was told of");

        assertFalse(engine.currentRules().firstRun(), "after it");
        // Not reached any more: the run doesn't throw.
        Faults.inject(Faults.Step.FIRST_RUN_ROOM_CHECKING, 1, new StackOverflowError());
        assertEquals(Map.of(), engine.run(new FactMap<>()));
        AbstractRulesEngine<Map<String, Object>> other = engine(new FirstLanguage());
        other.load(List.of(rule("s", "yes", "done")));
        assertFalse(other.currentRules().firstRun(), "another engine's rule list of the language");
    }

    @Test
    @DisplayName("a language's first run is its own: one of another language doesn't end it, and a run of a rule list "
            + "of both ends it only for the languages whose actions it ran")
    void perLanguage() {
        AbstractRulesEngine<Map<String, Object>> ran = engine(new RanLanguage());
        ran.load(List.of(rule("r", "yes", "done")));
        ran.run(new FactMap<>());
        assertFalse(ran.currentRules().firstRun(), "after the first language's run");

        AbstractRulesEngine<Map<String, Object>> engine = twoLanguages(new RanLanguage(), new NotRunLanguage());
        engine.load(List.of(rule("other", "yes", "done").toBuilder().language(NotRunLanguage.NAME).build()));
        assertTrue(engine.currentRules().firstRun(), "a rule list of a language that hasn't run");
        // Only the first language's rule matches, so only its action runs.
        engine.load(List.of(rule("first", "yes", "done"),
                rule("other", "no", "done").toBuilder().language(NotRunLanguage.NAME).build()));
        assertTrue(engine.currentRules().firstRun(), "a rule list of both");
        Faults.inject(Faults.Step.FIRST_RUN_ROOM_CHECKING, 1, new StackOverflowError());
        assertThrows(StackOverflowError.class, () -> engine.run(new FactMap<>()), "a run of a rule list of both");

        engine.run(new FactMap<>());

        assertTrue(engine.currentRules().firstRun(), "a rule list of both, after a run of only the first's action");
        engine.load(List.of(rule("other", "yes", "done").toBuilder().language(NotRunLanguage.NAME).build()));
        assertTrue(engine.currentRules().firstRun(), "a rule list of the second language, after that run");

        engine.load(List.of(rule("first", "no", "done"),
                rule("other", "yes", "done").toBuilder().language(NotRunLanguage.NAME).build()));
        engine.run(new FactMap<>());

        assertFalse(engine.currentRules().firstRun(), "a rule list of both, after a run that ran the second's action");
        engine.load(List.of(rule("other", "yes", "done").toBuilder().language(NotRunLanguage.NAME).build()));
        assertFalse(engine.currentRules().firstRun(), "a rule list of the second language, after that run");
    }

    @Test
    @DisplayName("a run of a rule list of two languages that haven't run, that runs the actions of one, ends the first "
            + "run of that one alone")
    void onlyFiredLanguageEnds() {
        AbstractRulesEngine<Map<String, Object>> engine = twoLanguages(new FiredLanguage(), new UnfiredLanguage());
        engine.load(List.of(rule("fired", "yes", "done"),
                rule("unfired", "no", "done").toBuilder().language(UnfiredLanguage.NAME).build()));

        engine.run(new FactMap<>());

        assertTrue(engine.currentRules().firstRun(), "a rule list of both");
        engine.load(List.of(rule("fired", "yes", "done")));
        assertFalse(engine.currentRules().firstRun(), "a rule list of the language whose action ran");
        engine.load(List.of(rule("unfired", "yes", "done").toBuilder().language(UnfiredLanguage.NAME).build()));
        assertTrue(engine.currentRules().firstRun(), "a rule list of the language whose action didn't run");
    }

    @Test
    @DisplayName("a run that fails, or runs no action, isn't a language's first run: the next run needs the room too")
    void failedOrNoActionRunsDontCount() {
        AbstractRulesEngine<Map<String, Object>> engine = engine(new FailingLanguage());
        engine.load(List.of(rule("fails", "yes", "throw")));

        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        assertTrue(engine.currentRules().firstRun(), "after a run that failed");

        engine.load(List.of(rule("unmatched", "no", "done")));
        assertNull(engine.run(new FactMap<>()));
        assertTrue(engine.currentRules().firstRun(), "after a run that matched no rule");

        engine.load(List.of(rule("passes", "yes", "done")));
        engine.run(new FactMap<>());
        assertFalse(engine.currentRules().firstRun(), "after a run that ran an action");
    }

    @Test
    @DisplayName("a run that ran an action and then failed in a later rule isn't a language's first run")
    void actionThenFailingRuleDoesntCount() {
        AbstractRulesEngine<Map<String, Object>> engine = engine(new FiredThenFailedLanguage());
        engine.load(List.of(rule("fires", "yes", "done").toBuilder().priority(2).build(),
                rule("fails", "yes", "throw").toBuilder().priority(1).build()));

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("fails", thrown.getRuleName(), "the rule that failed, after the other's action ran");
        assertTrue(engine.currentRules().firstRun(), "after a run that ran an action, then failed");
    }

    @Test
    @DisplayName("a run that ran an action and then failed as it ended isn't a language's first run")
    void actionThenFailingEndDoesntCount() {
        AbstractRulesEngine<Map<String, Object>> engine = engine(new FailedEndLanguage());
        engine.load(List.of(rule("fires", "close", "done")));

        // A value kept for the run whose close() throws a fatal error, which the run throws as it ends.
        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        assertEquals(CLOSE_FAILED, thrown.getMessage());
        assertTrue(engine.currentRules().firstRun(), "after a run that ran an action, then failed as it ended");
    }

    @Test
    @DisplayName("a run that reads rules a reload then closes, and borrows from the list that replaced them, records "
            + "the languages it ran for that list")
    void reloadedListRecorded() {
        RuleSet closed = TestRuleSets.ruleSet(List.of(), Map.of()).build();
        closed.retire();
        AbstractRulesEngine<Map<String, Object>> engine = reloadedEngine(new ReloadedLanguage(),
                new AtomicReference<>(closed));
        engine.load(List.of(rule("r", "yes", "done")));
        assertTrue(engine.currentRules().firstRun(), "the list the run borrows from");

        engine.run(new FactMap<>());

        assertFalse(engine.currentRules().firstRun(), "after a run that read a closed list first");
    }

    @Test
    @DisplayName("a run that reads rules a reload then closes, which need the room too, records the languages it ran "
            + "for the list that replaced them")
    void reloadedFirstRunListRecorded() {
        AtomicReference<RuleSet> closed = new AtomicReference<>();
        AbstractRulesEngine<Map<String, Object>> engine = reloadedEngine(new ReplacedLanguage(), closed);
        engine.load(List.of(rule("replaced", "yes", "done")));
        closed.set(engine.currentRules());
        // The reload retires and closes the rules read above, as no run uses them.
        engine.load(List.of(rule("r", "yes", "done")));
        assertTrue(closed.get().firstRun(), "the closed list the run reads first");

        engine.run(new FactMap<>());

        assertFalse(engine.currentRules().firstRun(), "after a run that read a closed list first");
    }

    @Test
    @DisplayName("validate() doesn't end a language's first run, nor need its room")
    void validateDoesntCount() {
        AbstractRulesEngine<Map<String, Object>> engine = engine(new ValidatedLanguage());
        // Not reached: validate() checks no more room for a language's first run.
        Faults.inject(Faults.Step.FIRST_RUN_ROOM_CHECKING, 1, new StackOverflowError());
        assertEquals(List.of(), engine.validate(List.of(rule("r", "yes", "done"))));

        engine.load(List.of(rule("r", "yes", "done")));
        assertTrue(engine.currentRules().firstRun(), "after validate()");
    }

    @Test
    @DisplayName("a rule list without rules, checked against the default language, never needs the room")
    void emptyRuleList() {
        AbstractRulesEngine<Map<String, Object>> engine = engine(new EmptyListLanguage());
        engine.load(List.of());
        assertFalse(engine.currentRules().firstRun(), "a rule list without rules");
        Faults.inject(Faults.Step.FIRST_RUN_ROOM_CHECKING, 1, new StackOverflowError());

        assertNull(engine.run(new FactMap<>()));

        engine.load(List.of(rule("r", "yes", "done")));
        assertTrue(engine.currentRules().firstRun(), "a rule list of the default language, after runs without rules");
    }

    @Test
    @DisplayName("recording a language the rule list doesn't use records nothing")
    void unusedLanguageIgnored() {
        AbstractRulesEngine<Map<String, Object>> engine = engine(new IgnoredLanguage());
        engine.load(List.of(rule("r", "yes", "done")));

        engine.currentRules().ran(List.of("unused"));

        assertTrue(engine.currentRules().firstRun(), "after recording a language the rules don't use");
    }

    private static AbstractRulesEngine<Map<String, Object>> engine(ExpressionLanguage language) {
        return (AbstractRulesEngine<Map<String, Object>>) RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(language).build();
    }

    // An engine whose second reading of its rules, the first run's after the test's own, finds the closed rule list
    // held in closed, as a run finds the rules a reload replaced, and whose other readings find the rules it loaded.
    private static AbstractRulesEngine<Map<String, Object>> reloadedEngine(ExpressionLanguage language,
                                                                          AtomicReference<RuleSet> closed) {
        AtomicInteger reads = new AtomicInteger();
        EngineConfiguration<Map<String, Object>> configuration = TestConfigurations.engineConfiguration(
                Map.of(language.name(), language)).build();
        return new AbstractRulesEngine<>(HashMap::new, configuration) {
            @Override
            RuleSet currentRules() {
                return reads.getAndIncrement() == 1 ? closed.get() : super.currentRules();
            }

            @Override
            String matchPolicy() {
                return "firstMatch";
            }
        };
    }

    // An engine of two languages, the first the default.
    private static AbstractRulesEngine<Map<String, Object>> twoLanguages(TestLanguage first, TestLanguage second) {
        return (AbstractRulesEngine<Map<String, Object>>) RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(first).language(second).defaultLanguage(first.name()).build();
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /**
     * A language whose conditions {@code yes} and {@code no} are true and false, and whose action {@code done} does
     * nothing and {@code throw} throws. Each test extends it with a class of its own.
     */
    private abstract static class TestLanguage implements ExpressionLanguage {

        private final String name;

        TestLanguage(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    if ("close".equals(expression.text())) {
                        return (evaluation, session) -> {
                            AutoCloseable failing = () -> {
                                throw new OutOfMemoryError(CLOSE_FAILED);
                            };
                            evaluation.runScopedClosing(CLOSE_FAILED, () -> failing);
                            return true;
                        };
                    }
                    boolean value = "yes".equals(expression.text());
                    return (evaluation, session) -> value;
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    if ("throw".equals(expression.text())) {
                        return (action, session) -> {
                            throw new IllegalStateException("the action failed");
                        };
                    }
                    return (action, session) -> ActionResult.done();
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    private static final class FirstLanguage extends TestLanguage {
        FirstLanguage() {
            super("first");
        }
    }

    private static final class RanLanguage extends TestLanguage {
        static final String NAME = "ran";

        RanLanguage() {
            super(NAME);
        }
    }

    private static final class NotRunLanguage extends TestLanguage {
        static final String NAME = "notRun";

        NotRunLanguage() {
            super(NAME);
        }
    }

    private static final class FiredThenFailedLanguage extends TestLanguage {
        FiredThenFailedLanguage() {
            super("firedThenFailed");
        }
    }

    private static final class FailedEndLanguage extends TestLanguage {
        FailedEndLanguage() {
            super("failedEnd");
        }
    }

    private static final class ReloadedLanguage extends TestLanguage {
        ReloadedLanguage() {
            super("reloaded");
        }
    }

    private static final class ReplacedLanguage extends TestLanguage {
        ReplacedLanguage() {
            super("replaced");
        }
    }

    private static final class FiredLanguage extends TestLanguage {
        FiredLanguage() {
            super("fired");
        }
    }

    private static final class UnfiredLanguage extends TestLanguage {
        static final String NAME = "unfired";

        UnfiredLanguage() {
            super(NAME);
        }
    }

    private static final class FailingLanguage extends TestLanguage {
        FailingLanguage() {
            super("failing");
        }
    }

    private static final class EmptyListLanguage extends TestLanguage {
        EmptyListLanguage() {
            super("emptyList");
        }
    }

    private static final class IgnoredLanguage extends TestLanguage {
        IgnoredLanguage() {
            super("ignored");
        }
    }

    private static final class ValidatedLanguage extends TestLanguage {
        ValidatedLanguage() {
            super("validated");
        }
    }
}
