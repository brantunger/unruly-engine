package io.github.brantunger.unruly.test;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <pre>{@code
 * class MyLanguageContractTest extends ExpressionLanguageContractTest {
 *     protected ExpressionLanguage language() { return new MyLanguage(); }
 *     protected String alwaysTrue() { return "true"; }
 *     protected String factEquals(String fact, int value) { return fact + " == " + value; }
 *     // ... one method for each expression the checks need
 * }
 * }</pre>
 *
 * <p>
 * Each check runs rules through an engine, so a language passes only if it works with the engine as users will run
 * it. On the module path, the package of the extending test must be open to {@code org.junit.platform.commons}.
 * </p>
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
     * Returns a condition that is true when an integer fact equals a value.
     *
     * @param fact  The fact's name
     * @param value The value to compare it with
     * @return The condition
     */
    protected abstract String factEquals(String fact, int value);

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
     * Returns a fact name that rules in this language can't refer to.
     *
     * @return The name, or {@code null} if every name is accepted
     */
    protected abstract @Nullable String unusableFactName();

    /**
     * Returns a condition that compares one property of a fact with a value, as {@code applicant.creditScore == 750}
     * does in MVEL. The same condition is run against a record fact and against a {@link Map} fact.
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

    private static FactStore<Object> fact(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    @Test
    @DisplayName("a condition reads the facts, and its rule fires only when the condition is true")
    void conditionReadsFacts() {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x")));

        assertEquals(Map.of(SEEN, 1), engine.run(fact("x", 1)));
        assertNull(engine.run(fact("x", 2)));
    }

    @Test
    @DisplayName("a condition must evaluate to a boolean: null, a string or a number fails the rule")
    void conditionMustBeBoolean() {
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, factValue("x"), putFact(SEEN, "x")));

        assertEquals(Map.of(SEEN, true), engine.run(fact("x", true)));
        for (Object notBoolean : Arrays.asList(null, "true", 1)) {
            assertThrows(RuleExecutionException.class, () -> engine.run(fact("x", notBoolean)),
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
        assertThrows(UnrulyException.class, () -> engine(rule("r", 1, alwaysTrue(), reassign)).run(fact("x", 1)));
    }

    @Test
    @DisplayName("a variable an action declares doesn't change the facts later actions see")
    void actionVariablesStayLocal() {
        String declare = declareVariable("x", 2);
        assumeTrue(declare != null, "the language's actions have no variables");
        RulesEngine<Map<String, Object>> engine = engine(
                rule("declares", 2, alwaysTrue(), declare),
                rule("reads", 1, alwaysTrue(), putFact(SEEN, "x")));

        assertEquals(Map.of(SEEN, 1), engine.run(fact("x", 1)));
        assertEquals(Map.of(SEEN, 3), engine.run(fact("x", 3)));
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
    @DisplayName("a fact name the language can't refer to is rejected by run()")
    void unusableFactNameRejected() {
        String name = unusableFactName();
        assumeTrue(name != null, "the language accepts every fact name");
        RulesEngine<Map<String, Object>> engine = engine(rule("r", 1, alwaysTrue(), putFact(SEEN, "x")));

        assertThrows(IllegalArgumentException.class, () -> engine.run(fact(name, 1)));
    }

    @Test
    @DisplayName("a condition reads a property of a record fact and of a map fact the same way")
    void conditionReadsProperties() {
        RulesEngine<Map<String, Object>> engine =
                engine(rule("r", 1, factProperty(APPLICANT, CREDIT_SCORE, 750), putFact(SEEN, APPLICANT)));

        assertNotNull(engine.run(fact(APPLICANT, new Applicant(750))), "a record fact's component wasn't read");
        assertNotNull(engine.run(fact(APPLICANT, Map.of(CREDIT_SCORE, 750))), "a map fact's key wasn't read");
        assertNull(engine.run(fact(APPLICANT, new Applicant(700))), "the record's component was read as 750");
        assertNull(engine.run(fact(APPLICANT, Map.of(CREDIT_SCORE, 700))), "the map's key was read as 750");
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
        assertThrows(UnrulyException.class, () -> engine(misspelled).run(fact(APPLICANT, new Applicant(750))),
                "a misspelled property of a record fact didn't fail");
    }

    @Test
    @DisplayName("the engine closes the language's compiler once: when a reload replaces the rules, and when it's closed")
    void compilerClosed() {
        ExpressionLanguage language = language();
        List<AtomicInteger> closes = new CopyOnWriteArrayList<>();
        RulesEngine<Map<String, Object>> engine = engine(countingCloses(language, closes));

        engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));
        assertEquals(Map.of(SEEN, 1), engine.run(fact("x", 1)));
        engine.load(List.of(rule("r", 1, factEquals("x", 2), putFact(SEEN, "x"))));

        assertEquals(List.of(1, 0), closes.stream().map(AtomicInteger::get).toList());
        assertEquals(Map.of(SEEN, 2), engine.run(fact("x", 2)));

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
                assertEquals(runs.get(0), runs.get(1));
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
