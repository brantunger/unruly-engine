package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A language that reads facts with {@link FactProperties}, as {@code docs/languages/custom.md} tells one to, fails a
 * rule whose fact's accessor throws with what the accessor threw in the rule's failure, and a nested {@code run()} an
 * accessor starts that fails is logged once, by the nested run, with the rule around it reading
 * {@code a nested run() failed: } and that failure, as it does for a rule written in MVEL.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("a fact's accessor that fails, through FactProperties, is named once in the log")
class FactPropertiesFailureLogTest {

    private static final String INNER_FAILURE = "Failed to evaluate condition for rule 'inner-rule': A "
            + HashMap.class.getName() + " has no property 'total'. A fact's properties are a record's components, a "
            + "bean's getters, or a map's keys.";

    /** A fact whose getter calls a service. */
    public static final class Item {

        private final Runnable service;

        Item(Runnable service) {
            this.service = service;
        }

        public int getPrice() {
            service.run();
            return 5;
        }
    }

    /** A map fact that looks its values up in a service, in {@code containsKey}, or in {@code get}. */
    static final class LookupMap extends AbstractMap<String, Object> {

        private final Runnable service;
        private final boolean onContainsKey;

        LookupMap(Runnable service, boolean onContainsKey) {
            this.service = service;
            this.onContainsKey = onContainsKey;
        }

        @Override
        public boolean containsKey(Object key) {
            if (onContainsKey) {
                service.run();
            }
            return true;
        }

        @Override
        public Object get(Object key) {
            service.run();
            return 5;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            service.run();
            return Set.of();
        }
    }

    /** A lazily loaded collection whose iteration calls a service. */
    static final class LazyLines extends AbstractCollection<Object> {

        private final Runnable service;

        LazyLines(Runnable service) {
            this.service = service;
        }

        @Override
        public Iterator<Object> iterator() {
            service.run();
            return List.<Object>of().iterator();
        }

        @Override
        public int size() {
            return 0;
        }
    }

