package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A condition or action that returns what would fail its rule, after the run was cancelled, still stops the run, as
 * one that throws does. What was wrong with the result is kept as a suppressed exception on the stop, like what an
 * expression threw, rather than lost. Uses only API that predates the fix.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a wrong result returned after cancellation is kept on the stop")
class WrongResultAfterCancellationTest {

    private static final Duration SHORT = Duration.ofMillis(200);

    /** A language whose expressions wait until the run is cancelled, then return what their text names. */
    private static final class LateLanguage implements ExpressionLanguage {

        @Override
        public String name() {
            return "late";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    if ("yes".equals(expression.text())) {
                        return (evaluation, session) -> true;
                    }
                    Object result = switch (expression.text()) {
                        case "string" -> "approved";
                        case "null" -> null;
                        default -> Boolean.TRUE;
                    };
                    return (evaluation, session) -> {
                        while (!evaluation.isCancelled()) {
                            Thread.onSpinWait();
                        }
                        return result;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> {
                        while (!action.isCancelled()) {
                            Thread.onSpinWait();
                        }
                        return null;
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    private static RulesEngine<Map<String, Object>> engine(Rule rule, RuleListener listener) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new LateLanguage()).runTimeout(SHORT).listener(listener).build();
        engine.load(List.of(rule));
        return engine;
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /** Runs the engine, which must stop, and checks the stop keeps one suppressed exception describing the result. */
    private static void assertStopKeeps(Rule rule, ExpressionKind kind, String message) {
        AtomicReference<RuleExecutionException> onError = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = engine(rule, new RuleListener() {
            @Override
            public void onError(Rule failed, RuleExecutionException error) {
                onError.set(error);
            }
        });
        AtomicReference<RuleExecutionException> thrown = new AtomicReference<>();

        String logs = logsOf(() -> thrown.set(
                assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()))));

        RuleExecutionException stop = thrown.get();
        assertInstanceOf(TimeoutException.class, stop.getCause(), "the run didn't stop");
        assertNull(stop.getRuleName());
        assertEquals(1, stop.getSuppressed().length, "the wrong result isn't kept");
        RuleExecutionException kept = assertInstanceOf(RuleExecutionException.class, stop.getSuppressed()[0]);
        assertEquals(message, kept.getMessage());
        assertEquals(rule.getRuleName(), kept.getRuleName());
        assertEquals(kind, kept.getExpressionKind());
        assertSame(stop, onError.get(), "the rule's callback wasn't closed with the stop");
        assertFalse(logs.contains("ERROR"), logs);
    }

    @Test
    @DisplayName("a condition that returns a string")
    void conditionReturnsAString() {
        assertStopKeeps(rule("text", "string", "unused"), ExpressionKind.CONDITION, "Condition for rule 'text' "
                + "evaluated to a java.lang.String. A condition expression must evaluate to a boolean.");
    }

    @Test
    @DisplayName("a condition that returns null")
    void conditionReturnsNull() {
        assertStopKeeps(rule("nothing", "null", "unused"), ExpressionKind.CONDITION, "Condition for rule 'nothing' "
                + "evaluated to null. A condition expression must evaluate to a boolean.");
    }

    @Test
    @DisplayName("an action that returns null")
    void actionReturnsNull() {
        assertStopKeeps(rule("no-result", "yes", "wait"), ExpressionKind.ACTION, "Action for rule 'no-result' "
                + "returned no result. An action returns ActionResult.done() or ActionResult.set(...).");
    }

    @Test
    @DisplayName("a condition that returns a boolean keeps nothing")
    void rightResultKeepsNothing() {
        RulesEngine<Map<String, Object>> engine = engine(rule("fine", "boolean", "unused"), new RuleListener() {
        });

        RuleExecutionException stop = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertInstanceOf(TimeoutException.class, stop.getCause());
        assertEquals(0, stop.getSuppressed().length);
    }
}
