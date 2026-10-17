package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest.Nesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NestedRuns tells a nested run that failed as the check planned from one that failed for another reason")
class NestedRunsTest {

    /** An exception whose getMessage() and getCause() throw, as a broken language's may. */
    private static final class Unreadable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("unreadable message");
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException("unreadable cause");
        }
    }

    /** A nest whose nested run failed with {@code failure}, as the check sees it once the run around it returned. */
    private static Nesting nestThatFailedWith(RuntimeException failure) {
        Nesting nest = new Nesting(() -> {
            throw failure;
        });
        nest.getValue();
        return nest;
    }

    /** A failing nest whose getter the nested run read, and what the getter threw. */
    private static IllegalStateException read(Nesting failing) {
        return assertThrows(IllegalStateException.class, failing::getValue);
    }

    /** An engine whose {@code run()} does {@code run}, and nothing else. */
    @SuppressWarnings("unchecked")
    private static RulesEngine<Map<String, Object>> engineRunning(Runnable run) {
        return (RulesEngine<Map<String, Object>>) Proxy.newProxyInstance(RulesEngine.class.getClassLoader(),
                new Class<?>[] {RulesEngine.class}, (proxy, method, arguments) -> {
                    if (!"run".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    run.run();
                    return Map.of();
                });
    }

    @Test
    @DisplayName("a nested run that failed with what the failing nest's getter threw, as a cause of the engine's"
            + " failure or suppressed on one, failed as planned")
    void failedWithTheGetterException() {
        Nesting failing = Nesting.failing();
        IllegalStateException thrown = read(failing);
        RuleExecutionException suppressedOnACause = new RuleExecutionException("rule 'outer' failed",
                new IllegalStateException("evaluation failed"));
        suppressedOnACause.getCause().addSuppressed(thrown);

        assertAll(
                () -> assertDoesNotThrow(() -> NestedRuns.assertNestedRunFailed(nestThatFailedWith(
                        new RuleExecutionException("rule 'outer' failed", thrown)), failing, "action")),
                () -> assertDoesNotThrow(() -> NestedRuns.assertNestedRunFailed(nestThatFailedWith(
                        new RuleExecutionException("rule 'outer' failed", new IllegalStateException("wrapped",
                                thrown))), failing, "action")),
                () -> assertDoesNotThrow(() -> NestedRuns.assertNestedRunFailed(nestThatFailedWith(
                        suppressedOnACause), failing, "action")));
    }

    @Test
    @DisplayName("a nested run that failed with an exception that carries the planned failure's message, as a language"
            + " that rethrows with the message does, failed as planned, though the getter's own exception is gone")
    void failedWithTheGetterMessage() {
        Nesting failing = Nesting.failing();
        read(failing);
        Nesting nest = nestThatFailedWith(new RuleExecutionException("rule 'outer' failed",
                new IllegalStateException("the language says: " + NestedRuns.PLANNED_FAILURE)));

        assertDoesNotThrow(() -> NestedRuns.assertNestedRunFailed(nest, failing, "condition"));
    }

    @Test
    @DisplayName("a nested run that failed with something other than the engine's failure, or with one that doesn't"
            + " carry what the getter threw, failed for another reason")
    void failedForAnotherReason() {
        Nesting failing = Nesting.failing();
        Nesting notTheEngines = nestThatFailedWith(new IllegalStateException("the language threw this itself"));
        Nesting anotherRule = nestThatFailedWith(new RuleExecutionException("rule 'inner' failed",
                new IllegalStateException("unrelated")));

        AssertionFailedError unread = assertThrows(AssertionFailedError.class,
                () -> NestedRuns.assertNestedRunFailed(notTheEngines, failing, "action"));
        read(failing);
        AssertionFailedError read = assertThrows(AssertionFailedError.class,
                () -> NestedRuns.assertNestedRunFailed(anotherRule, failing, "action"));

        assertAll(
                () -> assertTrue(unread.getMessage().contains("failed for another reason than its nest.value"),
                        unread.getMessage()),
                () -> assertTrue(read.getMessage().contains("doesn't carry what its nest.value threw"),
                        read.getMessage()));
    }

    @Test
    @DisplayName("a nested run that didn't fail, though its getter threw, fails the check; one that never read the"
            + " getter skips it")
    void didNotFail() {
        Nesting failing = Nesting.failing();
        Nesting nest = new Nesting(() -> Map.of("inner", 2));
        nest.getValue();

        assertThrows(org.opentest4j.TestAbortedException.class,
                () -> NestedRuns.assertNestedRunFailed(nest, failing, "condition"));
        read(failing);
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> NestedRuns.assertNestedRunFailed(nest, failing, "condition"));

        assertTrue(failure.getMessage().contains("didn't fail, though a getter its rule read threw"),
                failure.getMessage());
    }

    @Test
    @DisplayName("exceptions that lead back to each other through causes and suppressed exceptions, and ones whose"
            + " cause or message can't be read or is missing, end the search for the planned failure")
    void searchEndsOnLoopsAndUnreadableLinks() {
        Nesting failing = Nesting.failing();
        read(failing);
        IllegalStateException cause = new IllegalStateException("cause");
        RuleExecutionException top = new RuleExecutionException("rule 'outer' failed", cause);
        IllegalStateException back = new IllegalStateException("back", cause);
        top.addSuppressed(back);
        cause.addSuppressed(back);
        back.addSuppressed(top);
        cause.addSuppressed(new IllegalStateException());
        cause.addSuppressed(new Unreadable());

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> NestedRuns.assertNestedRunFailed(nestThatFailedWith(top), failing, "action"));

        assertTrue(failure.getMessage().contains("doesn't carry what its nest.value threw"), failure.getMessage());
    }

    @Test
    @DisplayName("only the first hundred exceptions of a nested run's failure are read for the planned failure")
    void searchReadsAHundredExceptions() {
        Nesting failing = Nesting.failing();
        Throwable chain = read(failing);
        for (int i = 0; i < 98; i++) {
            chain = new IllegalStateException("link " + i, chain);
        }
        Nesting hundredth = nestThatFailedWith(new RuleExecutionException("rule 'outer' failed", chain));
        Nesting oneTooMany = nestThatFailedWith(new RuleExecutionException("rule 'outer' failed",
                new IllegalStateException("one too many", chain)));

        assertDoesNotThrow(() -> NestedRuns.assertNestedRunFailed(hundredth, failing, "action"));
        assertThrows(AssertionFailedError.class,
                () -> NestedRuns.assertNestedRunFailed(oneTooMany, failing, "action"));
    }

    @Test
    @DisplayName("runAround() throws the engine's failure as it was when the run failed before the nest was read, and"
            + " reports a run that failed after the nested run ended, or failed as planned, by what happened in it")
    void runAroundReportsTheRunByWhatHappenedInIt() {
        RuleExecutionException failure = new RuleExecutionException("rule 'outer' failed");
        Nesting failing = Nesting.failing();
        Nesting unread = new Nesting(() -> Map.of());
        Nesting ended = new Nesting(() -> Map.of());
        Nesting failedAsPlanned = new Nesting(() -> {
            throw new RuleExecutionException("rule 'inner' failed", read(failing));
        });
        RulesEngine<Map<String, Object>> failsBeforeTheNest = engineRunning(() -> {
            throw failure;
        });

        RuleExecutionException beforeTheNest = assertThrows(RuleExecutionException.class,
                () -> NestedRuns.runAround(failsBeforeTheNest, unread, "action", null));
        AssertionFailedError afterTheNest = assertThrows(AssertionFailedError.class,
                () -> NestedRuns.runAround(engineRunning(() -> {
                    ended.getValue();
                    throw failure;
                }), ended, "action", failing));
        AssertionFailedError afterThePlannedFailure = assertThrows(AssertionFailedError.class,
                () -> NestedRuns.runAround(engineRunning(() -> {
                    failedAsPlanned.getValue();
                    throw failure;
                }), failedAsPlanned, "condition", failing));

        assertAll(
                () -> assertSame(failure, beforeTheNest),
                () -> assertTrue(afterTheNest.getMessage().contains("ended, so the nested run"),
                        afterTheNest.getMessage()),
                () -> assertTrue(afterThePlannedFailure.getMessage().contains("failed, as the check meant it to"),
                        afterThePlannedFailure.getMessage()),
                () -> assertSame(failure, afterThePlannedFailure.getCause()));
    }
}