    private static RulesEngine<Map<String, Object>> engine(String ruleName, ExpressionLanguage language,
                                                           String condition) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(language).build();
        engine.load(List.of(Rule.builder().ruleName(ruleName).condition(condition).action("put seen 1").build()));
        return engine;
    }

    /** An engine whose one rule reads a property a map fact doesn't have, so its run() fails. */
    private static Runnable failingRun() {
        RulesEngine<Map<String, Object>> inner = engine("inner-rule", new ToyExpressionLanguage(), "order.total == 1");
        return () -> inner.run(new FactMap<>(new Fact<>("order", new HashMap<String, Object>())));
    }

    /** Runs a rule whose condition reads {@code item.price} from {@code item}. */
    private static Outcome<RuleExecutionException> readingPrice(Object item) {
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new ToyExpressionLanguage(), "item.price == 5");
        return capture(RuleExecutionException.class, () -> engine.run(new FactMap<>(new Fact<>("item", item))));
    }

    @Test
    @DisplayName("a getter's failure names what it threw in the log and in run()'s failure")
    void aGettersMessageReachesTheRun() {
        Outcome<RuleExecutionException> outcome = readingPrice(new Item(() -> {
            throw new IllegalStateException("price service down");
        }));

        String failure = "Failed to evaluate condition for rule 'outer-rule': Reading 'price' on a "
                + Item.class.getName() + " failed: price service down";
        assertEquals(failure, outcome.thrown().getMessage());
        assertEquals(List.of(failure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("what a getter threw is escaped when it's logged, so its line break can't forge a log line")
    void aGettersMessageIsEscapedWhenLogged() {
        Outcome<RuleExecutionException> outcome = readingPrice(new Item(() -> {
            throw new IllegalStateException("down\nERROR forged");
        }));

        assertEquals(List.of("Failed to evaluate condition for rule 'outer-rule': Reading 'price' on a "
                + Item.class.getName() + " failed: down\\nERROR forged"), outcome.lines("ERROR"), outcome.logs());
        assertFalse(outcome.logs().lines().anyMatch(line -> line.startsWith("ERROR forged")), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a getter", "a map's get()", "a map's containsKey()"})
    @DisplayName("a nested run() an accessor starts that fails is logged once, and the rule around it names it")
    void aNestedRunFromAnAccessorIsLoggedOnce(String accessor) {
        Runnable nested = failingRun();
        Object item = switch (accessor) {
            case "a getter" -> new Item(nested);
            case "a map's get()" -> new LookupMap(nested, false);
            default -> new LookupMap(nested, true);
        };

        Outcome<RuleExecutionException> outcome = readingPrice(item);

        assertEquals("Failed to evaluate condition for rule 'outer-rule': a nested run() failed: " + INNER_FAILURE,
                outcome.thrown().getMessage());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a collection's iteration", "a map's iteration"})
    @DisplayName("a nested run() that toData's iteration starts and that fails is logged once")
    void aNestedRunFromToDataIsLoggedOnce(String accessor) {
        Runnable nested = failingRun();
        Object value = "a map's iteration".equals(accessor) ? new LookupMap(nested, false) : new LazyLines(nested);
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    FactProperties.toData(context.facts(), 2);
                    return ActionResult.done();
                }), "c");

        Outcome<RuleExecutionException> outcome = capture(RuleExecutionException.class,
                () -> engine.run(new FactMap<>(new Fact<>("lines", value))));

        assertEquals("Failed to execute action for rule 'outer-rule': a nested run() failed: " + INNER_FAILURE,
                outcome.thrown().getMessage());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("a getter that wraps its nested run()'s failure in words of its own has those logged")
    void aGettersOwnWordsAroundANestedRunAreLogged() {
        Runnable nested = failingRun();
        Outcome<RuleExecutionException> outcome = readingPrice(new Item(() -> {
            try {
                nested.run();
            } catch (RuleExecutionException e) {
                throw new IllegalStateException("price lookup failed", e);
            }
        }));

        String outerFailure = "Failed to evaluate condition for rule 'outer-rule': Reading 'price' on a "
                + Item.class.getName() + " failed: price lookup failed (after a nested run() failed: " + INNER_FAILURE
                + ")";
        assertEquals(outerFailure, outcome.thrown().getMessage());
        assertEquals(List.of(INNER_FAILURE, outerFailure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("what a getter threw that is too long is shortened once, where the rule's failure is described")
    void aLongMessageIsShortenedOnce() {
        Outcome<RuleExecutionException> outcome = readingPrice(new Item(() -> {
            throw new IllegalStateException("x".repeat(1_100));
        }));

        String read = "Reading 'price' on a " + Item.class.getName() + " failed: " + "x".repeat(1_100);
        String failure = "Failed to evaluate condition for rule 'outer-rule': " + read.substring(0, 1_000) + "... ("
                + (read.length() - 1_000) + " more characters)";
        assertEquals(failure, outcome.thrown().getMessage());
        assertEquals(List.of(failure), outcome.lines("ERROR"), outcome.logs());
    }

    /** Runs a rule whose action reads {@code item.price} with {@code read}, and fails with what that throws. */
    private static Outcome<RuleExecutionException> acting(Object item, Function<Object, Object> read) {
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    read.apply(context.facts().get("item"));
                    return ActionResult.done();
                }), "c");
        return capture(RuleExecutionException.class, () -> engine.run(new FactMap<>(new Fact<>("item", item))));
    }

    @Test
    @DisplayName("a language that puts words of its own around the read's failure names the nested failure once")
    void aLanguagesWordsAroundANestedRunsFailure() {
        Outcome<RuleExecutionException> outcome = acting(new Item(failingRun()), item -> {
            try {
                return FactProperties.read(item, "price");
            } catch (IllegalStateException e) {
                throw new IllegalStateException("Error evaluating item.price: " + e.getMessage(), e);
            }
        });

        String outerFailure = "Failed to execute action for rule 'outer-rule': Error evaluating item.price: Reading "
                + "'price' on a " + Item.class.getName() + " failed (after a nested run() failed: " + INNER_FAILURE
                + ")";
        assertEquals(outerFailure, outcome.thrown().getMessage());
        assertEquals(List.of(INNER_FAILURE, outerFailure), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("a language that reads facts on a thread of its own names a nested failure once in the rule's")
    void aReadOnAnotherThread() {
        Outcome<RuleExecutionException> outcome = acting(new Item(failingRun()), item -> {
            AtomicReference<RuntimeException> failed = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    FactProperties.read(item, "price");
                } catch (RuntimeException e) {
                    failed.set(e);
                }
            });
            reader.start();
            try {
                reader.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw failed.get();
        });

        // The nested run on the reader's thread was the outermost there, and logged its failure; the engine names it
        // once, after the read's own words, and logs it again, which is the limit of a record kept per thread.
        assertEquals("Failed to execute action for rule 'outer-rule': Reading 'price' on a " + Item.class.getName()
                + " failed (after a nested run() failed: " + INNER_FAILURE + ")", outcome.thrown().getMessage());
    }

    @Test
    @DisplayName("read failures a run caught don't push a nested failure's wrapper out before it reaches the engine")
    void manyCaughtReadFailuresKeepTheWrapper() {
        Outcome<RuleExecutionException> outcome = acting(new Item(failingRun()), item -> {
            IllegalStateException nested = assertThrows(IllegalStateException.class,
                    () -> FactProperties.read(item, "price"));
            Item down = new Item(() -> {
                throw new IllegalStateException("price service down");
            });
            for (int i = 0; i <= LoggedFailures.MAX_LOGGED; i++) {
                assertThrows(IllegalStateException.class, () -> FactProperties.read(down, "price"));
            }
            throw nested;
        });

        assertEquals("Failed to execute action for rule 'outer-rule': a nested run() failed: " + INNER_FAILURE,
                outcome.thrown().getMessage());
        assertEquals(List.of(INNER_FAILURE), outcome.lines("ERROR"), outcome.logs());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rethrown", "wrapped", "wrapped with no words", "joined"})
    @DisplayName("a nested run()'s fatal Error out of a getter is logged once, unless the getter wraps it in words of"
            + " its own, and is rethrown")
    void aNestedRunsFatalErrorFromAGetter(String how) {
        OutOfMemoryError oom = new OutOfMemoryError("simulated heap exhaustion");
        RulesEngine<Map<String, Object>> inner = engine("inner-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    throw oom;
                }), "c");
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new ToyExpressionLanguage(), "item.price == 5");
        Item item = new Item(() -> {
            try {
                inner.run(new FactMap<>());
            } catch (OutOfMemoryError e) {
                if ("rethrown".equals(how)) {
                    throw e;
                }
                throw switch (how) {
                    case "wrapped" -> new IllegalStateException("price lookup failed", e);
                    case "wrapped with no words" -> new IllegalStateException(e);
                    default -> new CompletionException(e);
                };
            }
        });

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>(new Fact<>("item", item))));

        assertSame(oom, outcome.thrown());
        String innerFatal = "Failed to execute action for rule 'inner-rule': simulated heap exhaustion";
        List<String> logged = "wrapped".equals(how)
                ? List.of(innerFatal, "Failed to evaluate condition for rule 'outer-rule': Reading 'price' on a "
                        + Item.class.getName() + " failed: price lookup failed (after a nested run() failed: "
                        + "java.lang.OutOfMemoryError: simulated heap exhaustion)")
                : List.of(innerFatal);
        assertEquals(logged, outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("a fatal Error a run logged, thrown again by a getter a later run reads, is named with that read")
    void aFatalErrorLoggedEarlierThrownAgainByAGetter() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated heap exhaustion");
        RulesEngine<Map<String, Object>> first = engine("first-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    throw oom;
                }), "c");
        RulesEngine<Map<String, Object>> second = engine("second-rule", new ToyExpressionLanguage(),
                "item.price == 5");
        Item item = new Item(() -> {
            throw oom;
        });
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    try {
                        first.run(new FactMap<>());
                    } catch (OutOfMemoryError e) {
                        // Handled: the application carries on, and a later run meets the same instance.
                    }
                    second.run(new FactMap<>(new Fact<>("item", item)));
                    return ActionResult.done();
                }), "c");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of("Failed to execute action for rule 'first-rule': simulated heap exhaustion",
                "Failed to evaluate condition for rule 'second-rule': Reading 'price' on a " + Item.class.getName()
                        + " failed: simulated heap exhaustion (caused by java.lang.OutOfMemoryError: simulated heap "
                        + "exhaustion, already logged)"), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("a fatal Error a run nested one level deeper logged, thrown again by a getter a later run reads, is"
            + " named with that read")
    void aFatalErrorLoggedDeeperEarlierThrownAgainByAGetter() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated heap exhaustion");
        RulesEngine<Map<String, Object>> inner = engine("inner-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    throw oom;
                }), "c");
        RulesEngine<Map<String, Object>> first = engine("first-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    inner.run(new FactMap<>());
                    return ActionResult.done();
                }), "c");
        RulesEngine<Map<String, Object>> second = engine("second-rule", new ToyExpressionLanguage(),
                "item.price == 5");
        Item item = new Item(() -> {
            throw oom;
        });
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new StubExpressionLanguage().action(
                (context, session) -> {
                    try {
                        first.run(new FactMap<>());
                    } catch (OutOfMemoryError e) {
                        // Handled: the application carries on, and a later run meets the same instance.
                    }
                    second.run(new FactMap<>(new Fact<>("item", item)));
                    return ActionResult.done();
                }), "c");

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>()));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of("Failed to execute action for rule 'inner-rule': simulated heap exhaustion",
                "Failed to evaluate condition for rule 'second-rule': Reading 'price' on a " + Item.class.getName()
                        + " failed: simulated heap exhaustion (caused by java.lang.OutOfMemoryError: simulated heap "
                        + "exhaustion, already logged)"), outcome.lines("ERROR"), outcome.logs());
    }

    @Test
    @DisplayName("a nested load()'s fatal Error out of a getter is logged once, by the load, and rethrown")
    void aNestedLoadsFatalErrorFromAGetter() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated heap exhaustion");
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().compileAction(expression -> {
                    throw oom;
                })).build();
        RulesEngine<Map<String, Object>> engine = engine("outer-rule", new ToyExpressionLanguage(), "item.price == 5");
        Item item = new Item(() -> inner.load(List.of(Rule.builder().ruleName("inner-rule").condition("c")
                .action("a").build())));

        Outcome<Throwable> outcome = capture(() -> engine.run(new FactMap<>(new Fact<>("item", item))));

        assertSame(oom, outcome.thrown());
        assertEquals(List.of("Action for rule 'inner-rule' failed to compile: simulated heap exhaustion"),
                outcome.lines("ERROR"), outcome.logs());
    }
}
