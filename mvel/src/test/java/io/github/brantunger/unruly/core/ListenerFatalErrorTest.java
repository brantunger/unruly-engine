package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a fatal Error from a listener still gives every listener a closing callback")
class ListenerFatalErrorTest {

    private final List<String> events = new CopyOnWriteArrayList<>();

    /** A listener that records each callback as {@code name.callback} and throws {@code thrown} from one of them. */
    private RuleListener listener(String name, String throwFrom, Error thrown) {
        return new RuleListener() {
            private void record(String callback) {
                events.add(name + "." + callback);
                if (callback.equals(throwFrom)) {
                    throw thrown;
                }
            }

            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                record("beforeEvaluate");
            }

            @Override
            public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
                record("afterEvaluate");
            }

            @Override
            public void beforeExecute(Rule rule, Object output) {
                record("beforeExecute");
            }

            @Override
            public void afterExecute(Rule rule, Object output) {
                record("afterExecute");
            }

            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                record("onError");
            }
        };
    }

    private static StatefulRulesEngine<Map<String, Object>> engine(String condition, RuleListener... listeners) {
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new,
                builder -> builder.listeners(List.of(listeners)));
        engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action("output.put('k', 1)").build()));
        return engine;
    }

    private static FactStore<Object> x() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        return facts;
    }

    @Test
    @DisplayName("from afterEvaluate: the later listeners still get afterEvaluate, then the error is rethrown")
    void fromAfterCallback() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        StatefulRulesEngine<Map<String, Object>> engine = engine("true",
                listener("L1", null, null), listener("L2", "afterEvaluate", oom), listener("L3", null, null));

        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(x())));
        assertEquals(List.of("L1.beforeEvaluate", "L2.beforeEvaluate", "L3.beforeEvaluate",
                "L1.afterEvaluate", "L2.afterEvaluate", "L3.afterEvaluate"), events);
    }

    @Test
    @DisplayName("from beforeEvaluate: the condition doesn't run and every listener gets onError")
    void fromBeforeEvaluate() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        StatefulRulesEngine<Map<String, Object>> engine = engine("true",
                listener("L1", null, null), listener("L2", "beforeEvaluate", oom), listener("L3", null, null));

        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(x())));
        assertEquals(List.of("L1.beforeEvaluate", "L2.beforeEvaluate", "L3.beforeEvaluate",
                "L1.onError", "L2.onError", "L3.onError"), events);
    }

    @Test
    @DisplayName("from beforeExecute: the action doesn't run and every listener gets onError")
    void fromBeforeExecute() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        StatefulRulesEngine<Map<String, Object>> engine = engine("true",
                listener("L1", null, null), listener("L2", "beforeExecute", oom));

        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(x())));
        assertEquals(List.of("L1.beforeEvaluate", "L2.beforeEvaluate", "L1.afterEvaluate", "L2.afterEvaluate",
                "L1.beforeExecute", "L2.beforeExecute", "L1.onError", "L2.onError"), events);
    }

    @Test
    @DisplayName("from onError: the later listeners still get onError, then the error is rethrown")
    void fromOnError() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        StatefulRulesEngine<Map<String, Object>> engine = engine("x.missing > 1",
                listener("L1", "onError", oom), listener("L2", null, null));

        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> engine.run(x())));
        assertEquals(List.of("L1.beforeEvaluate", "L2.beforeEvaluate", "L1.onError", "L2.onError"), events);
    }

    @Test
    @DisplayName("from beforeEvaluate, then from onError: the error from beforeEvaluate is rethrown")
    void fromBeforeEvaluateThenOnError() {
        OutOfMemoryError fromBefore = new OutOfMemoryError("beforeEvaluate");
        OutOfMemoryError fromOnError = new OutOfMemoryError("onError");
        StatefulRulesEngine<Map<String, Object>> engine = engine("true",
                listener("L1", "beforeEvaluate", fromBefore), listener("L2", "onError", fromOnError));

        assertSame(fromBefore, assertThrows(OutOfMemoryError.class, () -> engine.run(x())));
        assertEquals(List.of("L1.beforeEvaluate", "L2.beforeEvaluate", "L1.onError", "L2.onError"), events);
    }

    @Test
    @DisplayName("from the rule, then from onError: the rule's error is rethrown once every listener got onError")
    void fromRuleThenOnError() {
        OutOfMemoryError fromRule = new OutOfMemoryError("rule");
        OutOfMemoryError fromOnError = new OutOfMemoryError("onError");
        StatefulRulesEngine<Map<String, Object>> engine = engine("bomb.explode()",
                listener("L1", "onError", fromOnError), listener("L2", null, null));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("bomb", new WrappedFatalErrorTest.Bomb(fromRule));

        assertSame(fromRule, assertThrows(OutOfMemoryError.class, () -> engine.run(facts)));
        assertEquals(List.of("L1.beforeEvaluate", "L2.beforeEvaluate", "L1.onError", "L2.onError"), events);
    }

    @Test
    @DisplayName("two fatal errors in one callback: every listener is called and the first error is rethrown")
    void twoFatalErrors() {
        OutOfMemoryError first = new OutOfMemoryError("first");
        OutOfMemoryError second = new OutOfMemoryError("second");
        StatefulRulesEngine<Map<String, Object>> engine = engine("true",
                listener("L1", "afterExecute", first), listener("L2", "afterExecute", second),
                listener("L3", null, null));

        assertSame(first, assertThrows(OutOfMemoryError.class, () -> engine.run(x())));
        assertEquals(List.of("L1.afterExecute", "L2.afterExecute", "L3.afterExecute"),
                events.stream().filter(event -> event.endsWith("afterExecute")).toList());
    }
}
