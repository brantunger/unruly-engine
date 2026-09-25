package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a fact whose value can't be read fails run() before the run starts (#635)")
class ThrowingFactReadTest {

    private final List<String> events = new CopyOnWriteArrayList<>();

    private final AtomicInteger reads = new AtomicInteger();

    /**
     * An {@link Error} that isn't a {@link VirtualMachineError}, so the engine doesn't treat it as fatal.
     */
    private static final class UnreadableValueError extends Error {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        UnreadableValueError(String message) {
            super(message);
        }
    }

    private StatelessRulesEngine<Map<String, Object>> engine() {
        StatelessRulesEngine<Map<String, Object>> engine = TestEngines.firstMatch(HashMap::new,
                builder -> builder.language(new ToyExpressionLanguage()).listener(new RuleListener() {
                    @Override
                    public void beforeRun(RunContext run) {
                        events.add("beforeRun");
                    }

                    @Override
                    public void onRunError(RunContext run, RuntimeException error) {
                        events.add("onRunError");
                    }

                    @Override
                    public void afterRun(RunContext run, RunResult<?> result) {
                        events.add("afterRun");
                    }
                }));
        engine.load(List.of(Rule.builder().ruleName("any").condition("x == null").action("put seen true").build()));
        return engine;
    }

    // A fact named x that counts its reads, and whose value is null, or that throws thrown if it isn't null.
    private FactMap<Object> factThrowing(Throwable thrown) {
        FactMap<Object> facts = new FactMap<>();
        facts.put(new FactReference<>() {
            @Override
            public String getName() {
                return "x";
            }

            @Override
            public Object getValue() {
                reads.incrementAndGet();
                if (thrown == null) {
                    return null;
                }
                if (thrown instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) thrown;
            }
        });
        return facts;
    }

    @Test
    @DisplayName("run() rethrows what getValue() threw unchanged, and listeners see no run (#635)")
    void factReadFailureRethrownBeforeRun() {
        IllegalStateException unreadable = new IllegalStateException("value not ready");
        FactMap<Object> facts = factThrowing(unreadable);
        StatelessRulesEngine<Map<String, Object>> engine = engine();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> engine.run(facts));

        assertSame(unreadable, thrown);
        assertEquals(1, reads.get());
        assertEquals(List.of(), events);
    }

    @Test
    @DisplayName("runWithResult() rethrows what getValue() threw unchanged, and listeners see no run (#635)")
    void factReadFailureRethrownBeforeRunWithResult() {
        IllegalStateException unreadable = new IllegalStateException("value not ready");
        FactMap<Object> facts = factThrowing(unreadable);
        StatelessRulesEngine<Map<String, Object>> engine = engine();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> engine.runWithResult(facts));

        assertSame(unreadable, thrown);
        assertEquals(1, reads.get());
        assertEquals(List.of(), events);
    }

    @Test
    @DisplayName("a run reads a fact's value once (#635)")
    void factReadOncePerRun() {
        FactMap<Object> facts = factThrowing(null);

        assertEquals(Map.of("seen", true), engine().run(facts));
        assertEquals(1, reads.get());
        assertEquals(List.of("beforeRun", "afterRun"), events);
    }

    @Test
    @DisplayName("run() rethrows an Error getValue() threw unchanged, and listeners see no run (#635)")
    void factReadErrorRethrownBeforeRun() {
        UnreadableValueError unreadable = new UnreadableValueError("value not ready");
        FactMap<Object> facts = factThrowing(unreadable);
        StatelessRulesEngine<Map<String, Object>> engine = engine();

        UnreadableValueError thrown = assertThrows(UnreadableValueError.class, () -> engine.run(facts));

        assertSame(unreadable, thrown);
        assertEquals(1, reads.get());
        assertEquals(List.of(), events);
    }
}
