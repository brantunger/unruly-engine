package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;

/**
 * Runs rules against facts: it evaluates each {@link Rule}'s condition and fires the actions of the rules that match,
 * as its match policy decides. Build one with {@link RulesEngineBuilder}, which sets its match policy, expression
 * languages, imports, listeners, limit on compiled copies and run timeout once.
 *
 * <p>
 * <b>Lifecycle:</b> compile the rules with {@link #load(List)}, then call {@link #run(FactStore)} or
 * {@link #runWithResult(FactStore)} as often as needed, from any number of threads. Rules are evaluated in descending
 * priority order; equal priorities keep their list order, and a {@code null} priority sorts last. {@code load} may be
 * called again at any time to swap in new rules atomically. Close the engine with {@link #close()} once it's no longer
 * needed.
 * </p>
 *
 * <p>
 * <b>Implementing:</b> you may implement this interface, for example to decorate an engine or as a test double.
 * Implement {@link #load(List)}, {@link #validate(List)}, {@link #runWithResult(FactStore, RunOptions)} and
 * {@link #rules()}; {@link #run(FactStore)}, {@link #runWithResult(FactStore)} and {@link #close()} have defaults. A
 * method added in a later 2.x release is a {@code default} method, so an existing implementation keeps compiling.
 * {@link RunResult#of(Object, List, List, String)} and {@link RuleSetInfo#of(List, String, java.time.Instant)} create
 * the values an implementation returns.
 * </p>
 *
 * @param <O> The output object type to instantiate
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/thread-safety.md">Thread safety</a>
 * @see <a href=
 *      "https://github.com/brantunger/unruly-engine/blob/main/docs/error-handling.md#-exceptions-by-method">Exceptions
 *      by method</a>
 */
public interface RulesEngine<O> extends AutoCloseable {

    /**
     * Compiles a rule list and swaps it in for the rules loaded before, if any.
     *
     * @param ruleList The list of {@link Rule} objects
     * @throws io.github.brantunger.unruly.api.exception.RuleCompilationException if the list contains a {@code null}
     *         rule; two rules share a name; a condition or action is blank; a rule is written in a language the engine
     *         doesn't have; a condition or action has a syntax error its language detects, or is one its language
     *         rejects, such as a condition with an assignment; a language throws while creating its compiler, or
     *         returns {@code null} instead of a compiler or a compiled expression; a {@link LinkageError} such as a
     *         {@link NoClassDefFoundError} is thrown for a class a rule uses, which means the rule is misconfigured
     *         rather than the JVM failing; or a {@link RulesEngineBuilder#fact(String, Class) declared fact} has a name
     *         the rules' languages can't refer to. A {@code null} rule or a duplicate name is thrown at once. The
     *         other problems are collected before the exception is thrown, and
     *         {@link RuleCompilationException#failures() failures()} lists them: each broken rule, a language that
     *         can't create its compiler (once, in place of the first rule that needed it; the rules written in it
     *         aren't compiled and get no failure of their own), and each declared fact name the compilers that were
     *         created reject. Once the rules compile, an engine built with
     *         {@link RulesEngineBuilder#copiesAtLoad(int) copiesAtLoad(n)} makes its copies of them, and a language
     *         that throws while creating or warming up a session for one, or returns {@code null} instead of a
     *         session, fails the load with this exception, naming the language, on its own.
     * @throws IllegalStateException if the engine is closed
     * @throws NullPointerException if {@code ruleList} itself is {@code null}
     * @throws Error                 a {@link VirtualMachineError} other than {@link StackOverflowError} thrown while
     *                               compiling or making copies, also as the cause of another exception, is logged and
     *                               then rethrown unchanged
     */
    void load(List<Rule> ruleList);

