package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.EvaluationContext;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Every check but {@code evaluateAgreesWithDetail} runs rules through an engine, so a language passes only if it
 * works with the engine as users will run it. On the module path, a test module of its own, one that requires the kit,
 * must open the package of the extending test to {@code org.junit.platform.commons}. Tests that Surefire patches into
 * the language's own named module need no opens clause: Surefire gives JUnit access to them.
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
// A test class: each check makes several assertions, and their failure messages show the values compared. Each check
// closes the engine it builds. What PMD still takes for unclosed is closed or stopped elsewhere: a language's compiler
// by the wrapper that holds it, a session by the ClosedQuietly that holds it, and a check's workers by stop().
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
     * Returns a condition that assigns a value to a fact, which the language must reject when it loads or runs the
     * rule, rather than evaluate to a boolean.
     *
     * @param fact  The fact's name
     * @param value The value to assign
     * @return The condition, or {@code null} if the language's conditions can't express an assignment, which skips
     *         the check
     */
    protected abstract @Nullable String assignment(String fact, int value);

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
     * Returns a fact name that rules in this language can't refer to. It must not be {@code "output"}, and the check
     * fails when it is: the engine rejects that name itself, before the language is asked, so it would pass the check
     * without the language's {@code checkFactName} ever running. Nor may it be {@code "x"}, the fact the check's rule
     * reads, and the check fails when it is too.
     *
     * @return The name, or {@code null} if every name is accepted
     */
    protected abstract @Nullable String unusableFactName();

    /**
     * Returns fact names that rules in this language can refer to, such as {@code credit_score2}. Each is read by a
     * condition and put into the output by an action, and the check fails if the language rejects the name or the
     * rule doesn't fire. By default, none, which skips the check. List the names a language's users will write, so
     * that a {@code checkFactName} stricter than the language itself fails here rather than in every run with that
     * fact.
     *
     * @return The names, or an empty collection to skip the check
     */
    protected Collection<String> usableFactNames() {
        return List.of();
    }

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

    /**
     * What a check does with an engine, a compiler or a session that it closes afterwards.
     *
     * @param <T> The resource's type
     */
    @FunctionalInterface
    private interface ResourceCheck<T> {
        void accept(T resource) throws Exception;
    }

    /**
     * Runs a check with an engine, a compiler or a session, and then closes it, however the check ends, as
     * try-with-resources would. A check builds its engine before it loads any rules, so a failed {@code load()} still
     * leaves the engine to be closed. What closing throws is attached to the check's own failure, unless it is that
     * failure or already attached to it: a language that throws one cached {@link Error} from {@code close()}, or from
     * a condition and a {@code close()}, fails the check with it and then the close with it again, and
     * try-with-resources would report {@code "Self-suppression not permitted"} instead.
     *
     * @param resource The engine, with no rules loaded yet, or the compiler or session
     * @param check    What the check does with it
     * @param <T>      The resource's type
     * @throws Exception What the check throws, or, when the check passes, what closing the resource throws
     */
    // Any Throwable, as try-with-resources closes on any, compared by identity: the same instance is what's handled.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static <T extends AutoCloseable> void closing(T resource, ResourceCheck<T> check) throws Exception {
        try {
            check.accept(resource);
        } catch (Throwable failure) {
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                if (closeFailure != failure
                        && Arrays.stream(failure.getSuppressed()).noneMatch(known -> known == closeFailure)) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        resource.close();
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
    void conditionReadsFacts() throws Exception {
        closing(engine(), engine -> {
            engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));

            assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
            assertNull(engine.run(new FactMap<>(new Fact<>("x", 2))));
        });
    }

    @Test
    @DisplayName("a condition written for a whole number reads a Long, a Short and a BigDecimal fact")
    void conditionReadsWholeNumbers() throws Exception {
        assumeTrue(comparesWholeNumbersByValue(), "the language compares whole numbers by type");
        closing(engine(), engine -> {
            engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));

            // A language whose equality is Objects.equals passes the check above, where every fact is an Integer, and
            // then never fires on a fact that came from JSON, a database or a long id.
            for (Object one : List.of(1L, (short) 1, BigDecimal.ONE)) {
                assertNotNull(engine.run(new FactMap<>(new Fact<>("x", one))),
                        "the rule didn't fire for a " + one.getClass().getSimpleName() + " fact");
            }
            // And it is by value: a language whose coercion falls through to true whenever the runtime types differ
            // fires for every whole number there is.
            assertNull(engine.run(new FactMap<>(new Fact<>("x", 2L))), "the rule fired for a 2L fact");
        });
    }

    @Test
    @DisplayName("a condition must evaluate to a boolean: null, a string or a number fails the rule")
    void conditionMustBeBoolean() throws Exception {
        closing(engine(), engine -> {
            engine.load(List.of(rule("r", 1, factValue("x"), putFact(SEEN, "x"))));

            assertEquals(Map.of(SEEN, true), engine.run(new FactMap<>(new Fact<>("x", true))));
            for (Object notBoolean : Arrays.asList(null, "true", 1)) {
                assertThrows(RuleExecutionException.class,
                        () -> engine.run(new FactMap<>(new Fact<>("x", notBoolean))), String.valueOf(notBoolean));
            }
        });
    }

    @Test
    @DisplayName("a condition that assigns to a fact is rejected by load or run, naming the rule and its condition")
    void conditionAssignmentRejected() throws Exception {
        String assign = assignment("x", 2);
        assumeTrue(assign != null, "the language's conditions can't assign a fact");
        closing(engine(), engine -> {
            // A language may reject the assignment when compiling or when running: by refusing it, by failing to
            // write to the read-only facts, or by evaluating to something that isn't a boolean. So loading is inside
            // the check.
            UnrulyException ex = assertThrows(UnrulyException.class, () -> {
                engine.load(List.of(rule("r", 1, assign, putFact(SEEN, "x"))));
                engine.run(new FactMap<>(new Fact<>("x", 1)));
            }, "a condition that assigns to a fact was neither rejected by load nor failed by run");

            if (ex instanceof RuleCompilationException compilation) {
                assertEquals("r", compilation.getRuleName(), ex.getMessage());
                assertEquals(ExpressionKind.CONDITION, compilation.getExpressionKind(), ex.getMessage());
            } else if (ex instanceof RuleExecutionException execution) {
                assertEquals("r", execution.getRuleName(), ex.getMessage());
                assertEquals(ExpressionKind.CONDITION, execution.getExpressionKind(), ex.getMessage());
            } else {
                fail("a condition that assigns to a fact failed with an UnrulyException that is neither a"
                        + " RuleCompilationException nor a RuleExecutionException: " + ex);
            }
        });
    }

    @Test
    @DisplayName("an action can't replace the output object")
    void outputNotReplaceable() throws Exception {
        String reassign = reassignOutput();
        assumeTrue(reassign != null, "the language's actions can't assign the output");
        // A language may reject the assignment when compiling or when running, so loading is inside the check.
        closing(engine(), engine -> assertThrows(UnrulyException.class, () -> {
            engine.load(List.of(rule("r", 1, alwaysTrue(), reassign)));
            engine.run(new FactMap<>(new Fact<>("x", 1)));
        }));
    }

    @Test
    @DisplayName("a variable an action declares doesn't change the facts later actions see, and no later rule or run"
            + " reads it")
    void actionVariablesStayLocal() throws Exception {
        String declare = declareVariable("x", 2);
        assumeTrue(declare != null, "the language's actions have no variables");
        closing(engine(), engine -> {
            engine.load(List.of(
                    rule("declares", 2, alwaysTrue(), declare),
                    rule("reads", 1, alwaysTrue(), putFact(SEEN, "x"))));

            assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
            assertSameOutput(Map.of(SEEN, 3), engine.run(new FactMap<>(new Fact<>("x", 3))));
        });

        // A variable that isn't a fact: a language that keeps it in the session, and reads the facts first, passes
        // the check above, and still hands it to every later rule and run. Reading it may fail the load or the run,
        // or read as null, as a JsonLogic-style language reads a name it doesn't know; anything but its value passes.
        String declareOther = Objects.requireNonNull(declareVariable("y", 2),
                "declareVariable() returned null for 'y', but not for 'x'");
        closing(engine(), engine -> {
            try {
                engine.load(List.of(
                        rule("declares", 2, factEquals("x", 1), declareOther),
                        rule("reads", 1, alwaysTrue(), putFact(SEEN, "y"))));
            } catch (UnrulyException e) {
                // The language refuses a name that isn't a fact when it compiles the rule.
                return;
            }
            for (int x = 1; x <= 2; x++) {
                Map<String, Object> output;
                try {
                    output = engine.run(new FactMap<>(new Fact<>("x", x)));
                } catch (UnrulyException e) {
                    // The rule that reads it failed: the variable isn't there.
                    continue;
                }
                if (output != null && sameValue(2, output.get(SEEN))) {
                    fail(x == 1
                            ? "a later rule read the variable 'y' an action declared: " + output
                            : "a later run read the variable 'y' an action declared in an earlier one, although the"
                                    + " rule that declares it didn't fire: " + output);
                }
            }
        });
    }

    @Test
    @DisplayName("a syntax error is reported by load, naming the rule and its condition")
    void syntaxErrorAtLoad() throws Exception {
        List<Rule> rules = List.of(rule("r", 1, syntaxError(), putFact(SEEN, "x")));

        closing(engine(), engine -> {
            RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

            assertEquals("r", ex.getRuleName());
            assertEquals(ExpressionKind.CONDITION, ex.getExpressionKind());
            assertTrue(ex.getMessage().startsWith("Condition for rule 'r' "), ex.getMessage());
        });
    }

    @Test
    @DisplayName("a syntax error in an action is reported by load, naming the rule and its action")
    void syntaxErrorInActionAtLoad() throws Exception {
        String action = actionSyntaxError();
        // The engine rejects a blank action before the language is asked to compile it, so a blank one would make
        // this check pass without the language's compiler running at all.
        assertFalse(action.isBlank(), "actionSyntaxError() must return an action the language itself rejects");
        List<Rule> rules = List.of(rule("r", 1, alwaysTrue(), action));

        closing(engine(), engine -> {
            // A language that compiles its actions on first use loads this rule without a word, and fails it in
            // production instead, one run at a time.
            RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.load(rules));

            assertEquals("r", ex.getRuleName());
            assertEquals(ExpressionKind.ACTION, ex.getExpressionKind());
        });
    }

    @Test
    @DisplayName("a fact name the language can't refer to is rejected by run()")
    void unusableFactNameRejected() throws Exception {
        String name = unusableFactName();
        assumeTrue(name != null, "the language accepts every fact name");
        // The engine rejects "output" before the language is asked, so it would make this check pass without the
        // language's checkFactName running at all.
        assertNotEquals("output", name, "unusableFactName() must return a name the language itself rejects");
        // The check's rule reads x, which the run supplies alongside the name, so x can't be the name as well.
        assertNotEquals("x", name, "unusableFactName() must not be x, which the check's rule reads");
        closing(engine(), engine -> {
            engine.load(List.of(rule("r", 1, alwaysTrue(), putFact(SEEN, "x"))));
            // The rule reads x, so x is supplied too: then only checkFactName can make the run throw.
            FactStore<Object> facts = new FactMap<>(new Fact<>("x", 1), new Fact<>(name, 1));

            assertThrows(IllegalArgumentException.class, () -> engine.run(facts),
                    "unusableFactName() returned '" + name + "', but run() didn't throw an IllegalArgumentException"
                            + " for a fact with that name: check the language's checkFactName");
        });
    }

    @Test
    @DisplayName("a fact name the language can refer to is read by a condition and an action")
    void usableFactNamesAccepted() throws Exception {
        Collection<String> names = usableFactNames();
        assumeTrue(!names.isEmpty(), "the language names no fact names it must accept");
        for (String name : names) {
            closing(engine(), engine -> {
                // A checkFactName stricter than the language rejects the name at run(), so every run with that fact
                // fails.
                Map<String, Object> output = assertDoesNotThrow(() -> {
                    engine.load(List.of(rule("r", 1, factEquals(name, 1), putFact(SEEN, name))));
                    return engine.run(new FactMap<>(new Fact<>(name, 1)));
                }, "a rule couldn't use the fact name '" + name + "', which usableFactNames() says it can");

                if (!sameValue(Map.of(SEEN, 1), output)) {
                    fail("a rule on the fact name '" + name + "' didn't put its value: expected: <" + Map.of(SEEN, 1)
                            + "> but was: <" + output + ">");
                }
            });
        }
    }

    @Test
    @DisplayName("a condition reads a property of a record fact, a JavaBean fact and a map fact the same way")
    void conditionReadsProperties() throws Exception {
        closing(engine(), engine -> {
            engine.load(List.of(rule("r", 1, factProperty(APPLICANT, CREDIT_SCORE, 750), putFact(SEEN, APPLICANT))));

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
        });
    }

    @Test
    @DisplayName("a property the fact doesn't have fails the run, rather than being false or undefined")
    void missingPropertyFailsTheRun() throws Exception {
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
        closing(engine(), engine -> assertThrows(UnrulyException.class, () -> {
            engine.load(List.of(misspelled));
            engine.run(new FactMap<>(new Fact<>(APPLICANT, new Applicant(750))));
        }, "a misspelled property of a record fact didn't fail"));
    }

    @Test
    @DisplayName("copies made and warmed up when the rules load give the same results, one run or several at once")
    void copiesAtLoad() throws Exception {
        closing(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(language())
                .copiesAtLoad(2).build(), engine -> {
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
                stop(workers);
            }
        });
    }

    /**
     * Stops a check's workers before its engine is closed: interrupts them, which also stops workers that a broken
     * language leaves running, and waits a while for them to end, so that none still holds a copy of the rules when
     * the engine closes it. On a thread that is already interrupted, the wait is skipped and the thread stays
     * interrupted; a copy a worker gives back later is closed then, by the closed engine.
     *
     * @param workers The workers
     */
    private static void stop(ExecutorService workers) {
        workers.shutdownNow();
        // Not close(), which waits for as long as a broken language keeps a worker running. A worker still running
        // after this gives its copy back to the closed engine, which closes the copy then.
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Interrupted before or while it waits, as a JUnit timeout or an interrupted run leaves the thread. The
            // check's own failure stays the one reported, and the thread stays interrupted.
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("the engine closes the language's compiler once: when a reload replaces the rules, and when it's"
            + " closed, and closing it doesn't throw")
    void compilerClosed() throws Exception {
        ExpressionLanguage language = language();
        List<AtomicInteger> closes = new CopyOnWriteArrayList<>();
        List<Throwable> closeFailures = new CopyOnWriteArrayList<>();
        // Closed after the check as well, so a failed check still closes the engine. A third close does nothing.
        try {
            closing(engine(countingCloses(language, closes, closeFailures)), engine -> {
                engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));
                assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
                engine.load(List.of(rule("r", 1, factEquals("x", 2), putFact(SEEN, "x"))));

                assertEquals(List.of(1, 0), closes.stream().map(AtomicInteger::get).toList());
                assertSameOutput(Map.of(SEEN, 2), engine.run(new FactMap<>(new Fact<>("x", 2))));

                engine.close();
                engine.close();

                assertEquals(List.of(1, 1), closes.stream().map(AtomicInteger::get).toList());
            });
        } catch (Throwable e) {
            // The check's own failure stays the one reported, with what closing a compiler threw attached to it.
            suppressAll(e, closeFailures);
            throw e;
        }

        // A close() that throws is only logged at WARN, so nothing else would show it: a compiler that fails to
        // close has usually failed to release what it holds.
        if (!closeFailures.isEmpty()) {
            fail("a compiler's close() threw " + closeFailures.get(0) + ", which the engine only logs at WARN");
        }
    }

    /**
     * Attaches what closing a language's compilers or sessions threw to a check's failure, each once, unless it is
     * that failure or already attached to it: the engine rethrows a fatal error from close() itself, and
     * {@code closing()} has attached what {@code engine.close()} threw to the failure, so either may be one of them
     * already. One exception a language throws from every close, cached, is attached once.
     *
     * @param failure       The check's failure
     * @param closeFailures What closing threw
     */
    // By identity: the same instance is what's attached.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static void suppressAll(Throwable failure, List<Throwable> closeFailures) {
        for (Throwable closeFailure : closeFailures) {
            if (closeFailure != failure
                    && Arrays.stream(failure.getSuppressed()).noneMatch(known -> known == closeFailure)) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * Whether the engine rethrows what a language's {@code close()} threw, rather than logging it: a
     * {@link VirtualMachineError} other than a {@link StackOverflowError}, as the engine decides.
     */
    private static boolean isFatal(Throwable thrown) {
        return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError);
    }

    /**
     * Wraps a language so that each compiler it creates counts how often it's closed, and records in
     * {@code closeFailures} what its close() throws that the engine only logs.
     */
    private static ExpressionLanguage countingCloses(ExpressionLanguage language, List<AtomicInteger> closes,
                                                     List<Throwable> closeFailures) {
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

                    // Anything it throws, a checked exception thrown sneakily included: the engine logs any Exception
                    // or Error but a fatal one, which it rethrows, and which fails the check by itself.
                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        try {
                            compiler.close();
                        } catch (Throwable e) {
                            if (!isFatal(e)) {
                                closeFailures.add(e);
                            }
                            throw e;
                        }
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("each copy of the rules gets a session of its own, and closing a session doesn't throw")
    void sessionsClosed() throws Exception {
        SessionWatch sessions = new SessionWatch();
        // Two copies when the rules load. A language that keeps state gets newSession() called twice, once for each
        // copy, and each session warmed up; one that returns Session.none() is asked once, and its copy is shared.
        // Closed however the check ends, so a failed run still closes the sessions.
        try {
            closing(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                    .language(sessions.watching(language())).copiesAtLoad(2).build(), engine -> {
                engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));
                assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
            });
        } catch (Throwable e) {
            // The engine is closed by now. The run's failure stays the one reported, with what closing the sessions
            // threw attached to it.
            sessions.suppressCloseFailures(e);
            throw e;
        }

        // The engine closes every session itself, once, so what's left to check is the language's part: a session
        // returned to two copies is used by two runs at once and closed twice, and a close() that throws is only
        // logged at WARN, so nothing else would show either.
        sessions.assertNoneShared();
        sessions.assertNoneThrewOnClose();
    }

    /**
     * Checks three things about each condition's detail: that it isn't a session the language's {@code newSession()}
     * returned, compared by identity, that its {@code toString()} still works once the engine has closed the
     * sessions, and that what it prints is the same after another run and after the close as right after its own
     * run. It doesn't look inside the detail for a session it holds, and a language that returns
     * {@link Session#none()} has no session to compare with.
     */
    @Test
    @DisplayName("a condition's detail isn't the session it ran with, and reads the same after another run and once the"
            + " session is closed")
    void conditionDetail() throws Exception {
        SessionWatch sessions = new SessionWatch();

        // Closed before the details are read the last time, so they're read once the engine has closed the sessions,
        // and a failed run still closes them.
        List<RuleEvaluation> evaluations = new ArrayList<>();
        List<String> printed = new ArrayList<>();
        closing(engine(sessions.watching(language())), engine -> {
            // One rule that matches and one that doesn't, so the detail of a false condition is checked too.
            engine.load(List.of(rule("matches", 2, factEquals("x", 1), putFact(SEEN, "x")),
                    rule("misses", 1, factEquals("x", 2), putFact(SEEN, "x"))));
            evaluations.addAll(engine.runWithResult(new FactMap<>(new Fact<>("x", 1))).evaluations());
            for (RuleEvaluation evaluation : evaluations) {
                printed.add(assertDoesNotThrow(() -> String.valueOf(evaluation.detail()),
                        "the detail of rule '" + evaluation.rule().getRuleName() + "' can't be read after its run"));
            }

            // A detail that reads what the session holds when it's printed, such as a buffer the session reuses,
            // prints the next run's values: every rule's result would then explain the last run.
            engine.run(new FactMap<>(new Fact<>("x", 7)));
            for (int i = 0; i < evaluations.size(); i++) {
                RuleEvaluation evaluation = evaluations.get(i);
                assertEquals(printed.get(i), String.valueOf(evaluation.detail()),
                        "the detail of rule '" + evaluation.rule().getRuleName() + "' changed after another run");
            }
        });

        assertEquals(2, evaluations.size(), "the run didn't report both rules' evaluations");
        // The result outlives the run, and a caller reads it after the engine has given the session to another run
        // or closed it, so the detail can't be the session, and printing it can't need the session open.
        for (int i = 0; i < evaluations.size(); i++) {
            RuleEvaluation evaluation = evaluations.get(i);
            Object detail = evaluation.detail();
            sessions.assertNotASession(detail);
            String closed = assertDoesNotThrow(() -> String.valueOf(detail),
                    "the condition's detail can't be read once the engine has closed its session");
            assertEquals(printed.get(i), closed,
                    "the detail of rule '" + evaluation.rule().getRuleName() + "' changed once the engine closed its"
                            + " session");
        }
    }

    /**
     * Watches the sessions a language returns: whether it returns one instance twice, and whether closing one throws.
     * Each session the engine gets is wrapped, so its close can be seen, and the language is handed back its own
     * session, unwrapped, wherever the engine passes one.
     */
    private static final class SessionWatch {

        /** Every session the language returned, by identity: two sessions that are merely equal are still two. */
        private final Set<Session> returned = Collections.synchronizedSet(
                Collections.newSetFromMap(new IdentityHashMap<>()));
        private final List<Session> shared = new CopyOnWriteArrayList<>();
        private final List<Throwable> closeFailures = new CopyOnWriteArrayList<>();

        void assertNoneShared() {
            if (!shared.isEmpty()) {
                fail("newSession() returned the same session for two copies of the rules, so two runs use it at once"
                        + " and the engine closes it twice: " + shared.get(0));
            }
        }

        void assertNoneThrewOnClose() {
            if (!closeFailures.isEmpty()) {
                fail("a session's close() threw " + closeFailures.get(0)
                        + ", which the engine only logs at WARN");
            }
        }

        void suppressCloseFailures(Throwable runFailure) {
            suppressAll(runFailure, closeFailures);
        }

        void assertNotASession(@Nullable Object detail) {
            if (returned.contains(detail)) {
                fail("the condition's detail is the session it ran with, which the engine gives to another run or"
                        + " closes: " + detail);
            }
        }

        // Session.none() is one shared instance, and the engine asks whether a language's session is it by identity.
        @SuppressWarnings("PMD.CompareObjectsWithEquals")
        private @Nullable Session watch(@Nullable Session session) {
            // Passed through as it is: a wrapped Session.none() would make the engine keep a copy of the rules for
            // each run, where it shares one, and change what's being checked. A null is passed through too, so the
            // engine still rejects it with its own message.
            if (session == null || session == Session.none()) {
                return session;
            }
            // Recorded, not failed here: an exception from newSession() would be the engine's failure to create a
            // session, reported by load() here, not a message about the language's session being shared.
            if (!returned.add(session)) {
                shared.add(session);
            }
            return new Watched(session);
        }

        /** The language's own session, which is what its expressions and warmUp() expect. */
        private static Session unwrap(Session session) {
            return session instanceof Watched watched ? watched.session : session;
        }

        ExpressionLanguage watching(ExpressionLanguage language) {
            return new ExpressionLanguage() {
                @Override
                public String name() {
                    return language.name();
                }

                @Override
                public ExpressionCompiler newCompiler(CompileContext context) {
                    ExpressionCompiler compiler = language.newCompiler(context);
                    return new ExpressionCompiler() {
                        @Override
                        public CompiledCondition compileCondition(Expression expression) {
                            CompiledCondition condition = compiler.compileCondition(expression);
                            // Not a lambda: that would implement only evaluate(), and drop the language's detail.
                            return new CompiledCondition() {
                                @Override
                                public @Nullable Object evaluate(EvaluationContext evaluation, Session session)
                                        throws Exception {
                                    return condition.evaluate(evaluation, unwrap(session));
                                }

                                @Override
                                public ConditionResult evaluateWithDetail(EvaluationContext evaluation,
                                                                          Session session) throws Exception {
                                    return condition.evaluateWithDetail(evaluation, unwrap(session));
                                }
                            };
                        }

                        @Override
                        public CompiledAction compileAction(Expression expression) {
                            CompiledAction action = compiler.compileAction(expression);
                            return (actionContext, session) -> action.execute(actionContext, unwrap(session));
                        }

                        @Override
                        public Session newSession() {
                            return watch(compiler.newSession());
                        }

                        @Override
                        public void warmUp(Session session) throws Exception {
                            compiler.warmUp(unwrap(session));
                        }

                        @Override
                        public void checkFactName(String name) {
                            compiler.checkFactName(name);
                        }

                        @Override
                        public void close() {
                            compiler.close();
                        }
                    };
                }
            };
        }

        /** A language's session, whose close() records what it throws before throwing it on. */
        private final class Watched implements Session {

            private final Session session;

            Watched(Session session) {
                this.session = session;
            }

            // Anything it throws, a checked exception thrown sneakily included: the engine logs any Exception or Error.
            @Override
            public void close() {
                try {
                    session.close();
                } catch (Throwable e) {
                    closeFailures.add(e);
                    throw e;
                }
            }
        }
    }

    /**
     * Compiles a condition with the language's compiler, outside an engine, and evaluates it against one session, with
     * {@code evaluate} and with {@code evaluateWithDetail}, for a fact that is an {@code Integer} 1 or 2, a
     * {@code Long} 1 or 2, a {@code Short} 1 and a {@code BigDecimal} 1. The engine calls only
     * {@code evaluateWithDetail}, so a language whose {@code evaluate} disagrees with it passes every other check,
     * and fails whoever calls {@code evaluate} directly. A language that doesn't override {@code evaluateWithDetail}
     * passes: the default returns what {@code evaluate} does. So does one that throws from both for a fact, as a
     * language that compares whole numbers by type may; one that throws from only one of them fails.
     *
     * <p>
     * An exception from closing the session or the compiler doesn't fail this check: the engine only logs one, and
     * {@code sessionsClosed} is the check that fails a session whose {@code close()} throws, and
     * {@code compilerClosed} the one that fails a compiler whose {@code close()} throws.
     * </p>
     */
    @Test
    @DisplayName("a condition's evaluate returns the value evaluateWithDetail reports")
    void evaluateAgreesWithDetail() throws Exception {
        // Closed however the check ends, the session before its compiler, as the engine closes them.
        closing(new ClosedQuietly<>(language().newCompiler(LanguageTestContexts.compile())), compiler -> {
            CompiledCondition condition = compiler.resource()
                    .compileCondition(new Expression("r", ExpressionKind.CONDITION, factEquals("x", 1)));
            // A session of the language's own, as a run gets one, not Session.none(), which a stateful language
            // couldn't evaluate with.
            Session created = compiler.resource().newSession();
            assertNotNull(created, "newSession() returned null, which fails every run that needs a session");
            closing(new ClosedQuietly<>(created), closed -> {
                Session session = closed.resource();
                // The whole numbers conditionReadsWholeNumbers runs against too: an evaluate that compares with
                // Objects.equals, beside an evaluateWithDetail that compares by value, agrees with it on Integers only.
                for (Object x : List.of(1, 2, 1L, 2L, (short) 1, BigDecimal.ONE)) {
                    String forFact = "for x = " + x + " (" + x.getClass().getSimpleName() + "), ";
                    EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of("x", x));
                    ConditionResult detailed;
                    try {
                        detailed = condition.evaluateWithDetail(evaluation, session);
                    } catch (Exception e) {
                        // A language that can't compare this type fails the rule either way, as long as evaluate
                        // does too.
                        assertThrows(Exception.class, () -> condition.evaluate(evaluation, session),
                                forFact + "evaluateWithDetail threw " + e + ", but evaluate didn't");
                        continue;
                    }
                    assertNotNull(detailed,
                            forFact + "evaluateWithDetail returned null, which fails the rule");

                    Object value = assertDoesNotThrow(() -> condition.evaluate(evaluation, session),
                            forFact + "evaluate threw, but evaluateWithDetail returned " + detailed.value());
                    assertEquals(detailed.value(), value,
                            forFact + "evaluate returned a different value than evaluateWithDetail reported");
                }
            });
        });
    }

    /**
     * Closes a session or a compiler, ignoring an exception its {@code close()} throws, which the engine only logs. An
     * {@link Error} is thrown on.
     *
     * @param resource The session or compiler
     * @param <T>      Its type
     */
    private record ClosedQuietly<T extends AutoCloseable>(T resource) implements AutoCloseable {

        // Anything but an Error, a checked exception thrown sneakily included: the engine logs any Exception.
        @Override
        @SuppressWarnings("PMD.EmptyCatchBlock")
        public void close() {
            try {
                resource.close();
            } catch (Exception e) {
                // Logged by the engine, not thrown. Whether it throws is the session check's question, not this one's.
            }
        }
    }

    @Test
    @DisplayName("concurrent runs of one rule list each see their own facts")
    void concurrentRuns() throws Exception {
        closing(engine(), engine -> {
            engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "y"))));
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
                stop(workers);
            }
        });
    }
}
