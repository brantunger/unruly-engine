package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.exception.UnrulyException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.test.KitResources.ClosedQuietly;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.test.CompilerCloseCounter.countingCloses;
import static io.github.brantunger.unruly.test.KitFailures.describe;
import static io.github.brantunger.unruly.test.KitFailures.message;
import static io.github.brantunger.unruly.test.KitFailures.outerRunFailed;
import static io.github.brantunger.unruly.test.KitFailures.rethrowIfFatal;
import static io.github.brantunger.unruly.test.KitFailures.runAroundNestedFailed;
import static io.github.brantunger.unruly.test.KitFailures.suppressAll;
import static io.github.brantunger.unruly.test.KitResources.closing;
import static io.github.brantunger.unruly.test.KitResources.stop;
import static io.github.brantunger.unruly.test.SameOutput.assertSameOutput;
import static io.github.brantunger.unruly.test.SameOutput.mismatch;
import static io.github.brantunger.unruly.test.SameOutput.sameValue;
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
// A test class: each check makes several assertions, and their failure messages show the values compared.
@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
public abstract class ExpressionLanguageContractTest {

    /** The output key the checks' actions put a fact's value under. */
    private static final String SEEN = "seen";

    /** The rule the variable checks declare their variable in. */
    private static final String DECLARES = "declares";

    /** The rule that reads what an earlier rule, or an earlier run, may have left behind. */
    private static final String READS = "reads";

    /** The rule {@code sharedStateStaysLocal} changes the language's shared state in. */
    private static final String CHANGES = "changes";

    /** The fact whose getter starts a nested run, and the property of it the nested-run checks read. */
    private static final String NEST = "nest";
    private static final String NEST_VALUE = "value";

    /**
     * What the getter of a {@link Nesting} made to fail puts in its message, so that the checks recognize the failure
     * a language rethrows with that message, rather than with the exception as a cause.
     */
    private static final String PLANNED_FAILURE = "[unruly contract kit: planned nested-run failure]";

    /** The nested-run checks' rules: the one whose expression starts a nested run, and the one that run fires. */
    private static final String OUTER = "outer";
    private static final String INNER = "inner";

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
     * Returns a condition that writes a property of a fact, which the language must reject when it loads or runs the
     * rule. {@code conditionWritesRejected} runs the same condition against a {@link Map} fact and a
     * {@link WritableApplicant} bean fact. The condition must also write a bean's property, as MVEL's
     * {@code applicant.creditScore = 1} does. Each fact must be unchanged afterwards: a language that fails the rule
     * only after the write has still changed the caller's fact. By default, {@code null}.
     *
     * @param fact     The fact's name
     * @param property The property to write
     * @param value    The value to write
     * @return The condition, or {@code null} if the language's conditions can't write a property, which skips that
     *         part of the check
     */
    protected @Nullable String propertyAssignment(String fact, String property, int value) {
        return null;
    }

