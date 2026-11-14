package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleExecutionException;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mvel2.optimizers.dynamic.DynamicOptimizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A fact can't take the place of a property MVEL reads from a value (see {@link GuardedVariables}): each place MVEL
 * 2.5.4 starts a chain against a value has a test here that fails when a fact is read there, and each place it reads
 * at the root has one that fails when a fact is refused there. They tell the places by MVEL's own frames, so all of
 * them must pass before the MVEL version changes.
 */
@DisplayName("a fact can't take the place of a property MVEL reads from a value")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class PropertyReadGuardTest {

    private static final String AT_RESTART = "' can't be read in place of a property: MVEL reads a name after a value "
            + "in parentheses, a literal, a new object or a null-safe name as the fact of that name, not as the "
            + "value's property";
    private static final String IN_SCOPED_BODY = "' can't be read in place of a property: in a with block or a "
            + "projection's element MVEL reads a name as the fact of that name, not as the value's property, and this "
            + "fact isn't declared. Declare the fact, or read it outside the block";
    // How many runs without the fact come before the run with it: MVEL's JIT compiles a chain again on its 51st run.
    private static final int[] RUNS_BEFORE = {0, 1, 50, 51, 52, 100};

    /** What the rules read their properties from. */
    public static class Order {

        private String note = "n";
        private Order peer;
        private final Order self;

        public Order() {
            this(true);
        }

        Order(boolean nested) {
            self = nested ? new Order(false) : this;
        }

        public String getTier() {
            return "gold";
        }

        public int getQty() {
            return 2;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public Order getPeer() {
            return peer;
        }

        public void setPeer(Order peer) {
            this.peer = peer;
        }

        public Order getSelf() {
            return self;
        }

        public List<String> getItems() {
            return List.of("i0", "i1");
        }

        public String find(Object key) {
            return "found " + key;
        }
    }

    /** Runs another engine's rules from a rule's action. */
    public static class Inner {

        private final RulesEngine<Map<String, Object>> engine;

        Inner(RulesEngine<Map<String, Object>> engine) {
            this.engine = engine;
        }

        public Object run() {
            FactMap<Object> facts = new FactMap<>();
            facts.setValue("limit", 7);
            return engine.run(facts).get("r");
        }
    }

    // A place, the action that reads a property there, and the fact named like what it reads.
    private record Place(String name, String action, String fact, Object value) {

        @Override
        public String toString() {
            return name + ": " + action;
        }
    }

    private static Place place(String name, String action, String fact, Object value) {
        return new Place(name, action, fact, value);
    }

    private static Order noted(String note) {
        Order order = new Order();
        order.setNote(note);
        order.getSelf().setNote(note);
        return order;
    }

    static Stream<Place> restarts() {
        return Stream.of(
                place("a value in parentheses", "output.put('r', (order).tier)", "tier", "other"),
                place("a cast in parentheses", "output.put('r', ((Object) order).tier)", "tier", "other"),
                place("a ternary in parentheses", "output.put('r', (true ? order : null).tier)", "tier", "other"),
                place("a method after parentheses", "output.put('r', (order).getTier())", "getTier", "other"),
                place("an indexed read after parentheses", "output.put('r', (order).items[0])", "items",
                        List.of("other")),
                place("a negated value in parentheses", "output.put('r', !(order).tier.empty)", "tier", ""),
                place("a divisor in parentheses", "output.put('r', 6 / (order).qty)", "qty", 3),
                place("a projection's result", "output.put('r', (level in roles).size)", "size", 9),
                place("a projection's result, a method", "output.put('r', (level in roles).size())", "size", 9),
                place("an inline list", "output.put('r', [1, 2].size)", "size", 9),
                place("an inline map", "output.put('r', ['tier': 'gold'].tier)", "tier", "other"),
                place("an inline map, a method", "output.put('r', ['a': 1].size())", "size", 9),
                place("an inline array", "output.put('r', {1, 2}.length)", "length", 9),
                place("a string literal", "output.put('r', 'gold'.bytes.length)", "bytes", new byte[9]),
                place("a string literal, a method", "output.put('r', 'gold'.equals(order.tier))", "equals", "other"),
                place("a new object", "output.put('r', new java.util.ArrayList().size)", "size", 9),
                place("a new object, imported", "import java.util.ArrayList; output.put('r', new ArrayList().size)",
                        "size", 9),
                place("a null-safe name after parentheses", "output.put('r', (order).?tier)", "tier", "other"),
                place("parentheses in a function's body", "def f(o) { (o).tier }; output.put('r', f(order))", "tier",
                        "other"),
                place("parentheses in a projection's element", "output.put('r', (($).name in roles))", "name",
                        "other"),
                place("parentheses in a with block", "with (order) { note = (order).tier }; output.put('r', "
                        + "order.note)", "tier", "other"),
                place("a projection in a constructor's argument", "import java.util.ArrayList; "
                        + "output.put('r', new ArrayList((($).name in roles)))", "name", "other"));
    }

    static Stream<Place> scopedBodies() {
        return Stream.of(
                place("a projection's element", "output.put('r', (level in roles))", "level", 9),
                place("a projection's element in a constructor's argument", "import java.util.ArrayList; "
                        + "output.put('r', new ArrayList((name in roles)))", "name", "other"),
                place("a with block's value", "with (order) { note = tier }; output.put('r', order.note)", "tier",
                        "other"),
                place("a with block's chain", "with (order) { note = self.note }; output.put('r', order.note)",
                        "self", noted("other")),
                place("a with block's nested argument", "with (order) { note = String.valueOf(String.valueOf(tier)) };"
                        + " output.put('r', order.note)", "tier", "other"),
                place("a with block's inline list", "with (order) { note = '' + [tier] }; output.put('r', order.note)",
                        "tier", "other"),
                place("a with block's ternary", "with (order) { note = true ? tier : 'x' }; output.put('r', "
                        + "order.note)", "tier", "other"),
                place("a with block's assignment target", "with (order) { self.note = 'z' }; output.put('r', "
                        + "order.self.note)", "self", noted("other")),
                place("a with block's statement's argument", "with (order) { setNote(tier) }; output.put('r', "
                        + "order.note)", "tier", "other"),
                place("a with block's second statement's argument", "with (order) { setNote('x'), setNote(note + "
                        + "tier) }; output.put('r', order.note)", "tier", "other"),
                place("an inline with block's value", "order.{ note = tier }; output.put('r', order.note)", "tier",
                        "other"),
                place("an inline with block's assignment target", "order.{ self.note = 'z' }; output.put('r', "
                        + "order.self.note)", "self", noted("other")),
                place("a function called from a with block", "def f() { tier }; with (order) { note = f() }; "
                        + "output.put('r', order.note)", "tier", "other"));
    }

    // The scoped bodies whose rule gives the same output on every run with MVEL's JIT, which the test of the JIT
    // needs of a rule it runs more than 50 times: all but an inline with block's value.
    static Stream<Place> scopedBodiesRunManyTimes() {
        return scopedBodies().filter(place -> !place.action().startsWith("order.{ note = tier }"));
    }

    static Stream<Place> roots() {
        return Stream.of(
                place("a chain from a fact", "output.put('r', order.tier)", "tier", "other"),
                place("a chain of three", "output.put('r', order.self.tier)", "tier", "other"),
                place("a chain after a call", "output.put('r', order.getSelf().tier)", "tier", "other"),
                place("a chain after an index", "output.put('r', orders[0].tier)", "tier", "other"),
                place("a chain after an index after parentheses", "output.put('r', (orders)[0].tier)", "tier",
                        "other"),
                place("a null-safe name within a chain", "output.put('r', order.?self.tier)", "tier", "other"),
                place("a second name after parentheses", "output.put('r', (order).self.tier)", "tier", "other"),
                place("an assignment to a chain", "order.self.note = 'z'; output.put('r', order.self.note)", "self",
                        noted("other")),
                place("a compound assignment to a chain", "order.self.note += 'z'; output.put('r', order.self.note)",
                        "self", noted("other")),
                place("a foreach body", "foreach (o : orders) { output.put('r', o.tier) }", "tier", "other"),
                place("a ternary's branches", "output.put('r', true ? order.tier : order.note)", "tier", "other"),
                place("a static field", "output.put('r', Integer.MAX_VALUE)", "MAX_VALUE", 9));
    }

    // The facts every rule here may read, without the one named like a property.
    private static FactMap<Object> facts() {
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("order", new Order());
        facts.setValue("orders", List.of(new Order()));
        facts.setValue("roles", List.of(Map.of("name", "guest", "level", 1), Map.of("name", "user", "level", 2)));
        return facts;
    }

    private static FactMap<Object> factsWith(Place place) {
        FactMap<Object> facts = facts();
        facts.setValue(place.fact(), place.value());
        return facts;
    }

    // An engine that declares the facts every rule reads, and those named besides.
    private static RulesEngine<Map<String, Object>> engine(String action, String... declared) {
        return engineOf("true", action, declared);
    }

    private static RulesEngine<Map<String, Object>> engineOf(String condition, String action, String... declared) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder
                .<Map<String, Object>>firstMatch(TreeMap::new)
                .fact("order", Order.class).fact("orders", List.class).fact("roles", List.class);
        for (String name : declared) {
            builder.fact(name, Object.class);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(Rule.builder().ruleName("r").condition(condition).action(action).build()));
        return engine;
    }

    // An engine that declares no fact.
    private static RulesEngine<Map<String, Object>> undeclared(String action) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(TreeMap::new)
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action(action).build()));
        return engine;
    }

    // What a run gives: its output's text, or the message of the refusal that failed it.
    private static String outcome(RulesEngine<Map<String, Object>> engine, FactMap<Object> facts) {
        try {
            return "output " + engine.run(facts);
        } catch (RuleExecutionException e) {
            assertInstanceOf(IllegalArgumentException.class, e.getCause(), () -> "failed otherwise: " + e);
            return e.getCause().getMessage();
        }
    }

    private static String refusal(Place place, String reason) {
        return "Fact '" + place.fact() + reason;
    }

    private static String withoutFact(Place place, String... declared) {
        try (RulesEngine<Map<String, Object>> engine = engine(place.action(), declared)) {
            return outcome(engine, facts());
        }
    }

    // The run with the fact is refused when it is the engine's first, and later it is refused or gives what a run
    // without the fact gives: never anything else.
    private static void assertNeverRead(Place place, String reason, String... declared) {
        String expected = withoutFact(place, declared);
        assertTrue(expected.startsWith("output "), () -> "without the fact: " + expected);
        for (int before : RUNS_BEFORE) {
            try (RulesEngine<Map<String, Object>> engine = engine(place.action(), declared)) {
                for (int run = 0; run < before; run++) {
                    assertEquals(expected, outcome(engine, facts()), "a run without the fact");
                }
                String first = outcome(engine, factsWith(place));
                if (before == 0) {
                    assertEquals(refusal(place, reason), first, "the engine's first run");
                } else if (!first.equals(refusal(place, reason))) {
                    assertEquals(expected, first, "after " + before + " runs without the fact");
                }
                String second = outcome(engine, factsWith(place));
                if (!second.equals(refusal(place, reason))) {
                    assertEquals(expected, second, "a second run with the fact, after " + before + " without");
                }
                assertEquals(expected, outcome(engine, facts()), "a run without the fact, after one with it");
            }
        }
    }

    private static void assertReadAtRoot(Place place) {
        String expected = withoutFact(place);
        assertTrue(expected.startsWith("output "), () -> "without the fact: " + expected);
        for (int before : RUNS_BEFORE) {
            try (RulesEngine<Map<String, Object>> engine = engine(place.action())) {
                for (int run = 0; run < before; run++) {
                    outcome(engine, facts());
                }
                assertEquals(expected, outcome(engine, factsWith(place)), "after " + before + " runs without");
            }
        }
    }

    // The block's target is read where the block's statement names it again, with no fact declared, on every run.
    private static void assertTargetRead(String action) {
        for (int before : RUNS_BEFORE) {
            try (RulesEngine<Map<String, Object>> engine = undeclared(action)) {
                for (int run = 0; run <= before; run++) {
                    assertEquals("output {r=x}", outcome(engine, facts()), "run " + run + " of " + before);
                }
            }
        }
    }

    static Stream<String> targetStatements() {
        return Stream.of("with (order) { setNote('x') }; output.put('r', order.note)",
                "with (order) { setNote('y'), setNote('x') }; output.put('r', order.note)",
                "with (order.self) { setNote('x') }; output.put('r', order.self.note)",
                "order.{ setNote('x') }; output.put('r', order.note)",
                "order.self.{ setNote('x') }; output.put('r', order.self.note)",
                "def g() { with (order) { setNote('x') } }; g(); output.put('r', order.note)");
    }

    @Nested
    @DisplayName("with MVEL's reflective optimizer, the engine's default")
    class Reflective {

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#restarts")
        @DisplayName("a name after a value that isn't a name is never read as a fact")
        void restartsRefuseTheFact(Place place) {
            assertNeverRead(place, AT_RESTART);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#restarts")
        @DisplayName("a name after a value that isn't a name is never read as a fact, even a declared one")
        void restartsRefuseADeclaredFact(Place place) {
            assertNeverRead(place, AT_RESTART, place.fact());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#scopedBodies")
        @DisplayName("a name in a projection's element or a with block is never read as a fact nobody declared")
        void scopedBodiesRefuseAnUndeclaredFact(Place place) {
            assertNeverRead(place, IN_SCOPED_BODY);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#scopedBodies")
        @DisplayName("a name in a projection's element or a with block is read as the fact when it is declared")
        void scopedBodiesReadADeclaredFact(Place place) {
            String property = withoutFact(place, place.fact());
            try (RulesEngine<Map<String, Object>> engine = engine(place.action(), place.fact())) {
                String fact = outcome(engine, factsWith(place));
                assertTrue(fact.startsWith("output "), () -> "with the declared fact: " + fact);
                assertNotEquals(property, fact, "the fact's value, not the property's");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#roots")
        @DisplayName("a name MVEL reads from a chain that starts at a fact is the property, and the run isn't refused")
        void rootChainsAreUnchanged(Place place) {
            assertReadAtRoot(place);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#targetStatements")
        @DisplayName("a with block's statement without an assignment reads the block's own target, declared or not")
        void withStatementReadsItsOwnTarget(String action) {
            assertTargetRead(action);
        }
    }

    @Nested
    @DisplayName("with MVEL's JIT the default, as an application may make it")
    class Jit {

        private long timeSpan;

        @BeforeEach
        void jitOn() {
            MvelJit.on();
            // MVEL's JIT compiles a chain on its 51st run only when the 50 before took less than this.
            timeSpan = DynamicOptimizer.timeSpan;
            DynamicOptimizer.timeSpan = Long.MAX_VALUE;
        }

        @AfterEach
        void jitOff() {
            DynamicOptimizer.timeSpan = timeSpan;
            MvelJit.off();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#restarts")
        @DisplayName("a name after a value that isn't a name is never read as a fact")
        void restartsRefuseTheFact(Place place) {
            assertNeverRead(place, AT_RESTART);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#scopedBodiesRunManyTimes")
        @DisplayName("a name in a projection's element or a with block is never read as a fact nobody declared")
        void scopedBodiesRefuseAnUndeclaredFact(Place place) {
            assertNeverRead(place, IN_SCOPED_BODY);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#roots")
        @DisplayName("a name MVEL reads from a chain that starts at a fact is the property, and the run isn't refused")
        void rootChainsAreUnchanged(Place place) {
            assertReadAtRoot(place);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#targetStatements")
        @DisplayName("a with block's statement without an assignment reads the block's own target, declared or not")
        void withStatementReadsItsOwnTarget(String action) {
            assertTargetRead(action);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.brantunger.unruly.mvel.PropertyReadGuardTest#compiledAgain")
        @DisplayName("the run in which the JIT compiles a chain again, its 51st, refuses the fact again")
        void theJitsOwnBuildRefusesTheFact(Place place, String reason) {
            try (RulesEngine<Map<String, Object>> engine = engine(place.action())) {
                String expected = outcome(engine, facts());
                for (int run = 1; run < 50; run++) {
                    assertEquals(expected, outcome(engine, facts()));
                }
                assertEquals(expected, outcome(engine, factsWith(place)), "the 50th run reads what is built");
                assertEquals(refusal(place, reason), outcome(engine, factsWith(place)), "the 51st run");
            }
        }
    }

    static Stream<Arguments> compiledAgain() {
        return Stream.of(
                Arguments.of(place("a value in parentheses", "output.put('r', (order).tier)", "tier", "other"),
                        AT_RESTART),
                Arguments.of(place("a string literal", "output.put('r', 'gold'.bytes.length)", "bytes", new byte[9]),
                        AT_RESTART),
                Arguments.of(place("a with block's chain", "with (order) { note = self.note }; output.put('r', "
                        + "order.note)", "self", noted("other")), IN_SCOPED_BODY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("laterRuns")
    @DisplayName("the name after a null-safe first name is refused in the first run where the value isn't null")
    void nullSafeFirstNameRefusesTheFactOnALaterRun(String action) {
        Place place = place("null-safe", action, "tier", "other");
        for (int nullRuns : new int[] {0, 1, 51}) {
            try (RulesEngine<Map<String, Object>> engine = engine(action)) {
                for (int run = 0; run < nullRuns; run++) {
                    assertEquals("output {r=null}", outcome(engine, facts()), "a run whose peer is null");
                }
                FactMap<Object> facts = factsWith(place);
                ((Order) facts.getValue("order")).setPeer(new Order());
                // With no run before it, MVEL reads the whole chain at once, and the second name isn't a first one.
                assertEquals(nullRuns == 0 ? "output {r=gold}" : refusal(place, AT_RESTART), outcome(engine, facts),
                        "after " + nullRuns + " runs whose peer is null");
                FactMap<Object> without = facts();
                ((Order) without.getValue("order")).setPeer(new Order());
                assertEquals("output {r=gold}", outcome(engine, without), "without the fact");
            }
        }
    }

    static Stream<String> laterRuns() {
        return Stream.of("p = order.peer; output.put('r', ?p.tier)", "output.put('r', (order).?peer.tier)",
                "p = order.peer; output.put('r', String.valueOf(?p.tier))");
    }

    @Test
    @DisplayName("a condition is refused as an action is, and the rule's failure names the rule and has the refusal "
            + "as its cause")
    void conditionsAreRefusedToo() {
        try (RulesEngine<Map<String, Object>> engine = engineOf("(order).tier == 'gold'", "output.put('r', 1)")) {
            FactMap<Object> facts = facts();
            facts.setValue("tier", "other");

            RuleExecutionException failure = assertThrows(RuleExecutionException.class, () -> engine.run(facts));

            assertEquals("Failed to evaluate condition for rule 'r': Fact 'tier" + AT_RESTART, failure.getMessage());
            assertEquals("r", failure.getRuleName());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals(Map.of("r", 1), engine.run(facts()));
        }
    }

    @Test
    @DisplayName("a projection's collection and its filter are read at the root, so a fact nobody declared is read "
            + "there")
    void projectionCollectionAndFilterReadFacts() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(TreeMap::new)
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("($.name in roles if $.level > least).size() == 1")
                .action("output.put('r', ($.name in roles))").build()));
        FactMap<Object> facts = facts();
        facts.setValue("least", 1);

        assertEquals(Map.of("r", List.of("guest", "user")), engine.run(facts));
    }

    @Test
    @DisplayName("a with block's target, the output object and an action's own variables are read where a fact "
            + "nobody declared isn't")
    void targetOutputAndLocalsAreRead() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(TreeMap::new)
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("x = 'local'; with (order) { note "
                + "= x }; with (output) { put('r', (x).length()), put('n', order.note) }").build()));

        RuleExecutionException failure = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        // The fact order, read inside the second block, is refused; the target, x and output before it aren't.
        assertEquals("Failed to execute action for rule 'r': Fact 'order" + IN_SCOPED_BODY, failure.getMessage());
    }

    @Test
    @DisplayName("a block after a value in parentheses has no target of its own, so its statement's name is in its "
            + "body")
    void blockAfterARestartHasNoTarget() {
        String action = "(order).{ tier }; output.put('r', order.note)";
        assertEquals(Map.of("r", "n"), undeclared(action).run(facts()));
        RulesEngine<Map<String, Object>> engine = undeclared(action);
        FactMap<Object> facts = facts();
        facts.setValue("tier", "other");

        RuleExecutionException failure = assertThrows(RuleExecutionException.class, () -> engine.run(facts));

        assertEquals("Failed to execute action for rule 'r': Fact 'tier" + IN_SCOPED_BODY, failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targetsReadInTheBody")
    @DisplayName("a compound assignment in a block, and a nested block's statement, read the block's target in its "
            + "body, so the run fails unless the target is declared")
    void compoundAssignmentReadsItsTargetInTheBody(String action, String expected) {
        RulesEngine<Map<String, Object>> engine = undeclared(action);

        RuleExecutionException failure = assertThrows(RuleExecutionException.class, () -> engine.run(facts()));

        assertEquals("Failed to execute action for rule 'r': Fact 'order" + IN_SCOPED_BODY, failure.getMessage());
        try (RulesEngine<Map<String, Object>> declared = engine(action)) {
            assertEquals(Map.of("r", expected), declared.run(facts()), "with the target declared");
        }
    }

    static Stream<Arguments> targetsReadInTheBody() {
        return Stream.of(
                Arguments.of("with (order) { note += 'x' }; output.put('r', order.note)", "nx"),
                Arguments.of("order.{ note += 'x' }; output.put('r', order.note)", "nx"),
                Arguments.of("with (order) { self.{ setNote('x') } }; output.put('r', order.self.note)", "x"),
                Arguments.of("order.{ self.{ setNote('x') } }; output.put('r', order.self.note)", "x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plainAssignments")
    @DisplayName("a plain assignment in a block doesn't read the block's target, so it runs with no fact declared")
    void plainAssignmentRunsUndeclared(String action, String expected) {
        try (RulesEngine<Map<String, Object>> engine = undeclared(action)) {
            assertEquals(Map.of("r", expected), engine.run(facts()));
            assertEquals(Map.of("r", expected), engine.run(facts()));
        }
    }

    static Stream<Arguments> plainAssignments() {
        return Stream.of(
                Arguments.of("with (order) { note = note + 'x' }; output.put('r', order.note)", "nx"),
                Arguments.of("order.{ note = note + 'x' }; output.put('r', order.note)", "nx"),
                Arguments.of("with (order) { self.{ note = 'x' } }; output.put('r', order.self.note)", "x"),
                Arguments.of("order.{ self.{ note = 'x' } }; output.put('r', order.self.note)", "x"));
    }

    @Test
    @DisplayName("an argument of a call after parentheses is read at the root, so a fact is read there")
    void argumentAfterARestartReadsTheFact() {
        try (RulesEngine<Map<String, Object>> engine = engine("output.put('r', (order).find(key))")) {
            FactMap<Object> facts = facts();
            facts.setValue("key", "k");

            assertEquals(Map.of("r", "found k"), engine.run(facts));
        }
    }

    @Test
    @DisplayName("a run started from a with block reads its own facts at the root, whatever started it")
    void nestedRunIsNotInTheOuterBlock() {
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>firstMatch(TreeMap::new)
                .build();
        inner.load(List.of(Rule.builder().ruleName("in").condition("limit > 1").action("output.put('r', limit + 1)")
                .build()));
        try (RulesEngine<Map<String, Object>> engine = engineOf("true", "with (order) { note = '' + "
                + "inner.run() }; output.put('r', order.note)", "inner")) {
            FactMap<Object> facts = facts();
            facts.setValue("inner", new Inner(inner));

            assertEquals(Map.of("r", "8"), engine.run(facts));
        }
    }

    @ParameterizedTest(name = "{0} runs before")
    @MethodSource("warmRuns")
    @DisplayName("16 runs at once with the fact are each refused or give what a run without it gives")
    void concurrentRunsNeverReadTheFact(int before) throws Exception {
        Map<Place, String> places = new LinkedHashMap<>();
        restarts().limit(3).forEach(place -> places.put(place, AT_RESTART));
        scopedBodies().limit(3).forEach(place -> places.put(place, IN_SCOPED_BODY));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (Map.Entry<Place, String> each : places.entrySet()) {
                Place place = each.getKey();
                String reason = each.getValue();
                String expected = withoutFact(place);
                try (RulesEngine<Map<String, Object>> engine = engine(place.action())) {
                    for (int run = 0; run < before; run++) {
                        outcome(engine, facts());
                    }
                    CyclicBarrier start = new CyclicBarrier(16);
                    List<Future<String>> runs = new ArrayList<>();
                    for (int thread = 0; thread < 16; thread++) {
                        runs.add(pool.submit(() -> {
                            start.await();
                            return outcome(engine, factsWith(place));
                        }));
                    }
                    Map<String, Integer> outcomes = new HashMap<>();
                    for (Future<String> run : runs) {
                        outcomes.merge(run.get(), 1, Integer::sum);
                    }
                    outcomes.remove(expected);
                    Integer refused = outcomes.remove(refusal(place, reason));
                    assertEquals(Map.of(), outcomes, () -> place + ": neither refused nor as without the fact");
                    if (before == 0 && refused == null) {
                        fail(place + ": no run was refused");
                    }
                    assertEquals(expected, outcome(engine, facts()), "a run without the fact, afterwards");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    static Stream<Integer> warmRuns() {
        return Stream.of(0, 20);
    }

    // Deep in a stack, the walk that tells where a name stands has no room and overflows, so nothing tells whether the
    // name is at the root: the run must fail, not read the fact. DeepPropertyReadScenario makes each place's first run
    // in a new JVM, at every depth from the end of a small stack up, with its recursion kept interpreted, so it
    // reaches the same depth each time it looks for the end, and the engine's check of the room compiled in time
    // (-Xbatch), as DeepWalkTest has them: compiled, the check leaves less room than a walk takes.
    @Test
    @DisplayName("deep in a stack, a run whose walk of the stack overflows fails, and none reads the fact")
    void deepRunsNeverReadTheFact(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, DeepPropertyReadScenario.class, "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=exclude," + DeepPropertyReadScenario.class.getName() + "::descend", "-Xbatch")
                .lines().toList();
        String output = String.join("\n", lines);

        // The scenario stops at the first run that read the fact, and says which and where.
        assertEquals("none", lines.stream().filter(line -> line.startsWith(DeepPropertyReadScenario.FIRST_READ))
                .map(line -> line.substring(DeepPropertyReadScenario.FIRST_READ.length())).findFirst().orElse("none"),
                "the first run that read the fact:\n" + output);
        for (String action : DeepPropertyReadScenario.ACTIONS) {
            assertEquals(DeepPropertyReadScenario.REFUSED, value(lines, DeepPropertyReadScenario.AT_THE_TOP, action),
                    "at the top of the stack:\n" + output);
            assertEquals("0", value(lines, DeepPropertyReadScenario.READ, action),
                    "runs that read the fact:\n" + output);
            assertNotEquals("0", value(lines, DeepPropertyReadScenario.WALKS_OVERFLOWED, action),
                    "runs whose walk overflowed:\n" + output);
            assertEquals(DeepPropertyReadScenario.REFUSED, value(lines, DeepPropertyReadScenario.LAST, action),
                    "the last run:\n" + output);
        }
    }

    // What the scenario printed for an action, on the line of the name given.
    private static String value(List<String> lines, String name, String action) {
        String start = name + action + DeepPropertyReadScenario.IS;
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
