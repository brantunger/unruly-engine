package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the engine promises for rules in any expression language. A language's test extends this class and supplies
 * the expressions each check needs, written in that language:
 *
 * {@snippet :
 * class MyLanguageContractTest extends ExpressionLanguageContractTest {
 *     protected ExpressionLanguage language() { return new MyLanguage(); }
 *     protected String alwaysTrue() { return "true"; }
 *     protected String factEquals(String fact, int value) { return fact + " == " + value; }
 *     // ... one method for each expression the checks need
 * }
 * }
 *
 * <p>
 * Each check runs rules through an engine, so a language passes only if it works with the engine as users will run
 * it. On the module path, the package of the extending test must be open to {@code org.junit.platform.commons}.
 * </p>
 *
 * <p>
 * The checks compare numbers in the output by value, so a language whose whole numbers are {@code Long}s, as CEL's
 * are, or {@code Double}s, as JsonLogic's and JavaScript's are, needs no conversion to pass on that count. What a
 * language reads is a separate promise, and every language here makes it, whatever its own numbers are: a rule
 * written for {@code 1} is also run against a {@code Long}, a {@code Short} and a {@code BigDecimal} fact, and must
 * fire for all three and not for a {@code 2L} one. A language that compares whole numbers by type opts out of that
 * one check with {@link #comparesWholeNumbersByValue()}.
 * </p>
 *
 * @see <a href=
 * "https://github.com/brantunger/unruly-engine/blob/main/docs/languages/custom.md#-testing-with-the-contract-kit">
 * Testing with the contract kit</a>
 */
// A test class: each check makes several assertions, and their failure messages show the values compared. The engines
// the checks build are discarded with the check, except where a check is about closing.
@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.CloseResource"})
public abstract class ExpressionLanguageContractTest {

    /** The output key the checks' actions put a fact's value under. */
    private static final String SEEN = "seen";

    /** Creates the test. JUnit creates an instance of the extending class for each check. */
    protected ExpressionLanguageContractTest() {
    }

    /**
     * Returns the language under test.
     *
     * @return A new instance of the language
     */
    protected abstract ExpressionLanguage language();

    /**
     * Returns a condition that is always true.
     *
     * @return The condition
     */
    protected abstract String alwaysTrue();

    /**
     * Returns a condition that is true when a whole-number fact equals a value.
     *
     * @param fact  The fact's name
     * @param value The value to compare it with
     * @return The condition
     */
    protected abstract String factEquals(String fact, int value);

    /**
     * Whether this language compares whole numbers by value, so that a rule written for {@code 1} fires for a
     * {@code Long}, a {@code Short} and a {@code BigDecimal} fact that holds one, and doesn't fire for a {@code 2L}
     * fact. By default, {@code true}. A strongly typed language that deliberately compares them by type returns
     * {@code false}, which skips the whole check: there's no opting out of one of the three types.
     *
     * @return Whether whole numbers of different types compare equal
     */
    protected boolean comparesWholeNumbersByValue() {
        return true;
    }

    /**
     * Returns a condition whose result is a fact's value, whatever its type.
     *
     * @param fact The fact's name
     * @return The condition
     */
    protected abstract String factValue(String fact);

    /**
     * Returns a condition that assigns a value to a fact.
     *
     * @param fact  The fact's name
     * @param value The value to assign
     * @return The condition
     */
    protected abstract String assignment(String fact, int value);

    /**
     * Returns an action that puts a fact's value into the output map, by changing the output or by returning the
     * property in an {@link io.github.brantunger.unruly.api.language.ActionResult}.
     *
     * @param key  The key to put the value under
     * @param fact The fact's name
     * @return The action
     */
    protected abstract String putFact(String key, String fact);

    /**
     * Returns an action that declares a variable.
     *
     * @param name  The variable's name
     * @param value The value it holds
     * @return The action, or {@code null} if the language's actions have no variables, which skips the check
     */
    protected abstract @Nullable String declareVariable(String name, int value);

    /**
     * Returns an action that assigns a new object to the output.
     *
     * @return The action, or {@code null} if the language's actions can't assign anything, as for a language that
     *         returns its results as properties, which skips the check
     */
    protected abstract @Nullable String reassignOutput();

    /**
     * Returns a condition with a syntax error.
     *
     * @return The condition
     */
    protected abstract String syntaxError();

    /**
     * Returns an action with a syntax error, one this language's own compiler rejects. By default, whatever
     * {@link #syntaxError()} returns, which is a broken action in most languages. Override it when that text is a
     * valid action in this one: the language then compiles it without complaint, and the check fails with nothing
     * but JUnit's "expected {@code RuleCompilationException} to be thrown", naming neither the expression nor the
     * hook that supplied it.
     *
     * <p>
     * The action must not be blank, and the check fails when it is: the engine rejects a blank action itself, naming
     * the rule, before the language is asked to compile anything, so a blank one would pass the check without the
     * language's compiler ever running. Unlike the {@code @Nullable} hooks around it, this one can't be skipped by
     * returning {@code null}.
     * </p>
     *
     * @return The action, never blank
     */
    protected String actionSyntaxError() {
        return syntaxError();
    }

    /**
     * Returns a fact name that rules in this language can't refer to.
     *
     * @return The name, or {@code null} if every name is accepted
     */
    protected abstract @Nullable String unusableFactName();

    /**
     * Returns a condition that compares one property of a fact with a value, as {@code applicant.creditScore == 750}
     * does in MVEL. The same condition is run against a record fact, a JavaBean fact and a {@link Map} fact.
     *
     * @param fact     The fact's name
     * @param property The property to read
     * @param value    The value it must equal
     * @return The condition
     */
    protected abstract String factProperty(String fact, String property, int value);

    /**
     * Returns a condition that reads a property the fact doesn't have, which the language must reject when it loads
     * or runs the rule, rather than evaluate to {@code false}. It's usually {@link #factProperty} with the same
     * arguments.
     *
     * <p>
     * A language whose own semantics read a missing property as {@code null} or undefined, as JsonLogic reads a
     * {@code var} that isn't there, returns {@code null}. Such a language can't tell a misspelled property from an
     * absent one, whether it reads the fact directly or as the map {@code FactProperties.toData} makes of it, so the
     * check would fail it for being faithful to its own rules.
     * </p>
     *
     * @param fact     The fact's name
     * @param property The property the fact doesn't have
     * @param value    The value to compare it with
     * @return The condition, or {@code null} if the language reads a missing property as {@code null} or undefined,
     *         which skips the check
     */
    protected abstract @Nullable String missingFactProperty(String fact, String property, int value);

    /** The fact name and property the property checks use. */
    private static final String APPLICANT = "applicant";
    private static final String CREDIT_SCORE = "creditScore";

    /**
     * An applicant, so a language's tests can run a rule against a record fact. It's a record because a record is
     * what languages most often get wrong: its component is a method, so a language that looks only for a getter or
     * a field reads nothing.
     *
     * @param creditScore The applicant's credit score, the property the contract test reads
     */
    public record Applicant(int creditScore) {
    }

    /**
     * An applicant as a JavaBean, so a language's tests can run a rule against a fact whose property is a getter.
     */
    public static final class ApplicantBean {

        private final int creditScore;

        /**
         * Creates the applicant.
         *
         * @param creditScore The applicant's credit score, the property the contract test reads
         */
        public ApplicantBean(int creditScore) {
            this.creditScore = creditScore;
        }

        /**
         * Returns the applicant's credit score.
         *
         * @return The credit score
         */
        public int getCreditScore() {
            return creditScore;
        }
    }

    private Rule rule(String name, int priority, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action(action)
                .language(language().name()).build();
    }

    private static RulesEngine<Map<String, Object>> engine(ExpressionLanguage language) {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(language).build();
    }

    private RulesEngine<Map<String, Object>> engine() {
        return engine(language());
    }

    private RulesEngine<Map<String, Object>> engine(Rule... rules) {
        RulesEngine<Map<String, Object>> engine = engine();
        engine.load(List.of(rules));
        return engine;
    }

    /**
     * Asserts that a run's output, or a list of outputs, is what was expected, comparing numbers by value: {@code 1},
     * {@code 1L} and {@code 1.0} are the same output. Everything else is compared with {@code equals}.
     *
     * @param expected The expected output
     * @param actual   The output the engine returned
     */
    private static void assertSameOutput(@Nullable Object expected, @Nullable Object actual) {
        if (!sameValue(expected, actual)) {
            fail("expected: <" + expected + "> but was: <" + actual + ">");
        }
    }

    private static boolean sameValue(@Nullable Object expected, @Nullable Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return sameNumber(left, right);
        }
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            return left.keySet().equals(right.keySet())
                    && left.keySet().stream().allMatch(key -> sameValue(left.get(key), right.get(key)));
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!sameValue(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private static boolean sameNumber(Number left, Number right) {
        try {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        } catch (NumberFormatException e) {
            // NaN or an infinity, which have no BigDecimal form.
            return left.toString().equals(right.toString());
        }
    }

    @Test
    @DisplayName("a condition reads the facts, and its rule fires only when the condition is true")
    void conditionReadsFacts() {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x")));

        assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
        assertNull(engine.run(new FactMap<>(new Fact<>("x", 2))));
    }

    @Test
    @DisplayName("a condition written for a whole number reads a Long, a Short and a BigDecimal fact")
    void conditionReadsWholeNumbers() {
        assumeTrue(comparesWholeNumbersByValue(), "the language compares whole numbers by type");
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x")));

        // A language whose equality is Objects.equals passes the check above, where every fact is an Integer, and
        // then never fires on a fact that came from JSON, a database or a long id.
        for (Object one : List.of(1L, (short) 1, BigDecimal.ONE)) {
            assertNotNull(engine.run(new FactMap<>(new Fact<>("x", one))),
                    "the rule didn't fire for a " + one.getClass().getSimpleName() + " fact");
        }
        // And it is by value: a language whose coercion falls through to true whenever the runtime types differ
        // fires for every whole number there is.
        assertNull(engine.run(new FactMap<>(new Fact<>("x", 2L))), "the rule fired for a 2L fact");
    }

    @Test
    @DisplayName("a condition must evaluate to a boolean: null, a string or a number fails the rule")
    void conditionMustBeBoolean() {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factValue("x"), putFact(SEEN, "x")));

        assertEquals(Map.of(SEEN, true), engine.run(new FactMap<>(new Fact<>("x", true))));
        for (Object notBoolean : Arrays.asList(null, "true", 1)) {
            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>(new Fact<>("x", notBoolean))),
                    String.valueOf(notBoolean));
        }
    }

    @Test
    @DisplayName("a condition that assigns to a fact is rejected by load")
    void conditionAssignmentRejected() {
        RulesEngine<Map<String, Object>> engine = engine();
        List<Rule> rules = List.of(rule("r", 1, assignment("x", 2), putFact(SEEN, "x")));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertTrue(ex.getMessage().startsWith("Condition for rule 'r' "), ex.getMessage());
    }

    @Test
    @DisplayName("an action can't replace the output object")
    void outputNotReplaceable() {
        String reassign = reassignOutput();
        assumeTrue(reassign != null, "the language's actions can't assign the output");
        // A language may reject the assignment when compiling or when running.
        assertThrows(UnrulyException.class,
                () -> engine(rule("r", 1, alwaysTrue(), reassign)).run(new FactMap<>(new Fact<>("x", 1))));
    }

    @Test
    @DisplayName("a variable an action declares doesn't change the facts later actions see")
    void actionVariablesStayLocal() {
        String declare = declareVariable("x", 2);
        assumeTrue(declare != null, "the language's actions have no variables");
        RulesEngine<Map<String, Object>> engine = engine(
                rule("declares", 2, alwaysTrue(), declare),
                rule("reads", 1, alwaysTrue(), putFact(SEEN, "x")));

        assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
        assertSameOutput(Map.of(SEEN, 3), engine.run(new FactMap<>(new Fact<>("x", 3))));
    }

    @Test
    @DisplayName("a syntax error is reported by load, naming the rule and its condition")
    void syntaxErrorAtLoad() {
        RulesEngine<Map<String, Object>> engine = engine();
        List<Rule> rules = List.of(rule("r", 1, syntaxError(), putFact(SEEN, "x")));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("r", ex.getRuleName());
        assertEquals(ExpressionKind.CONDITION, ex.getExpressionKind());
        assertTrue(ex.getMessage().startsWith("Condition for rule 'r' "), ex.getMessage());
    }

    @Test
    @DisplayName("a syntax error in an action is reported by load, naming the rule and its action")
    void syntaxErrorInActionAtLoad() {
        String action = actionSyntaxError();
        // The engine rejects a blank action before the language is asked to compile it, so a blank one would make
        // this check pass without the language's compiler running at all.
        assertFalse(action.isBlank(), "actionSyntaxError() must return an action the language itself rejects");
        RulesEngine<Map<String, Object>> engine = engine();
        List<Rule> rules = List.of(rule("r", 1, alwaysTrue(), action));

        // A language that compiles its actions on first use loads this rule without a word, and fails it in
        // production instead, one run at a time.
        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

        assertEquals("r", ex.getRuleName());
        assertEquals(ExpressionKind.ACTION, ex.getExpressionKind());
    }

    @Test
    @DisplayName("a fact name the language can't refer to is rejected by run()")
    void unusableFactNameRejected() {
        String name = unusableFactName();
        assumeTrue(name != null, "the language accepts every fact name");
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, alwaysTrue(), putFact(SEEN, "x")));

        assertThrows(IllegalArgumentException.class, () -> engine.run(new FactMap<>(new Fact<>(name, 1))));
    }

    @Test
    @DisplayName("a condition reads a property of a record fact, a JavaBean fact and a map fact the same way")
    void conditionReadsProperties() {
        RulesEngine<Map<String, Object>> engine =
                engine(rule("r", 1, factProperty(APPLICANT, CREDIT_SCORE, 750), putFact(SEEN, APPLICANT)));

        assertNotNull(engine.run(new FactMap<>(new Fact<>(APPLICANT, new Applicant(750)))),
                "a record fact's component wasn't read");
        assertNotNull(engine.run(new FactMap<>(new Fact<>(APPLICANT, new ApplicantBean(750)))),
                "a JavaBean fact's getter wasn't read");
        assertNotNull(engine.run(new FactMap<>(new Fact<>(APPLICANT, Map.of(CREDIT_SCORE, 750)))),
                "a map fact's key wasn't read");
        assertNull(engine.run(new FactMap<>(new Fact<>(APPLICANT, new Applicant(700)))),
                "the record's component was read as 750");
        assertNull(engine.run(new FactMap<>(new Fact<>(APPLICANT, new ApplicantBean(700)))),
                "the JavaBean's getter was read as 750");
        assertNull(engine.run(new FactMap<>(new Fact<>(APPLICANT, Map.of(CREDIT_SCORE, 700)))),
                "the map's key was read as 750");
    }

    @Test
    @DisplayName("a property the fact doesn't have fails the run, rather than being false or undefined")
    void missingPropertyFailsTheRun() {
        String condition = missingFactProperty(APPLICANT, "creditScor", 750);
        assumeTrue(condition != null, "the language reads a missing property as null or undefined");
        Rule misspelled = rule("r", 1, condition, putFact(SEEN, APPLICANT));

        // Silently evaluating to false is the failure this catches: the rule never fires and nothing says why. A
        // language may reject the property when it compiles the rule rather than when it runs it, so loading is
        // inside the check too, and either failure counts.
        //
        // Only the record: a missing key of a map is a different question, and languages answer it differently on
        // purpose. JsonLogic, JEXL and SpEL read a missing key as null or empty, which is what their users expect,
        // and a faithful adapter for one of them shouldn't fail a contract written around a record's components.
        assertThrows(UnrulyException.class,
                () -> engine(misspelled).run(new FactMap<>(new Fact<>(APPLICANT, new Applicant(750)))),
                "a misspelled property of a record fact didn't fail");
    }

    @Test
    @DisplayName("copies made and warmed up when the rules load give the same results, one run or several at once")
    // Shut down in the finally block, which also interrupts workers that a broken language leaves running.
    @SuppressWarnings("PMD.CloseResource")
    void copiesAtLoad() throws Exception {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language()).copiesAtLoad(2).build();
        engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "y"))));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Map<String, Object>>> results = new ArrayList<>();
            for (int y = 0; y < 2; y++) {
                FactStore<Object> facts = new FactMap<>(new Fact<>("x", 1));
                facts.setValue("y", y);
                results.add(workers.submit(() -> engine.run(facts)));
            }
            for (int y = 0; y < 2; y++) {
                assertSameOutput(Map.of(SEEN, y), results.get(y).get(30, TimeUnit.SECONDS));
            }
            assertNull(engine.run(new FactMap<>(new Fact<>("x", 2))));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("the engine closes the language's compiler once: when a reload replaces the rules, and when it's closed")
    void compilerClosed() {
        ExpressionLanguage language = language();
        List<AtomicInteger> closes = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = engine(countingCloses(language, closes));

        engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));
        assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
        engine.load(List.of(rule("r", 1, factEquals("x", 2), putFact(SEEN, "x"))));

        assertEquals(List.of(1, 0), closes.stream().map(AtomicInteger::get).toList());
        assertSameOutput(Map.of(SEEN, 2), engine.run(new FactMap<>(new Fact<>("x", 2))));

        engine.close();
        engine.close();

        assertEquals(List.of(1, 1), closes.stream().map(AtomicInteger::get).toList());
    }

    /** Wraps a language so that each compiler it creates counts how often it's closed. */
    private static ExpressionLanguage countingCloses(ExpressionLanguage language, List<AtomicInteger> closes) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                AtomicInteger closed = new AtomicInteger();
                closes.add(closed);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }

                    @Override
                    public void checkFactName(String name) {
                        compiler.checkFactName(name);
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        compiler.close();
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("concurrent runs of one rule list each see their own facts")
    // Shut down in the finally block, which also interrupts workers that a broken language leaves running.
    @SuppressWarnings("PMD.CloseResource")
    void concurrentRuns() throws Exception {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact(SEEN, "y")));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<List<Map<String, Object>>>>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int worker = t;
                results.add(workers.submit(() -> {
                    start.await();
                    List<Map<String, Object>> expected = new ArrayList<>();
                    List<Map<String, Object>> actual = new ArrayList<>();
                    for (int i = 0; i < 200; i++) {
                        int x = (worker + i) % 2;
                        int y = worker * 1_000 + i;
                        FactStore<Object> facts = new FactMap<>();
                        facts.setValue("x", x);
                        facts.setValue("y", y);
                        expected.add(x == 1 ? Map.of(SEEN, y) : null);
                        actual.add(engine.run(facts));
                    }
                    return List.of(expected, actual);
                }));
            }
            start.countDown();

            for (Future<List<List<Map<String, Object>>>> result : results) {
                List<List<Map<String, Object>>> runs = result.get(30, TimeUnit.SECONDS);
                assertSameOutput(runs.get(0), runs.get(1));
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