    /**
     * Returns a condition that declares a variable and is then true, which the language must reject when it loads or
     * runs the rule. {@code conditionWritesRejected} names a variable that is none of the checks' facts, so a language
     * that declares its facts can't take the declaration for an assignment to one. By default, {@code null}.
     *
     * @param name  The variable's name
     * @param value The value it holds
     * @return The condition, or {@code null} if the language's conditions can't declare a variable, which skips that
     *         part of the check
     */
    protected @Nullable String conditionDeclaration(String name, int value) {
        return null;
    }

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
     * Returns an action that puts a variable's value into the output map under a key. The variable checks read the
     * variable an earlier action declared with it. By default, {@link #putFact putFact(key, variable)}, for a language
     * that reads a variable as it reads a fact. A language whose variables have a namespace of their own, such as
     * SpEL's {@code #y}, overrides it, and {@link #variableEquals} too: a variable read as a fact is never found, and
     * the checks pass whether it leaked or not.
     *
     * @param key      The key to put the value under
     * @param variable The variable's name
     * @return The action
     */
    protected String putVariable(String key, String variable) {
        return putFact(key, variable);
    }

    /**
     * Returns a condition that is true when a whole-number variable equals a value. {@code actionVariablesStayLocal}
     * reads with it the variable an earlier run's action declared. By default, {@link #factEquals factEquals(variable,
     * value)}, for a language that reads a variable as it reads a fact; see {@link #putVariable}.
     *
     * @param variable The variable's name
     * @param value    The value to compare it with
     * @return The condition
     */
    protected String variableEquals(String variable, int value) {
        return factEquals(variable, value);
    }

    /**
     * Returns an action that declares a variable and then fails the run, as
     * {@code y = 2; Integer.parseInt('not a number')} does in MVEL. {@code failedActionVariablesStayLocal} checks that
     * a later run doesn't read the variable, as a language that clears its variables only when an action succeeds
     * would let it. The action must declare the variable before it fails: one that fails first declares nothing, and
     * the check proves nothing. By default, {@code null}.
     *
     * @param name  The variable's name
     * @param value The value it holds
     * @return The action, or {@code null} if the language's actions have no variables, which skips the check
     */
    protected @Nullable String declareVariableThenFail(String name, int value) {
        return null;
    }

    /**
     * Returns an action that declares a variable holding a fact's value, and then puts the variable's value into the
     * output map under a key. {@code concurrentRuns} runs it in many runs at once, each with its own value, so it is
     * likely to catch an action whose variables every run shares, such as a map compiled into the action or a static:
     * such an action puts another run's value, or fails, when another run changes the variable between its declaring
     * the variable and putting it. By default, {@code null}.
     *
     * @param key  The key to put the value under
     * @param fact The fact's name
     * @return The action, or {@code null} if the language's actions have no variables, which leaves the variable out
     *         of {@code concurrentRuns} rather than skipping it
     */
    protected @Nullable String copyThroughVariable(String key, String fact) {
        return null;
    }

    /**
     * Whether a variable an action declares lasts until the end of the run, so that a later rule's action in the same
     * run reads it, as in a language that keeps each run's variables with
     * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScoped}. By default, {@code false}: a
     * variable stays local to the action that declares it. Returning {@code true} turns one part of
     * {@code actionVariablesStayLocal} around: a later rule in the same run must read the variable, so the check fails
     * when {@code load()} refuses that rule, when it fails the run, or when it reads anything but the variable's value.
     * The rest of the check is unchanged: the variable still mustn't change the facts later actions see, and no later
     * run may read it.
     *
     * @return Whether an action's variables last the run
     */
    protected boolean actionVariablesLastTheRun() {
        return false;
    }

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
     * Returns a fact name that rules in this language can't refer to. It must not be {@code "output"} or blank, and
     * the check fails when it is: the engine rejects those names itself, before the language is asked, so they would
     * pass the check without the language's {@code checkFactName} ever running. Nor may it be {@code "x"}, the fact
     * the check's rule reads, and the check fails when it is too.
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

    /**
     * Returns an action that puts one property of a fact, read through its getter, into the output map under a key.
     * {@code nestedRunInsideAnAction} and {@code nestedRunFailsInsideAnAction} run it against a {@link Nesting} fact,
     * whose getter starts a run nested inside the action, on the action's own thread. By default, {@code null}.
     *
     * @param key      The key to put the value under
     * @param fact     The fact's name
     * @param property The property to read
     * @return The action, or {@code null} if the language's actions can't read a fact's property, which skips the
     *         checks
     */
    protected @Nullable String putFactProperty(String key, String fact, String property) {
        return null;
    }

    /**
     * Returns a condition that is true when both conditions are, and evaluates {@code condition} before
     * {@code other}, as {@code condition && other} does in MVEL. {@code nestedRunInsideACondition} and
     * {@code nestedRunFailsInsideACondition} join {@link #factProperty factProperty("nest", "value", 7)}, whose getter
     * starts a run nested inside the condition, with {@link #factEquals factEquals("x", 1)}, so that the second read
     * is the one that finds any state the nested run left on the thread. A language that evaluates the right side
     * first isn't checked by them: the first passes it, and the second is skipped when the nested run, stopping at
     * the right side, never reads the getter that makes it fail. By default, {@code null}.
     *
     * @param condition The condition evaluated first
     * @param other     The condition evaluated second
     * @return The condition, or {@code null} if the language's conditions can't require both of two conditions, which
     *         skips the checks
     */
    protected @Nullable String bothConditions(String condition, String other) {
        return null;
    }

    /**
     * Returns an action that changes state the language keeps outside a run's variables and shares between runs, such
     * as a property of a built-in object, as {@code Math.discount = 50} is in JavaScript, or a global, setting what
     * {@code name} refers to to {@code value}. {@code sharedStateStaysLocal} checks that a later run doesn't see the
     * change: the language may refuse the action when it loads the rule, fail it when it runs before it changes
     * anything, or keep the change to the run. An action that fails after the change is checked as one that succeeds.
     * By default, {@code null}.
     *
     * @param name  The name of the state to change
     * @param value The value to set it to
     * @return The action, or {@code null} if the language's actions can reach no state shared between runs, which
     *         skips the check
     */
    protected @Nullable String changeSharedState(String name, int value) {
        return null;
    }

    /**
     * Returns a condition that is true when the shared state {@link #changeSharedState} changes holds {@code value},
     * and false, without failing, while it doesn't. {@code sharedStateStaysLocal} evaluates it in a run before any
     * action changes the state, in the run whose action makes the change, before that action runs, and in a later
     * run. The check fails when it fails any of those runs, or is true in the run before the change. It must read the
     * state the action writes: a condition that is never true passes the check whatever the language shares. By
     * default, {@code null}.
     *
     * @param name  The name of the state to read
     * @param value The value to compare it with
     * @return The condition, or {@code null} if {@link #changeSharedState} returns {@code null} too: with that hook
     *         overridden, the check fails on a {@code null} here rather than skip
     */
    protected @Nullable String sharedStateEquals(String name, int value) {
        return null;
    }

    /**
     * Configures each engine the checks build, for a language that needs what the builder carries to compile its
     * expressions: declared facts, imports or options of its own. By default, nothing. It's called once for each
     * engine, after the kit has started an {@code allMatches} engine whose output is a {@link HashMap}, and added the
     * language.
     *
     * <p>
     * The facts the checks' expressions refer to are {@code x}, {@code y}, {@code applicant} and {@code nest}, and the
     * names {@link #usableFactNames()} returns. The checks supply {@code x} as a {@code Boolean}, a {@code String},
     * {@code null}, an {@code Integer}, a {@code Long}, a {@code Short} and a {@code BigDecimal}, {@code y} as an
     * {@code Integer}, {@code applicant} as an {@link Applicant}, an {@link ApplicantBean}, a {@link WritableApplicant}
     * and a {@link Map}, {@code nest} as a {@link Nesting}, and each of the names {@link #usableFactNames()} and
     * {@link #unusableFactName()} return as the {@code Integer} 1. So a language that declares them declares {@code x}
     * and {@code applicant} as {@link Object}, and {@code nest} as {@link Object} or {@link Nesting}: the engine fails
     * a run whose fact isn't an instance of its declared type with an {@link IllegalArgumentException}, before the
     * language evaluates anything, and the check with it.
     * </p>
     *
     * <p>
     * It must not change what the checks depend on, or they fail for reasons that have nothing to do with the
     * language:
     * </p>
     * <ul>
     *     <li>{@code requireDeclaredFacts()}: each run supplies only the facts its check reads, so the engine fails
     *     it with an {@link IllegalArgumentException} before the language evaluates anything.</li>
     *     <li>A declaration of the name {@link #unusableFactName()} returns: {@code load()} checks every declared
     *     name with the language, which rejects that one. Most checks then fail to load their rules, with a
     *     {@link io.github.brantunger.unruly.api.exception.RuleCompilationException}, and the checks that expect a
     *     load to fail then pass or fail for reasons unrelated to the language.</li>
     *     <li>{@code runTimeout(...)}: a run that outlasts it fails, and its check with it.</li>
     *     <li>{@code maxCopies(...)} below 2: two checks make two copies of the rules when they load, which
     *     {@code build()} refuses with more copies than the limit. A higher limit also caps how many sessions
     *     {@code concurrentRuns} asks for, so {@code maxCopies(2)} hides a {@code newSession()} that returns a
     *     session twice only after its second call.</li>
     *     <li>Another language, or a {@code defaultLanguage(...)} other than the language's own name: {@code build()}
     *     throws an {@link IllegalStateException} for an engine with two languages and no default, or with a default
     *     that isn't one of its languages. {@code defaultLanguage(language().name())} changes nothing.</li>
     *     <li>{@code outputWriter(...)}: the checks read what an action returned from the output map, where
     *     {@code OutputWriter.beansAndMaps()} puts it.</li>
     * </ul>
     *
     * <p>
     * The two checks that need copies made when the rules load, {@code copiesAtLoad} and {@code sessionsClosed}, set
     * {@code copiesAtLoad(2)} after this. {@code compilerClosed} sets {@code copiesAtLoad(1)}, so that each compiler
     * has made a session when the rules load before it's closed; the engine warms up each session that isn't
     * {@code Session.none()}, which has nothing to warm up. The two that need a run to make its own copy,
     * {@code sessionClosedWhileAnotherRuns} and {@code sessionClosedOnAnotherThread}, set {@code copiesAtLoad(0)}, so
     * a {@code copiesAtLoad} set here doesn't change them. {@code sessionClosedWhileAnotherRuns} also sets
     * {@code maxCopies(1)} after this, so that a run nested in another gets an extra copy, and a limit set here doesn't
     * change it either; {@code concurrentRuns} sets none, so a limit set here caps the copies, and the sessions, its
     * runs get. The checks that look at what a run leaves for a later one, {@code actionVariablesStayLocal} in its
     * parts with a later run, {@code failedActionVariablesStayLocal}, {@code sharedStateStaysLocal} and
     * {@code conditionDetail}, and those that run a rejected rule a second time, {@code conditionAssignmentRejected},
     * {@code conditionWritesRejected}, {@code outputNotReplaceable} and {@code missingPropertyFailsTheRun}, set
     * {@code maxCopies(1)} and {@code copiesAtLoad(0)} after this, so that each run gets the one copy, and its
     * sessions, that the run before it used: with two copies made when the rules load, a later run would get the other
     * one. Listeners that don't change the output may be added: the engine only logs what a listener throws, unless
     * it's a fatal {@link Error}, but {@code beforeExecute} and {@code afterExecute} are given the output the checks
     * compare. {@code evaluateAgreesWithDetail} builds no engine, and compiles with {@link #compileContext()} instead:
     * a language that overrides both keeps them consistent.
     * </p>
     *
     * @param builder The builder of an engine a check is about to build
     */
    protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
    }

    /**
     * Returns the context {@code evaluateAgreesWithDetail} compiles its condition with, since that check compiles it
     * with the language's compiler, outside an engine. By default, {@link LanguageTestContexts#compile()}, which has
     * no imports, options or declared facts.
     *
     * <p>
     * A language that needs {@link #configure} to compile needs this too, with the same imports, options and declared
     * facts, which the {@link LanguageTestContexts} {@code compile} methods take. The condition is
     * {@link #factEquals factEquals("x", 1)}, evaluated for an {@code x} that is an {@code Integer}, a {@code Long}, a
     * {@code Short} and a {@code BigDecimal}.
     * </p>
     *
     * @return The context
     */
    protected CompileContext compileContext() {
        return LanguageTestContexts.compile();
    }

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

    /**
     * An applicant as a JavaBean with a setter, so a language's tests can run a rule against a fact whose property a
     * condition could write. {@code conditionWritesRejected} requires its credit score unchanged after a condition that
     * writes it. It's public, with a public setter, so that a language that calls a bean's setter, by reflection or
     * however else, can reach it: one it couldn't reach would fail the write, and pass the check, for that reason. It
     * has no {@code equals} or {@code hashCode}: the check looks at the one instance it gave the run.
     */
    public static final class WritableApplicant {