    /**
     * Compiles a rule list exactly as {@link #load(List)} would, with this engine's languages, imports, options and
     * declared facts, without loading it, and returns the problems {@code load()} would have thrown instead of
     * throwing. The two lists aren't quite the same, and the paragraphs below say how. The rules loaded, if any, are
     * unchanged, and the compiled result is discarded: a later {@code load()} compiles the list again. Nothing about
     * the rules is logged, not even a language's compile warnings; a fatal {@link Error} is logged at ERROR before
     * it's rethrown, and a compiler that fails to close at WARN, as always.
     *
     * <p>
     * One problem this method can't report: it makes no copies. An engine built with
     * {@link RulesEngineBuilder#copiesAtLoad(int) copiesAtLoad(n)} above zero makes them once the rules compile, and a
     * language that throws while creating or warming up a session for one, or returns {@code null} instead of a
     * session, fails that {@code load()}, naming the language. With the default {@code copiesAtLoad(0)} nothing is
     * outside what it can see.
     * </p>
     *
     * <p>
     * The problems come in the order {@code load()} finds them: a {@code null} entry or a duplicate name, in list
     * order; then each rule that doesn't compile, in priority order, with a language that can't create its compiler
     * in place of the first rule that needed it; then each declared fact name the languages reject. Unlike
     * {@code load()}, a {@code null} entry or a duplicate name doesn't stop the check: every other rule is still
     * compiled.
     * </p>
     *
     * @param ruleList The list of {@link Rule} objects
     * @return One exception for each problem found, as {@code load()} would have reported it; empty when it finds none
     * @throws IllegalStateException if the engine is closed
     * @throws NullPointerException  if the list itself is {@code null}
     * @throws Error                 a {@link VirtualMachineError} other than {@link StackOverflowError} thrown while
     *                               compiling, also as the cause of another exception, is logged and then rethrown
     *                               unchanged
     */
    List<RuleCompilationException> validate(List<Rule> ruleList);

    /**
     * Runs the loaded rules against facts.
     *
     * @param facts The facts to run the rules against. Any {@link FactStore} is accepted, such as a
     *              {@code FactMap<Applicant>}. The engine reads them through {@link FactStore#asMap()}.
     * @return The output of the actions of the matching {@link Rule} objects, or {@code null} if the rule list is
     *         empty or no rule matched
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException if a condition or action throws; a
     *         condition evaluates to {@code null} or a non-boolean; an action returns {@code null} instead of an
     *         {@link io.github.brantunger.unruly.api.language.ActionResult}, or a property it returned can't be set
     *         on the output; the output supplier throws or returns {@code null}; a language throws or returns
     *         {@code null} when it creates a session for the run; or more than one rule matches on a
     *         {@link RulesEngineBuilder#uniqueMatch(java.util.function.Supplier) unique-match} engine, which names
     *         them all and belongs to no rule. Also if the run must stop, which is checked while it waits for a
     *         compiled copy of the rules, while it reads the engine's rules again after a reload or {@link #close()}
     *         closed the list it had read, between rules and when each condition or action returns, because its
     *         thread was interrupted, which keeps the interrupt status
     *         set and makes the cause an {@link InterruptedException}, or because it passed the deadline a
     *         {@link RulesEngineBuilder#runTimeout(Duration) timeout} gave it, which makes the cause a
     *         {@link java.util.concurrent.TimeoutException}. Either belongs to no rule, so {@code getRuleName()} is
     *         {@code null}.
     * @throws IllegalArgumentException if a fact is named {@code output} or {@code null}, or has a name that the
     *         language of a loaded rule can't refer to (a rule list without rules is checked against the engine's
     *         default language); if a {@link RulesEngineBuilder#fact(String, Class) declared fact} has a non-null
     *         value that isn't an instance of its type; or, with {@link RulesEngineBuilder#requireDeclaredFacts()},
     *         if a declared fact is missing or an undeclared one is supplied. Also if a language's check of a fact
     *         name fails with any other exception, which becomes the cause.
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed; or if the
     *         engine's rule list was closed over and over while the run was borrowing a copy of it, which means
     *         an engine invariant has broken rather than that the call was wrong
     * @throws NullPointerException if {@code facts} is {@code null}
     * @throws Error                 a {@link VirtualMachineError} other than {@link StackOverflowError}, wherever it
     *                               arises (a rule or Java code it calls, the output supplier, an output writer, a
     *                               language checking a name, creating a session or closing one, or a listener), is
     *                               rethrown unchanged, also when it arrives as the cause of another exception.
     *                               Every other {@link Error} from a rule, the output supplier, an output writer or
     *                               a language creating a session, including a {@link LinkageError}, is reported as
     *                               a {@code RuleExecutionException}; one from a language's check of a fact name as
     *                               an {@code IllegalArgumentException}; one from a listener is logged, and the run
     *                               goes on; one from closing a session is logged at WARN
     */
    default @Nullable O run(FactStore<?> facts) {
        return runWithResult(facts).output();
    }

