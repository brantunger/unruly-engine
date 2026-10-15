package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1208: an overflow inside the engine's own call that logs a failure at ERROR, or a warning at WARN, as the first
 * message a JVM logs can overflow deep in a stack, loses the line, not the failure: the caller still gets what it would
 * have got, with the line logged. {@link DeepFirstErrorLogTest} overflows the log call for real, in a new JVM; these
 * tests make it fail with {@link Faults}, in this one, so each way the log call can fail is checked here: an overflow,
 * an {@link InternalError} around one, as the JDK throws in place of an overflow it hits defining a hidden class, which
 * are both caught, and an {@link InternalError} that isn't, which is thrown as it was before.
 */
@DisplayName("an overflow in the call that logs a failure loses the line, not the failure (#1208)")
class FailureLogOverflowTest {

    private static final String FAILED_TO_COMPILE = "Action for rule 'r' failed to compile: thrown by the language";
    private static final String FAILED_TO_EXECUTE = "Failed to execute action for rule 'r': thrown by the action";
    private static final String LISTENER_THREW =
            "Listener threw exception in beforeRun: java.lang.IllegalStateException: thrown by the listener";
    private static final Supplier<Map<String, Object>> OUTPUT = Map::of;

    @AfterEach
    void clearFaults() {
        Faults.clear();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"an overflow", "an InternalError around an overflow"})
    @DisplayName("a load whose log call overflows throws its failure, without its line")
    void loadKeepsItsFailure(String lost) {
        Faults.inject(Faults.Step.FAILURE_LOGGED, 1, overflow(lost));

        Outcome<RuleCompilationException> outcome = capture(RuleCompilationException.class,
                () -> failingToCompile().load(List.of(rule())));

        assertEquals(FAILED_TO_COMPILE, outcome.thrown().getMessage());
        assertEquals(List.of(), outcome.lines("ERROR"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"an overflow", "an InternalError around an overflow"})
    @DisplayName("a run whose log call overflows throws the rule's failure, without its line")
    void runKeepsTheRulesFailure(String lost) {
        RulesEngine<Map<String, Object>> engine = failingToRun();
        engine.load(List.of(rule()));
        Faults.inject(Faults.Step.FAILURE_LOGGED, 1, overflow(lost));

        Outcome<RuleExecutionException> outcome = capture(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        assertEquals(FAILED_TO_EXECUTE, outcome.thrown().getMessage());
        assertEquals(List.of(), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("an InternalError from the log call that isn't an overflow is thrown, as before")
    void otherInternalErrorThrown() {
        InternalError internal = new InternalError("not an overflow");
        Faults.inject(Faults.Step.FAILURE_LOGGED, 1, internal);

        Outcome<InternalError> outcome = capture(InternalError.class,
                () -> failingToCompile().load(List.of(rule())));

        assertSame(internal, outcome.thrown());
    }

    @Test
    @DisplayName("a line lost in a load nested in a run stays lost: the run around it takes it for logged")
    void lostLineInNestedLoadStaysLost() {
        RulesEngine<Map<String, Object>> engine = loadingInAction();
        Faults.inject(Faults.Step.FAILURE_LOGGED, 1, new StackOverflowError());

        Outcome<RuleExecutionException> outcome = capture(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        assertInstanceOf(RuleCompilationException.class, outcome.thrown().getCause());
        assertEquals(List.of(), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("with nothing injected, the same nested load logs its failure once")
    void nestedLineLoggedWithoutAFault() {
        RulesEngine<Map<String, Object>> engine = loadingInAction();

        Outcome<RuleExecutionException> outcome = capture(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        assertEquals(List.of(FAILED_TO_COMPILE), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("with nothing injected, the same load logs its failure, so the tests above lose a line")
    void lineLoggedWithoutAFault() {
        Outcome<RuleCompilationException> outcome = capture(RuleCompilationException.class,
                () -> failingToCompile().load(List.of(rule())));

        assertEquals(List.of(FAILED_TO_COMPILE), outcome.lines("ERROR"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"an overflow", "an InternalError around an overflow"})
    @DisplayName("a run whose warning about a listener overflows goes on, without the warning")
    void runGoesOnWithoutTheWarning(String lost) {
        RulesEngine<Map<String, Object>> engine = listenerThrowing();
        Faults.inject(Faults.Step.FAILURE_LOGGED, 1, overflow(lost));

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertNull(outcome.thrown(), outcome.logs());
        assertEquals(List.of(), outcome.lines("WARN"), outcome.logs());
    }

    @Test
    @DisplayName("an InternalError from the warning's log call that isn't an overflow is thrown, as before")
    void otherInternalErrorFromWarningThrown() {
        RulesEngine<Map<String, Object>> engine = listenerThrowing();
        InternalError internal = new InternalError("not an overflow");
        Faults.inject(Faults.Step.FAILURE_LOGGED, 1, internal);

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertSame(internal, outcome.thrown());
    }

    @Test
    @DisplayName("with nothing injected, the same run logs its warning, so the test above loses a line")
    void warningLoggedWithoutAFault() {
        RulesEngine<Map<String, Object>> engine = listenerThrowing();

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertNull(outcome.thrown(), outcome.logs());
        assertEquals(List.of(LISTENER_THREW), outcome.lines("WARN"), outcome.logs());
    }

    private static Error overflow(String lost) {
        return "an overflow".equals(lost) ? new StackOverflowError() : new InternalError(new StackOverflowError());
    }

    private static Rule rule() {
        return Rule.builder().ruleName("r").condition("c").action("a").build();
    }

    private static RulesEngine<Map<String, Object>> failingToCompile() {
        return RulesEngineBuilder.firstMatch(OUTPUT).language(new StubExpressionLanguage().compileAction(expression -> {
            throw new IllegalStateException("thrown by the language");
        })).build();
    }

    // An engine whose rule's action loads a rule that fails to compile on another engine, with the rule loaded.
    private static RulesEngine<Map<String, Object>> loadingInAction() {
        RulesEngine<Map<String, Object>> nested = failingToCompile();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.firstMatch(OUTPUT)
                .language(new StubExpressionLanguage().compileAction(expression -> (context, session) -> {
                    nested.load(List.of(rule()));
                    return ActionResult.done();
                })).build();
        engine.load(List.of(rule()));
        return engine;
    }

    // An engine with a rule that passes, whose listener throws from beforeRun, which the engine logs at WARN.
    private static RulesEngine<Map<String, Object>> listenerThrowing() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.firstMatch(OUTPUT)
                .language(new StubExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        throw new IllegalStateException("thrown by the listener");
                    }
                }).build();
        engine.load(List.of(rule()));
        return engine;
    }

    private static RulesEngine<Map<String, Object>> failingToRun() {
        return RulesEngineBuilder.firstMatch(OUTPUT).language(new StubExpressionLanguage()
                .action((context, session) -> {
                    throw new IllegalStateException("thrown by the action");
                })).build();
    }
}