        // Volatile, so that the check sees a write a language made on another thread.
        private volatile int creditScore;

        /**
         * Creates the applicant.
         *
         * @param creditScore The applicant's credit score, the property the contract test writes
         */
        public WritableApplicant(int creditScore) {
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

        /**
         * Sets the applicant's credit score, which no condition may do.
         *
         * @param creditScore The credit score
         */
        public void setCreditScore(int creditScore) {
            this.creditScore = creditScore;
        }

        /**
         * Returns the applicant as {@code WritableApplicant[creditScore=750]}, so that a failure that names the fact
         * says what it held.
         *
         * @return The applicant's description
         */
        @Override
        public String toString() {
            return "WritableApplicant[creditScore=" + creditScore + "]";
        }
    }

    /**
     * The fact the nested-run checks run their rules against: its {@code value} property starts a run nested inside
     * the run that reads it, on the same thread, as a getter a condition or an action reads, or a function it calls,
     * does when it runs rules. Only {@code nestedRunInsideAnAction}, {@code nestedRunInsideACondition},
     * {@code nestedRunFailsInsideACondition} and {@code nestedRunFailsInsideAnAction} create one. It's public, with a
     * public getter, so that a language can read the property, by reflection or however else it reads a JavaBean's.
     * It keeps what the nested run returned, or the exception it threw.
     */
    public static final class Nesting {

        private final Supplier<@Nullable Map<String, Object>> nestedRun;
        private final boolean fails;
        private boolean started;
        private @Nullable Map<String, Object> nestedOutput;
        private @Nullable RuntimeException nestedFailure;
        private final List<IllegalStateException> thrown = new ArrayList<>();

        Nesting(Supplier<@Nullable Map<String, Object>> nestedRun) {
            this(nestedRun, false);
        }

        private Nesting(Supplier<@Nullable Map<String, Object>> nestedRun, boolean fails) {
            this.nestedRun = nestedRun;
            this.fails = fails;
        }

        /**
         * The fact a nested run reads to fail: its getter records that it was read, throws, and starts nothing. What it
         * throws carries {@link #PLANNED_FAILURE} in its message, and it keeps every instance it throws.
         */
        static Nesting failing() {
            return new Nesting(() -> null, true);
        }

        /**
         * Starts the nested run, and returns 7 whether the run returned or threw an exception; an {@link Error} it
         * threw is thrown on. The one a check gives the nested run itself, so that the nested run fails, throws an
         * {@link IllegalStateException} instead, and starts nothing. The check looks for it among the causes and
         * suppressed exceptions of the nested run's failure, or for its message there.
         *
         * @return 7
         */
        public int getValue() {
            started = true;
            if (fails) {
                IllegalStateException failure = new IllegalStateException(
                        "nest.value fails the run that reads it, as the check means it to " + PLANNED_FAILURE);
                thrown.add(failure);
                throw failure;
            }
            try {
                nestedOutput = nestedRun.get();
            } catch (RuntimeException e) {
                // Kept for the check: the engine would report it as the outer action's failure.
                nestedFailure = e;
            }
            return 7;
        }
    }