    /**
     * Runs the rules like {@link #run(FactStore)}, and reports what the run did: the output object, the rules that
     * fired, what each rule's condition evaluated to, the checksum of the rules the run used, and the run's
     * {@link RunResult#tags() tags} and {@link RunResult#startedAt() start instant}. A caller can record which rules
     * produced a decision, and why the others didn't apply, without a {@link RuleListener}.
     *
     * @param facts The facts to run the rules against, as {@link #run(FactStore)} takes them
     * @return What the run did, never {@code null}. Its {@link RunResult#output() output} is {@code null} exactly when
     *         no rule fired, because the rule list is empty or no condition matched, and its
     *         {@link RunResult#evaluations() evaluations} have one entry per loaded rule.
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException as {@link #run(FactStore)} throws it
     * @throws IllegalArgumentException as {@link #run(FactStore)} throws it
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed; or if the
     *         engine's rule list was closed over and over while the run was borrowing a copy of it, which means
     *         an engine invariant has broken rather than that the call was wrong
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    default RunResult<O> runWithResult(FactStore<?> facts) {
        return runWithResult(facts, RunOptions.defaults());
    }

    /**
     * Runs the rules like {@link #runWithResult(FactStore)}, with settings for this run only, such as a
     * {@link RunOptions#withTimeoutOf(Duration) timeout} that replaces the one the engine was built with.
     *
     * <p>
     * A timeout's deadline is taken from when this method is called, so waiting for a compiled copy of the rules
     * counts towards it. The engine checks it while waiting, while the run reads the engine's rules again after a
     * reload or {@link #close()} closed the list it had read, and before and after each condition and each action, so
     * a run stops between rules, and when the condition or action that was running returns; see
     * {@link RulesEngineBuilder#runTimeout(Duration)} for what that does and doesn't stop.
     * </p>
     *
     * @param facts   The facts to run the rules against, as {@link #run(FactStore)} takes them
     * @param options The settings for this run; {@link RunOptions#defaults()} changes nothing
     * @return What the run did, as {@link #runWithResult(FactStore)} reports it
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException as {@link #run(FactStore)} throws it,
     *         and with a {@link java.util.concurrent.TimeoutException} cause if the run passes its deadline, or an
     *         {@link InterruptedException} cause if its thread is interrupted, which keeps the interrupt status set
     * @throws IllegalArgumentException as {@link #run(FactStore)} throws it
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed; or if the
     *         engine's rule list was closed over and over while the run was borrowing a copy of it, which means
     *         an engine invariant has broken rather than that the call was wrong
     * @throws NullPointerException if an argument is {@code null}
     */
    RunResult<O> runWithResult(FactStore<?> facts, RunOptions options);

    /**
     * Returns the rules the engine has loaded, their checksum and when they were loaded. A run started before a reload
     * finishes with the rules it started with, so compare {@link RunResult#ruleSetChecksum()} with
     * {@link RuleSetInfo#checksum()} rather than assuming they match.
     *
     * @return The loaded rules, never {@code null}. Before the first {@link #load(List)} it has no rules, the checksum
     *         of an empty rule list, and no {@link RuleSetInfo#loadedAt() load time}.
     * @throws IllegalStateException if the engine is closed
     */
    RuleSetInfo rules();

    /**
     * Closes the engine, and releases what its expression languages hold for the rules, such as interpreter contexts.
     * A run holding a copy of the rules finishes normally, and so does one waiting for a copy, because a rule list
     * can't close under a run that has begun borrowing from it: the languages' sessions are closed as each run
     * returns, and their compilers after the last one. Afterwards, {@link #run(FactStore)} and {@link #load(List)}
     * throw {@link IllegalStateException} — as does a run that had read the rules but had not yet begun to borrow a
     * copy when this method closed them, because it reads them again and finds a closed engine. Closing an engine
     * that is already closed does nothing.
     *
     * <p>
     * A failure to close a session or a compiler is logged at WARN and not thrown, except a fatal {@link Error}, which
     * is rethrown unchanged. By default, this method does nothing.
     * </p>
     */
    @Override
    default void close() {
        // Nothing to release.
    }
}