    private Rule rule(String name, int priority, String condition, String action) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition).action(action)
                .language(language().name()).build();
    }

    /**
     * Starts building the engine a check runs its rules with: an {@code allMatches} engine whose output is a
     * {@link HashMap}, with the language added and {@link #configure} applied. A check that needs a setting of its own
     * sets it on what this returns, after {@code configure}, so that {@code configure} can't change it.
     */
    private RulesEngineBuilder<Map<String, Object>> builder(ExpressionLanguage language) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>allMatches(
                HashMap::new).language(language);
        configure(builder);
        return builder;
    }

    private RulesEngine<Map<String, Object>> engine(ExpressionLanguage language) {
        return builder(language).build();
    }

    private RulesEngine<Map<String, Object>> engine() {
        return engine(language());
    }

    /**
     * Builds an engine that keeps one copy of the rules and makes none when they load, set after {@link #configure}
     * so that it can't change them: the first run makes the copy, and every later run gets it, and its sessions, back.
     * For a check that looks at what one run leaves for the next, or that runs a rejected rule again: with two copies
     * made when the rules load, the next run would get the other copy, whose sessions never saw the run before.
     */
    private RulesEngine<Map<String, Object>> oneCopyEngine(ExpressionLanguage language) {
        return builder(language).maxCopies(1).copiesAtLoad(0).build();
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

            assertSameOutput(Map.of(SEEN, true), engine.run(new FactMap<>(new Fact<>("x", true))));
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
        assertConditionRejected(assign, new FactMap<>(new Fact<>("x", 1)), "assigns to a fact", () -> {
        });
    }

    /**
     * Checks that a condition that writes a property of a fact, or declares a variable, is rejected by load or run,
     * naming the rule and its condition, as {@code conditionAssignmentRejected} checks for one that assigns to a fact:
     * a condition can't change the facts or declare variables. Each part is skipped when its hook,
     * {@link #propertyAssignment} or {@link #conditionDeclaration}, returns {@code null}, and the check when both do.
     * The property is written to a {@link Map} fact and to a {@link WritableApplicant}, which must each be unchanged
     * afterwards: a language that fails the rule only after the write has still changed the caller's fact. A rule that
     * fails when it runs is run a second time, which must fail too.
     */
    @Test
    @DisplayName("a condition that writes a fact's property or declares a variable is rejected by load or run, naming"
            + " the rule and its condition, and leaves the fact unchanged")
    void conditionWritesRejected() throws Exception {
        String write = propertyAssignment(APPLICANT, CREDIT_SCORE, 1);
        // A name none of the checks' facts has, so that a language that declares its facts can't take it for an
        // assignment to one, which conditionAssignmentRejected checks.
        String declare = conditionDeclaration("z", 2);
        assumeTrue(write != null || declare != null, "the language's conditions can't write a property or declare a"
                + " variable");
        if (write != null) {
            Map<String, Object> applicant = new HashMap<>(Map.of(CREDIT_SCORE, 750));
            assertConditionRejected(write, new FactMap<>(new Fact<>("x", 1), new Fact<>(APPLICANT, applicant)),
                    "writes a fact's property", () -> assertEquals(Map.of(CREDIT_SCORE, 750), applicant,
                            "a condition that writes a fact's property changed it"));
            // A language that refuses to write a map may still call a bean's setter.
            WritableApplicant bean = new WritableApplicant(750);
            assertConditionRejected(write, new FactMap<>(new Fact<>("x", 1), new Fact<>(APPLICANT, bean)),
                    "writes a bean fact's property", () -> assertEquals(750, bean.getCreditScore(),
                            "a condition that writes a bean fact's property changed it"));
        }
        if (declare != null) {
            assertConditionRejected(declare, new FactMap<>(new Fact<>("x", 1)), "declares a variable", () -> {
            });
        }
    }

    /**
     * Checks that a condition is rejected by load or run: loads it in a rule {@code r} and runs the rule with
     * {@code facts}, and requires an {@link UnrulyException} that names the rule and its condition. When the run
     * failed, runs the rule once more with the same facts, on the same copy of the rules, and requires the same: a
     * language that checks a compiled condition only on its first evaluation lets every later run through. Then checks
     * {@code afterwards}, before the engine is closed, so that what closing it throws can't hide a failure.
     *
     * @param condition  The condition
     * @param facts      The facts the rule runs with
     * @param what       What the condition does, which the failure's message says
     * @param afterwards What else the check requires once the condition is rejected
     */
    private void assertConditionRejected(String condition, FactStore<Object> facts, String what, Runnable afterwards)
            throws Exception {
        // One copy of the rules, so that the second run gets the compiled condition, and the session, the first used.
        closing(oneCopyEngine(language()), engine -> {
            // A language may reject the condition when compiling or when running: by refusing it, by failing to
            // write to the read-only facts, or by evaluating to something that isn't a boolean. So loading is inside
            // the check.
            AtomicBoolean loaded = new AtomicBoolean();
            UnrulyException ex = assertThrows(UnrulyException.class, () -> {
                engine.load(List.of(rule("r", 1, condition, putFact(SEEN, "x"))));
                loaded.set(true);
                engine.run(facts);
            }, "a condition that " + what + " was neither rejected by load nor failed by run");

            if (ex instanceof RuleCompilationException compilation) {
                assertEquals("r", compilation.getRuleName(), ex.getMessage());
                assertEquals(ExpressionKind.CONDITION, compilation.getExpressionKind(), ex.getMessage());
            } else if (ex instanceof RuleExecutionException execution) {
                assertEquals("r", execution.getRuleName(), ex.getMessage());
                assertEquals(ExpressionKind.CONDITION, execution.getExpressionKind(), ex.getMessage());
            } else {
                fail("a condition that " + what + " failed with an UnrulyException that is neither a"
                        + " RuleCompilationException nor a RuleExecutionException: " + ex);
            }
            assertRunFailsAgain(engine, loaded.get(), facts, ExpressionKind.CONDITION, "a condition that " + what);
            afterwards.run();
        });
    }

    /**
     * Checks that a run of rule {@code r} that failed fails again: when the rules loaded, so that the run failed
     * rather than load, runs the rule once more with the same facts, on the same copy of the rules, and requires a
     * {@link RuleExecutionException} that names the rule and the {@code kind} of expression that failed. A language
     * that checks a compiled expression only on its first evaluation lets every later run through. Does nothing when
     * load failed.
     *
     * @param engine The engine the first run failed on, which keeps one copy of the rules
     * @param loaded Whether the rules loaded, so that the first failure came from the run
     * @param facts  The facts the first run failed with
     * @param kind   The kind of expression the second run must fail in
     * @param what   What failed, which the failure's message says
     */
    private static void assertRunFailsAgain(RulesEngine<Map<String, Object>> engine, boolean loaded,
            FactStore<?> facts, ExpressionKind kind, String what) {
        if (!loaded) {
            return;
        }
        try {
            engine.run(facts);
        } catch (RuleExecutionException again) {
            if (!"r".equals(again.getRuleName()) || again.getExpressionKind() != kind) {
                throw failedDifferently(what, kind, again);
            }
            return;
        } catch (RuntimeException again) {
            throw failedDifferently(what, kind, again);
        }
        fail(what + " failed the first run but not the second");
    }

    /** The failure of {@link #assertRunFailsAgain} when the second run threw {@code again}, which it's given. */
    private static AssertionFailedError failedDifferently(String what, ExpressionKind kind, RuntimeException again) {
        return new AssertionFailedError(what + " failed the second run differently, not with a RuleExecutionException"
                + " naming rule r and its " + kind.name().toLowerCase(Locale.ROOT) + ": " + describe(again), again);
    }

    @Test
    @DisplayName("an action can't replace the output object")
    void outputNotReplaceable() throws Exception {
        String reassign = reassignOutput();
        assumeTrue(reassign != null, "the language's actions can't assign the output");
        // A language may reject the assignment when compiling or when running, so loading is inside the check. One
        // copy of the rules, so that a second run gets the compiled action, and the session, the first used.
        closing(oneCopyEngine(language()), engine -> {
            AtomicBoolean loaded = new AtomicBoolean();
            assertThrows(UnrulyException.class, () -> {
                engine.load(List.of(rule("r", 1, alwaysTrue(), reassign)));
                loaded.set(true);
                engine.run(new FactMap<>(new Fact<>("x", 1)));
            });
            // A language that checks a compiled action only on its first run lets every later one replace the output.
            assertRunFailsAgain(engine, loaded.get(), new FactMap<>(new Fact<>("x", 1)), ExpressionKind.ACTION,
                    "an action that replaces the output");
        });
    }

    @Test
    @DisplayName("a variable an action declares doesn't change the facts later actions see, and no later run reads it,"
            + " nor a later rule unless the language's variables last the run")
    void actionVariablesStayLocal() throws Exception {
        String declare = declareVariable("x", 2);
        assumeTrue(declare != null, "the language's actions have no variables");
        closing(engine(), engine -> {
            engine.load(List.of(
                    rule(DECLARES, 2, alwaysTrue(), declare),
                    rule(READS, 1, alwaysTrue(), putFact(SEEN, "x"))));

            assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
            assertSameOutput(Map.of(SEEN, 3), engine.run(new FactMap<>(new Fact<>("x", 3))));
        });

        // A variable that isn't a fact: a language that keeps it in the session, and reads the facts first, passes
        // the check above, and still hands it to every later rule and run. Reading it may fail the load or the run,
        // or read as null, as a JsonLogic-style language reads a name it doesn't know; anything but its value passes.
        // A language whose variables last the run must instead hand it to the later rule in the same run, and only
        // to that one. One copy of the rules, so that the later run gets the session the earlier one used.
        String declareOther = Objects.requireNonNull(declareVariable("y", 2),
                "declareVariable() returned null for 'y', but not for 'x'");
        boolean lastTheRun = actionVariablesLastTheRun();
        closing(oneCopyEngine(language()), engine -> {
            try {
                engine.load(List.of(
                        rule(DECLARES, 2, factEquals("x", 1), declareOther),
                        rule(READS, 1, alwaysTrue(), putVariable(SEEN, "y"))));
            } catch (UnrulyException e) {
                if (lastTheRun) {
                    throw new AssertionFailedError("actionVariablesLastTheRun() returns true, but load() refused the"
                            + " rule that reads the variable 'y' an earlier rule's action declares: " + describe(e), e);
                }
                // The language refuses a name that isn't a fact when it compiles the rule.
                return;
            }
            for (int x = 1; x <= 2; x++) {
                Map<String, Object> output;
                try {
                    output = engine.run(new FactMap<>(new Fact<>("x", x)));
                } catch (UnrulyException e) {
                    if (lastTheRun && x == 1) {
                        throw new AssertionFailedError("actionVariablesLastTheRun() returns true, but the run in which"
                                + " a later rule reads the variable 'y' an action declared failed: " + describe(e), e);
                    }
                    // The rule that reads it failed: the variable isn't there.
                    continue;
                }
                if (lastTheRun && x == 1) {
                    if (output == null || !sameValue(2, output.get(SEEN))) {
                        fail("actionVariablesLastTheRun() returns true, but a later rule in the same run didn't read"
                                + " the variable 'y' an action declared: " + output);
                    }
                } else if (output != null && sameValue(2, output.get(SEEN))) {
                    fail(x == 1
                            ? "a later rule read the variable 'y' an action declared: " + output
                            : "a later run read the variable 'y' an action declared in an earlier one, although the"
                                    + " rule that declares it didn't fire: " + output);
                }
            }
        });

        // A later run's condition, which a language may read from where its actions left their variables, even when
        // its actions read them no longer. y is a fact in the first run, which evaluates every condition before any
        // action declares it, so that a language that can't read an unknown name still gets through the first run.
        closing(oneCopyEngine(language()), engine -> {
            try {
                engine.load(List.of(
                        rule(DECLARES, 2, factEquals("x", 1), declareOther),
                        rule("readsInCondition", 1, variableEquals("y", 2), putFact(SEEN, "x"))));
            } catch (UnrulyException e) {
                // The language refuses a name that isn't a fact when it compiles the rule.
                return;
            }
            assertDoesNotThrow(() -> engine.run(new FactMap<>(new Fact<>("x", 1), new Fact<>("y", 0))),
                    "the run that declares the variable 'y', with y a fact, failed");
            Map<String, Object> output;
            try {
                output = engine.run(new FactMap<>(new Fact<>("x", 2)));
            } catch (UnrulyException e) {
                // The condition that reads it failed: the variable isn't there.
                return;
            }
            if (output != null) {
                fail("a later run's condition read the variable 'y' an action declared in an earlier one, although the"
                        + " rule that declares it didn't fire: " + output);
            }
        });
    }

    /**
     * Declares a variable in an action that then fails the run, and checks that a later run doesn't read it: a
     * language that clears what its actions declared only when an action succeeds leaves the variable for every later
     * run. Skipped when {@link #declareVariableThenFail} returns {@code null}.
     */
    @Test
    @DisplayName("a variable an action declares before it fails the run isn't read by a later run")
    void failedActionVariablesStayLocal() throws Exception {
        String declare = declareVariableThenFail("y", 2);
        assumeTrue(declare != null, "the language's actions have no variables");
        // One copy of the rules, so that the later run gets the session the failed one used.
        closing(oneCopyEngine(language()), engine -> {
            try {
                engine.load(List.of(
                        rule(DECLARES, 2, factEquals("x", 1), declare),
                        rule(READS, 1, alwaysTrue(), putVariable(SEEN, "y"))));
            } catch (UnrulyException e) {
                // The language refuses a name that isn't a fact when it compiles the rule.
                return;
            }
            assertThrows(UnrulyException.class, () -> engine.run(new FactMap<>(new Fact<>("x", 1))),
                    "the action from declareVariableThenFail() didn't fail the run");

            // Reading it may fail the run, or read as null; anything but its value passes.
            Map<String, Object> output;
            try {
                output = engine.run(new FactMap<>(new Fact<>("x", 2)));
            } catch (UnrulyException e) {
                // The rule that reads it failed: the variable isn't there.
                return;
            }
            if (output != null && sameValue(2, output.get(SEEN))) {
                fail("a later run read the variable 'y' that a failed action declared: " + output);
            }
        });
    }

    /**
     * Changes state the language shares between runs, such as a built-in object or a global, in one run, and checks
     * that a later run doesn't see the change: a language whose runs share a runtime that isn't sealed leaves it for
     * every later run, and a rule fires there although nothing in its run set what it reads. The language may refuse
     * the change when it loads the rule, or keep it from later runs: by failing the action before it changes anything,
     * as a sealed runtime does, or by keeping the change to its run. An action that fails after the change is checked
     * as one that succeeds. A run before the change, in which the rule that makes it doesn't fire, checks first that
     * {@link #sharedStateEquals} is false while nothing has changed the state. Skipped when {@link #changeSharedState}
     * returns {@code null}.
     */
    @Test
    @DisplayName("what an action changes in state the language shares between runs, such as a built-in object or a"
            + " global, isn't seen by a later run")
    void sharedStateStaysLocal() throws Exception {
        String change = changeSharedState("leak", 1);
        assumeTrue(change != null, "the language's actions can reach no state shared between runs");
        String equals = Objects.requireNonNull(sharedStateEquals("leak", 1),
                "sharedStateEquals() returned null, but changeSharedState() didn't");
        // One copy of the rules, so that the later run gets the sessions, and whatever runtime they hold, that the
        // earlier one used.
        closing(oneCopyEngine(language()), engine -> {
            try {
                engine.load(List.of(
                        rule(CHANGES, 2, factEquals("x", 1), change),
                        rule(READS, 1, equals, putFact(SEEN, "x"))));
            } catch (RuleCompilationException e) {
                if (names(CHANGES, ExpressionKind.ACTION, e.getRuleName(), e.getExpressionKind())) {
                    // The language refuses the change when it compiles the rule.
                    return;
                }
                failIfTheReadFailed("load() refused the rule that reads it", e.getRuleName(), e.getExpressionKind(),
                        e);
                throw e;
            }
            // A run in which the rule that changes the state doesn't fire: the condition that reads it must be false.
            Map<String, Object> before;
            try {
                before = engine.run(new FactMap<>(new Fact<>("x", 0)));
            } catch (RuleExecutionException e) {
                failIfTheReadFailed("the run before any change failed", e.getRuleName(), e.getExpressionKind(), e);
                throw e;
            }
            if (before != null && before.containsKey(SEEN)) {
                fail("sharedStateEquals() was true before any action changed the language's shared state, or an"
                        + " earlier engine left it changed: " + before);
            }
            try {
                engine.run(new FactMap<>(new Fact<>("x", 1)));
            } catch (RuleExecutionException e) {
                // An action that fails may have changed the state before it failed, so the later run still looks.
                if (!names(CHANGES, ExpressionKind.ACTION, e.getRuleName(), e.getExpressionKind())) {
                    failIfTheReadFailed("the run that changes it failed", e.getRuleName(), e.getExpressionKind(),
                            e);
                    throw e;
                }
            }
            Map<String, Object> output;
            try {
                output = engine.run(new FactMap<>(new Fact<>("x", 2)));
            } catch (RuleExecutionException e) {
                failIfTheReadFailed("the later run failed", e.getRuleName(), e.getExpressionKind(), e);
                throw e;
            }
            if (output != null) {
                fail("a later run's condition saw what an earlier run's action changed in the language's shared"
                        + " state: " + output);
            }
        });
    }

    /** Whether a failure names the rule and the kind of expression given first. */
    private static boolean names(String rule, ExpressionKind kind, @Nullable String failedRule,
                                 @Nullable ExpressionKind failedKind) {
        return rule.equals(failedRule) && kind == failedKind;
    }

    /**
     * Fails {@code sharedStateStaysLocal} when what failed is the condition from {@link #sharedStateEquals}, which must
     * be false, not fail, while the state doesn't hold its value. Anything else that failed, the check didn't expect,
     * and its caller rethrows.
     */
    private static void failIfTheReadFailed(String what, @Nullable String failedRule,
                                            @Nullable ExpressionKind failedKind, UnrulyException e) {
        if (names(READS, ExpressionKind.CONDITION, failedRule, failedKind)) {
            throw new AssertionFailedError("sharedStateEquals() must be false, not fail, while the shared state doesn't"
                    + " hold its value, but " + what + ", naming its condition: " + describe(e), e);
        }
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
        // The engine rejects "output" and blank names before the language is asked, so either would make this check
        // pass without the language's checkFactName running at all.
        assertNotEquals("output", name, "unusableFactName() must return a name the language itself rejects");
        assertFalse(name.isBlank(), "unusableFactName() must return a name the language itself rejects");
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
                    fail("a rule on the fact name '" + name + "' didn't put its value: "
                            + mismatch(Map.of(SEEN, 1), output));
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
        //
        // A run that failed is repeated, on the one copy of the rules the first run used: a language that checks a
        // compiled condition only on its first evaluation reads the property as null from the second run on.
        closing(oneCopyEngine(language()), engine -> {
            AtomicBoolean loaded = new AtomicBoolean();
            assertThrows(UnrulyException.class, () -> {
                engine.load(List.of(misspelled));
                loaded.set(true);
                engine.run(new FactMap<>(new Fact<>(APPLICANT, new Applicant(750))));
            }, "a misspelled property of a record fact didn't fail");
            assertRunFailsAgain(engine, loaded.get(), new FactMap<>(new Fact<>(APPLICANT, new Applicant(750))),
                    ExpressionKind.CONDITION, "a misspelled property of a record fact");
        });
    }

    @Test
    @DisplayName("copies made and warmed up when the rules load give the same results, one run or several at once")
    void copiesAtLoad() throws Exception {
        closing(builder(language()).copiesAtLoad(2).build(), engine -> {
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

    @Test
    @DisplayName("the engine closes the language's compiler once: when a reload replaces the rules, and when it's"
            + " closed, and closing it doesn't throw")
    void compilerClosed() throws Exception {
        ExpressionLanguage language = language();
        List<AtomicInteger> closes = new CopyOnWriteArrayList<>();
        List<Throwable> closeFailures = new CopyOnWriteArrayList<>();
        // Closed after the check as well, so a failed check still closes the engine. A third close does nothing. One
        // copy made when the rules load, set after configure(), so that each compiler has warmed up a session before
        // it's closed, unless its sessions are Session.none(), which the engine doesn't warm up: a compiler whose
        // close() fails only after a warm-up would pass otherwise.
        try {
            closing(builder(countingCloses(language, closes, closeFailures)).copiesAtLoad(1).build(), engine -> {
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
            fail("a compiler's close() threw " + describe(closeFailures.get(0))
                    + ", which the engine only logs at WARN");
        }
    }

    @Test
    @DisplayName("each copy of the rules gets a session of its own, and closing a session doesn't throw")
    void sessionsClosed() throws Exception {
        SessionWatch sessions = new SessionWatch();
        // Two copies when the rules load. A language that keeps state gets newSession() called twice, once for each
        // copy, and each session warmed up; one that returns Session.none() is asked once, and its copy is shared.
        // Closed however the check ends, so a failed run still closes the sessions.
        sessions.closing(builder(sessions.watching(language())).copiesAtLoad(2).build(), engine -> {
            engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));
            assertSameOutput(Map.of(SEEN, 1), engine.run(new FactMap<>(new Fact<>("x", 1))));
        });

        // The engine closes every session itself, once, so what's left to check is the language's part: a session
        // returned to two copies is used by two runs at once and closed twice, and a close() that throws is only
        // logged at WARN, so nothing else would show either.
        sessions.assertNoneShared();
        sessions.assertNoneThrewOnClose();
    }

    /**
     * Closes a session while another session of the same compiler is in use, as the engine does when a run nested in
     * another ends: the engine keeps one copy of the rules, which the outer run holds, so the nested run gets an extra
     * copy, whose sessions are closed as soon as it ends. The outer run must then finish as if nothing had been
     * closed, so a {@code close()} that tears down what the compiler's sessions share, such as the runtime they all
     * run in, fails the check, and so does one that throws while another session is in use, which the engine only
     * logs at WARN. A language that returns {@link Session#none()} has no session to close, and passes.
     */
    @Test
    @DisplayName("a session closed while another session of its compiler is in use leaves that one working")
    void sessionClosedWhileAnotherRuns() throws Exception {
        SessionWatch sessions = new SessionWatch();
        AtomicReference<RulesEngine<Map<String, Object>>> built = new AtomicReference<>();
        AtomicBoolean nested = new AtomicBoolean();
        AtomicReference<@Nullable Object> nestedOutput = new AtomicReference<>();
        AtomicReference<@Nullable RuntimeException> nestedFailure = new AtomicReference<>();
        // One copy kept and none made when the rules load, set after configure() so it can't change them: the outer
        // run makes the only copy, so the run nested in it gets an extra one.
        RulesEngineBuilder<Map<String, Object>> builder = builder(sessions.watching(language())).maxCopies(1)
                .copiesAtLoad(0).listener(new RuleListener() {
                    @Override
                    public void afterEvaluate(Rule rule, Map<String, @Nullable Object> facts, boolean matched) {
                        // Once, since the nested run evaluates the rule too. The engine only logs what a listener
                        // throws, so what the nested run returns or throws is kept for the check.
                        if ("a".equals(rule.getRuleName()) && nested.compareAndSet(false, true)) {
                            try {
                                nestedOutput.set(built.get().run(new FactMap<>(new Fact<>("x", 1))));
                            } catch (RuntimeException e) {
                                nestedFailure.set(e);
                            }
                        }
                    }
                });
        sessions.closing(builder.build(), engine -> {
            built.set(engine);
            engine.load(List.of(rule("a", 2, factEquals("x", 1), putFact("a", "x")),
                    rule("b", 1, factEquals("x", 1), putFact("b", "x"))));

            // The nested run ends after rule a is evaluated, and before rule b is.
            Map<String, Object> output;
            try {
                output = engine.run(new FactMap<>(new Fact<>("x", 1)));
            } catch (RuleExecutionException e) {
                throw outerRunFailed(e, nested.get(), nestedFailure.get());
            }
            int closedDuringRun = sessions.closed();
            assertTrue(nested.get(), "the listener didn't start a run nested in the check's run");
            RuntimeException failure = nestedFailure.get();
            if (failure instanceof RuleExecutionException e) {
                throw new AssertionFailedError("the run nested in the check's run failed: " + e.getMessage(), e);
            } else if (failure != null) {
                throw failure;
            }
            assertSameOutput(Map.of("a", 1, "b", 1), nestedOutput.get(), "the run nested in the check's run");
            assertSameOutput(Map.of("a", 1, "b", 1), output, "the check's run");
            // What the check is there for: a language with sessions of its own had one closed while the check's run
            // held another.
            if (sessions.anyReturned() && closedDuringRun == 0) {
                fail("the engine closed no session while the check's run was in progress, so no session was closed"
                        + " while another was in use");
            }
        });

        // A close() that throws is only logged at WARN, so nothing else would show it.
        sessions.assertNoneThrewOnClose();
    }

    /**
     * Makes a session on one thread and closes it on another, as the engine does with the sessions of a copy of the
     * rules a run made: the run's thread makes them, and {@code close()}, or a {@code load()} that replaces the rules,
     * closes them on the thread that calls it. A {@code close()} that throws there, as one whose runtime is bound to
     * the thread that made it may, fails the check, since the engine only logs it at WARN. A language that returns
     * {@link Session#none()} has no session to close, and passes.
     */
    @Test
    @DisplayName("a session made on one thread closes without throwing on another")
    void sessionClosedOnAnotherThread() throws Exception {
        SessionWatch sessions = new SessionWatch();
        // No copy made when the rules load, set after configure() so it can't change it: the run makes the one copy,
        // on the worker, and closing the engine closes its sessions, on this thread.
        sessions.closing(builder(sessions.watching(language())).copiesAtLoad(0).build(), engine -> {
            engine.load(List.of(rule("r", 1, factEquals("x", 1), putFact(SEEN, "x"))));
            assertSameOutput(Map.of(SEEN, 1), onItsOwnThread(() -> engine.run(new FactMap<>(new Fact<>("x", 1)))));
        });

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
        // One copy of the rules, so that the other run gets the sessions the details' run used.
        closing(oneCopyEngine(sessions.watching(language())), engine -> {
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
     * Compiles a condition with the language's compiler, outside an engine, and evaluates it against one session, with
     * {@code evaluate} and with {@code evaluateWithDetail}, for a fact that is an {@code Integer} 1 or 2, a
     * {@code Long} 1 or 2, a {@code Short} 1 and a {@code BigDecimal} 1. The engine calls only
     * {@code evaluateWithDetail}, so a language whose {@code evaluate} disagrees with it passes every other check,
     * and fails whoever calls {@code evaluate} directly. A language that doesn't override {@code evaluateWithDetail}
     * passes: the default returns what {@code evaluate} does. So does one that throws from both for a fact, as a
     * language that compares whole numbers by type may; one that throws from only one of them fails. What either
     * throws counts as the engine counts it: an exception or an {@link Error} fails the rule, such as a
     * {@link StackOverflowError} or an {@link AssertionError}, but a fatal one, another {@link VirtualMachineError},
     * is thrown on, and fails the check by itself.
     *
     * <p>
     * An exception or an {@link Error} from closing the session or the compiler doesn't fail this check, unless it's
     * a fatal one: the engine only logs the rest, and {@code sessionsClosed}, {@code sessionClosedWhileAnotherRuns}
     * and {@code sessionClosedOnAnotherThread} are the checks that fail a session whose {@code close()} throws, and
     * {@code compilerClosed} the one that fails a compiler whose {@code close()} throws.
     * </p>
     */
    @Test
    @DisplayName("a condition's evaluate returns the value evaluateWithDetail reports")
    void evaluateAgreesWithDetail() throws Exception {
        // Closed however the check ends, the session before its compiler, as the engine closes them.
        closing(new ClosedQuietly<>(language().newCompiler(compileContext())), compiler -> {
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
                    } catch (Throwable e) {
                        rethrowIfFatal(e);
                        // A language that can't compare this type fails the rule either way, as long as evaluate
                        // does too. The message is built only if it doesn't, since reading e can throw.
                        rethrowIfFatal(assertThrows(Throwable.class, () -> condition.evaluate(evaluation, session),
                                () -> forFact + "evaluateWithDetail threw " + describe(e) + ", but evaluate didn't"));
                        continue;
                    }
                    assertNotNull(detailed,
                            forFact + "evaluateWithDetail returned null, which fails the rule");

                    // Not assertDoesNotThrow, which would turn a fatal error into a failure of the check, and reads
                    // the message of what evaluate threw, which can throw.
                    Object value;
                    try {
                        value = condition.evaluate(evaluation, session);
                    } catch (Throwable e) {
                        rethrowIfFatal(e);
                        throw new AssertionFailedError(forFact + "evaluate threw, but evaluateWithDetail returned "
                                + describe(detailed.value()) + " ==> Unexpected exception thrown: " + describe(e), e);
                    }
                    assertEquals(detailed.value(), value,
                            forFact + "evaluate returned a different value than evaluateWithDetail reported");
                }
            });
        });
    }

    /**
     * Runs one rule list on eight threads at once, 200 runs each, with a different {@code y} in each run, and checks
     * that each run puts its own {@code y}. With {@link #copyThroughVariable}, a second rule copies {@code y} through
     * a variable of its action, so the check is likely to catch an action whose variables every run shares: it fails
     * when another run changes the variable between one run's declaring it and putting it, which so many runs at once
     * make likely but can't make certain. The check also records each session {@code newSession()} returns, and fails
     * when it returns one it returned before, other than {@link Session#none()}, as {@code sessionsClosed} does:
     * {@code sessionsClosed} asks for two sessions only, and this check for as many as the runs going at once need.
     * That failure is reported even when a run failed or returned the wrong output, which a session used by two runs
     * at once usually causes, with the run's failure as its cause.
     *
     * <p>
     * Two limits. A {@code maxCopies(2)} set in {@link #configure} keeps the engine at two copies of the rules, so a
     * {@code newSession()} that starts returning a session twice after its second call is never reached. And a run that
     * throws, with no session returned twice, fails the check with the worker's
     * {@link java.util.concurrent.ExecutionException}, which wraps what the engine threw.
     * </p>
     */
    @Test
    @DisplayName("concurrent runs of one rule list each see their own facts, action variables and sessions")
    void concurrentRuns() throws Exception {
        SessionWatch sessions = new SessionWatch();
        String copy = copyThroughVariable("copied", "y");
        try {
            concurrentRuns(sessions, copy);
        } catch (InterruptedException e) {
            // Interrupted while it waited for a worker: the thread stays interrupted, whatever is reported.
            Thread.currentThread().interrupt();
            sessions.assertNoneShared(e);
            throw e;
        } catch (Exception | AssertionError e) {
            // A session returned to two copies is what made the runs go wrong, so that is what's reported.
            sessions.assertNoneShared(e);
            throw e;
        }

        // A session returned to two copies is used by two runs at once, which is what this check is for.
        sessions.assertNoneShared();
    }

    private void concurrentRuns(SessionWatch sessions, @Nullable String copy) throws Exception {
        sessions.closing(engine(sessions.watching(language())), engine -> {
            List<Rule> rules = new ArrayList<>();
            rules.add(rule("r", 1, factEquals("x", 1), putFact(SEEN, "y")));
            if (copy != null) {
                rules.add(rule("v", 2, factEquals("x", 1), copy));
            }
            engine.load(rules);
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
                            Map<String, Object> fired = copy != null ? Map.of(SEEN, y, "copied", y) : Map.of(SEEN, y);
                            expected.add(x == 1 ? fired : null);
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

    /**
     * Starts a run from inside an action, on the action's own thread: the action reads the {@code value} of a
     * {@link Nesting} fact, whose getter runs the same engine again. A language that keeps a run's state on the stack,
     * or in its {@link Session}, of which the nested run gets its own, passes. One that keeps what an expression works
     * on in per-thread state, such as a {@link ThreadLocal} or a static, and looks it up again after the read, finds
     * the nested run's state there: the outer run's value goes into the nested run's output and is missing from its
     * own, and nothing fails.
     */
    @Test
    @DisplayName("a run started inside an action, on its thread, leaves the action writing to its own run's output")
    void nestedRunInsideAnAction() throws Exception {
        String action = putFactProperty(SEEN, NEST, NEST_VALUE);
        assumeTrue(action != null, "the language's actions can't read a fact's property");
        closing(engine(), engine -> {
            engine.load(List.of(rule(OUTER, 2, factEquals("x", 1), action),
                    rule(INNER, 1, factEquals("x", 2), putFact(INNER, "x"))));
            Nesting nest = new Nesting(() -> engine.run(new FactMap<>(new Fact<>("x", 2))));

            Map<String, Object> output = onItsOwnThread(() -> runAround(engine, nest, "action", null));
            assertTrue(nest.started, "the action didn't read nest.value, so no run was started inside it");
            RuntimeException failure = nest.nestedFailure;
            if (failure != null) {
                throw new AssertionFailedError("the run started inside the action failed: " + message(failure),
                        failure);
            }
            assertSameOutput(Map.of(INNER, 2), nest.nestedOutput, "the run started inside the action");
            assertSameOutput(Map.of(SEEN, 7), output, "the run around it");
        });
    }

    /**
     * Starts a run from inside a condition, on the condition's own thread, as {@code nestedRunInsideAnAction} does
     * from inside an action: the condition, {@link #bothConditions}, reads the {@code value} of a {@link Nesting}
     * fact, whose getter runs the same engine again, and then reads {@code x}. A language that keeps what a condition
     * works on in per-thread state, such as a {@link ThreadLocal} or a static, and looks it up again after the first
     * read, reads the nested run's {@code x} there: the outer rule doesn't fire, and nothing fails.
     */
    @Test
    @DisplayName("a run started inside a condition, on its thread, leaves the condition reading its own run's facts")
    void nestedRunInsideACondition() throws Exception {
        String condition = bothConditions(factProperty(NEST, NEST_VALUE, 7), factEquals("x", 1));
        assumeTrue(condition != null, "the language's conditions can't require both of two conditions");
        closing(engine(), engine -> {
            engine.load(List.of(rule(OUTER, 2, condition, putFact(SEEN, "x")),
                    rule(INNER, 1, factEquals("x", 2), putFact(INNER, "x"))));
            // The nested run evaluates the outer rule's condition too, so it gets a nest of its own that runs nothing.
            Nesting nest = new Nesting(() -> engine.run(new FactMap<>(new Fact<>("x", 2),
                    new Fact<>(NEST, new Nesting(() -> null)))));

            Map<String, Object> output = onItsOwnThread(() -> runAround(engine, nest, "condition", null));
            assertTrue(nest.started, "the condition didn't read nest.value, so no run was started inside it");
            RuntimeException failure = nest.nestedFailure;
            if (failure != null) {
                throw new AssertionFailedError("the run started inside the condition failed: " + message(failure),
                        failure);
            }
            assertSameOutput(Map.of(INNER, 2), nest.nestedOutput, "the run started inside the condition");
            assertSameOutput(Map.of(SEEN, 1), output, "the run around the run started inside its condition");
        });
    }

    /**
     * Starts a run from inside a condition, as {@code nestedRunInsideACondition} does, and makes that run fail: its
     * own {@code nest.value} throws. A language that puts back the per-thread state its condition replaced when the
     * condition returns, but not in a {@code finally}, leaves the failed run's state on the thread, and the condition
     * that started it, which the failure didn't reach, reads the failed run's {@code x}: the outer rule doesn't fire,
     * and nothing fails but the nested run. The check is skipped when the nested run neither fails nor reads the
     * getter that throws, as with a language that evaluates the right side of {@link #bothConditions} first, and it
     * fails when the nested run reads it and doesn't fail, or fails for another reason. A language that reads every
     * fact's properties before it sets any state fails the nested run first, and isn't checked by it.
     */
    @Test
    @DisplayName("a run that fails inside a condition, on its thread, leaves the condition reading its own run's facts")
    void nestedRunFailsInsideACondition() throws Exception {
        String condition = bothConditions(factProperty(NEST, NEST_VALUE, 7), factEquals("x", 1));
        assumeTrue(condition != null, "the language's conditions can't require both of two conditions");
        closing(engine(), engine -> {
            engine.load(List.of(rule(OUTER, 2, condition, putFact(SEEN, "x")),
                    rule(INNER, 1, factEquals("x", 2), putFact(INNER, "x"))));
            Nesting failing = Nesting.failing();
            Nesting nest = new Nesting(() -> engine.run(new FactMap<>(new Fact<>("x", 2),
                    new Fact<>(NEST, failing))));

            Map<String, Object> output = onItsOwnThread(() -> runAround(engine, nest, "condition", failing));
            assertNestedRunFailed(nest, failing, "condition");
            assertSameOutput(Map.of(SEEN, 1), output, "the run around the run that failed inside its condition");
        });
    }

    /**
     * Starts a run from inside an action, as {@code nestedRunInsideAnAction} does, and makes that run fail: its own
     * action reads a {@code nest.value} that throws. A language that puts back the per-thread state its action
     * replaced when the action returns, but not in a {@code finally}, leaves the failed run's state on the thread, and
     * the action that started it, which the failure didn't reach, writes to the failed run's output: the value is
     * missing from its own run's output, and nothing fails but the nested run. As in
     * {@code nestedRunFailsInsideACondition}, the check is skipped when the nested run neither fails nor reads the
     * getter that throws, and fails when it reads it and doesn't fail, or fails for another reason.
     */
    @Test
    @DisplayName("a run that fails inside an action, on its thread, leaves the action writing to its own run's output")
    void nestedRunFailsInsideAnAction() throws Exception {
        String action = putFactProperty(SEEN, NEST, NEST_VALUE);
        assumeTrue(action != null, "the language's actions can't read a fact's property");
        closing(engine(), engine -> {
            engine.load(List.of(rule(OUTER, 2, factEquals("x", 1), action),
                    rule(INNER, 1, factEquals("x", 2), putFact(INNER, "x"))));
            // x is 1, so that the nested run's own outer action reads its nest.value, and fails.
            Nesting failing = Nesting.failing();
            Nesting nest = new Nesting(() -> engine.run(new FactMap<>(new Fact<>("x", 1),
                    new Fact<>(NEST, failing))));

            Map<String, Object> output = onItsOwnThread(() -> runAround(engine, nest, "action", failing));
            assertNestedRunFailed(nest, failing, "action");
            assertSameOutput(Map.of(SEEN, 7), output, "the run around the run that failed inside its action");
        });
    }

    /**
     * Runs a check's run on a thread of its own, which ends with it, and waits for it for up to 30 seconds. The
     * nested-run checks use it so that per-thread state a language leaves behind when it fails the check can't fail a
     * later check on JUnit's thread; the run nested in the check's run starts on that thread too, so what the check
     * checks is unchanged. {@code sessionClosedOnAnotherThread} uses it to make a session on a thread other than the
     * one that closes it. What the run throws is thrown here as it was; a run still going after 30 seconds fails the
     * check, and a wait that is interrupted leaves the thread interrupted.
     */
    // stop() shuts the thread down, and what the run threw is thrown as it was, not wrapped.
    @SuppressWarnings({"PMD.CloseResource", "PMD.PreserveStackTrace"})
    private static <T> T onItsOwnThread(Callable<T> run) throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            return thread.submit(run).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            throw new AssertionFailedError("the run didn't return within 30 seconds", e);
        } catch (ExecutionException e) {
            Throwable thrown = e.getCause();
            if (thrown instanceof Error error) {
                throw error;
            }
            throw (Exception) thrown;
        } finally {
            stop(thread);
        }
    }

    /**
     * Runs the rules with {@code x} 1 and {@code nest}, and reports a failed run by what happened in it: a run that
     * failed before its expression started the nested run is reported as the engine reported it.
     *
     * @param where   Where the nested run starts, {@code "condition"} or {@code "action"}, which the failure says
     * @param failing The {@link Nesting} the check makes the nested run fail with, or {@code null}: a nested failure
     *                that is the one it planned isn't a second defect
     */
    private static Map<String, Object> runAround(RulesEngine<Map<String, Object>> engine, Nesting nest, String where,
                                                 @Nullable Nesting failing) {
        try {
            return engine.run(new FactMap<>(new Fact<>("x", 1), new Fact<>(NEST, nest)));
        } catch (RuleExecutionException e) {
            if (!nest.started) {
                throw e;
            }
            RuntimeException nestedFailure = nest.nestedFailure;
            throw runAroundNestedFailed(e, where, nestedFailure,
                    failing != null && nestedFailure != null && failedAsPlanned(nestedFailure, failing));
        }
    }

    /**
     * Checks that {@code nest} started its nested run, and that the run failed because it read {@code failing}, as
     * {@link #failedAsPlanned} decides. The check is skipped when the nested run neither failed nor read
     * {@code failing}, since it can't be made to fail then; a nested run that read it and didn't fail, or failed for
     * another reason, proves nothing about what a failed one leaves, and fails the check.
     *
     * @param where Where the nested run starts, {@code "condition"} or {@code "action"}, which the failure says
     */
    private static void assertNestedRunFailed(Nesting nest, Nesting failing, String where) {
        assertTrue(nest.started, "the " + where + " didn't read nest.value, so no run was started inside it");
        RuntimeException failure = nest.nestedFailure;
        String nested = "the run started inside the " + where;
        if (failure == null) {
            assumeTrue(failing.started, nested + " never read its own nest.value, whose getter makes it fail, as a"
                    + " language that reads the right side of a condition first may not");
            throw new AssertionFailedError(nested + " didn't fail, though a getter its rule read threw: it returned "
                    + describe(nest.nestedOutput));
        }
        if (!failedAsPlanned(failure, failing)) {
            throw new AssertionFailedError(nested + (failing.started
                    ? " failed, but its failure doesn't carry what its nest.value threw, as a cause, a suppressed"
                    + " exception or by its message: "
                    : " failed for another reason than its nest.value, which throws: ") + describe(failure), failure);
        }
    }

    /**
     * Whether a nested run's failure is the one {@code failing} made: a {@link RuleExecutionException} that carries,
     * among its causes and suppressed exceptions and theirs, one of the exceptions the getter of {@code failing}
     * threw, or an exception whose message contains {@link #PLANNED_FAILURE}, as one a language rethrows with that
     * message does. The walk is bounded, in case exceptions name each other.
     */
    // By identity: the instances the getter threw are what the failure must carry.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean failedAsPlanned(RuntimeException failure, Nesting failing) {
        if (!(failure instanceof RuleExecutionException)) {
            return false;
        }
        Deque<Throwable> toVisit = new ArrayDeque<>();
        toVisit.add(failure);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!toVisit.isEmpty() && visited.size() < 100) {
            Throwable next = toVisit.pop();
            if (!visited.add(next)) {
                continue;
            }
            if (failing.thrown.stream().anyMatch(thrown -> thrown == next) || carriesPlannedFailure(next)) {
                return true;
            }
            toVisit.addAll(linked(next));
        }
        return false;
    }

    /** An exception's cause and suppressed exceptions; none, for one whose own methods throw when they're read. */
    private static List<Throwable> linked(Throwable thrown) {
        try {
            List<Throwable> linked = new ArrayList<>(Arrays.asList(thrown.getSuppressed()));
            Throwable cause = thrown.getCause();
            if (cause != null) {
                linked.add(0, cause);
            }
            return linked;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Whether an exception's message contains {@link #PLANNED_FAILURE}; one that can't be read doesn't. */
    private static boolean carriesPlannedFailure(Throwable thrown) {
        try {
            String message = thrown.getMessage();
            return message != null && message.contains(PLANNED_FAILURE);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
