package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;

/**
 * The RulesEngine fires the action expression from a list of {@link Rule} objects when their conditions evaluate to
 * <strong>true</strong>. Build one with {@link RulesEngineBuilder}, which sets its expression languages, imports,
 * listeners and limit on compiled copies once.
 *
 * <p>
 * <b>Lifecycle:</b> compile the rules with {@link #load(List)}, then call {@link #run(FactStore)} or
 * {@link #runWithResult(FactStore)} as often as needed, from any number of threads. Rules are evaluated in descending priority order; equal priorities keep their list order,
 * and a {@code null} priority sorts last. {@code load} may be called again at any time to swap in new rules atomically.
 * Close the engine with {@link #close()} once it's no longer needed.
 * </p>
 *
 * <p>
 * <b>Implementing:</b> you may implement this interface, for example to decorate an engine or as a test double.
 * Implement {@link #load(List)}, {@link #runWithResult(FactStore, RunOptions)} and {@link #rules()};
 * {@link #run(FactStore)}, {@link #runWithResult(FactStore)} and {@link #close()} have defaults. A method added in a later 2.x release is a {@code default} method, so an existing
 * implementation keeps compiling. {@link RunResult#of(Object, List, String)} and
 * {@link RuleSetInfo#of(List, String, java.time.Instant)} create the values an implementation returns.
 * </p>
 *
 * @param <O> The output object type to instantiate
 */
public interface RulesEngine<O> extends AutoCloseable {

    /**
     * Compiles a rule list and swaps it in for the rules loaded before, if any.
     *
     * @param ruleList The list of {@link Rule} objects
     * @throws io.github.brantunger.unruly.api.exception.RuleCompilationException if a rule fails to compile, has a
     *         blank condition or action, has a condition that contains an assignment or {@code import_static}, shares
     *         its name with another rule, is written in a language the engine doesn't have, or if the list contains a
     *         {@code null} rule. Also if an expression language throws while creating its compiler, or returns
     *         {@code null} instead of a compiler or a compiled expression. That includes a {@link LinkageError} such as
     *         a {@link NoClassDefFoundError} for a class a rule uses, which means the rule is misconfigured rather than
     *         the JVM failing. A {@link VirtualMachineError} other than {@link StackOverflowError} thrown while
     *         compiling, also as the cause of another exception, is logged and then rethrown unchanged.
     * @throws IllegalStateException if the engine is closed
     * @throws NullPointerException if {@code ruleList} itself is {@code null}
     */
    void load(List<Rule> ruleList);

    /**
     * Fire rules engine against the rules supplied by the rules list.
     *
     * @param facts The facts to run the rules against. Any {@link FactStore} is accepted, such as a
     *              {@code FactMap<Applicant>}. The engine reads them through {@link FactStore#asMap()}.
     * @return The output of firing the actions of the matching {@link Rule} objects, or {@code null} if the rule
     *         list is empty or no rule matched
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException if evaluating a condition or executing
     *         an action fails, a condition doesn't evaluate to a boolean, the output factory throws or returns
     *         {@code null}, or a compiled condition or action throws or returns {@code null} when it is copied for the
     *         run. Also if the run must stop between rules, because its thread was interrupted, which keeps the
     *         interrupt status set and makes the cause an {@link InterruptedException}, or because it passed the
     *         deadline a {@link RulesEngineBuilder#runTimeout(Duration) timeout} gave it, which makes the cause a
     *         {@link java.util.concurrent.TimeoutException}. Either belongs to no rule, so {@code getRuleName()} is
     *         {@code null}.
     * @throws IllegalArgumentException if a fact is named {@code output} or {@code null}, or has a name that the
     *         language of a loaded rule can't refer to. In MVEL, that is a name that isn't a Java identifier, a
     *         reserved word such as {@code empty} or {@code in}, or a class name MVEL resolves, such as {@code Math}
     *         or a class from an imported package. A rule list without rules is checked against the engine's default
     *         language. Also if a language's check of a fact name fails with any other exception, which becomes the
     *         cause.
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    default @Nullable O run(FactStore<?> facts) {
        return runWithResult(facts).output();
    }

    /**
     * Fires the rules like {@link #run(FactStore)}, and reports what the run did: the output object, the rules that
     * fired, and the checksum of the rules the run used. A caller can record which rules produced a decision without
     * a {@link RuleListener}.
     *
     * @param facts The facts to run the rules against, as {@link #run(FactStore)} takes them
     * @return What the run did, never {@code null}. Its {@link RunResult#output() output} is {@code null} exactly when
     *         no rule fired, because the rule list is empty or no condition matched.
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException as {@link #run(FactStore)} throws it
     * @throws IllegalArgumentException as {@link #run(FactStore)} throws it
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    default RunResult<O> runWithResult(FactStore<?> facts) {
        return runWithResult(facts, RunOptions.defaults());
    }

    /**
     * Fires the rules like {@link #runWithResult(FactStore)}, with settings for this run only, such as a
     * {@link RunOptions#timeout(Duration) timeout} that replaces the one the engine was built with.
     *
     * <p>
     * A timeout's deadline is taken from when this method is called, so waiting for a compiled copy of the rules
     * counts towards it. The engine checks it while waiting, and before each condition and each action, so a run
     * stops between rules; see {@link RulesEngineBuilder#runTimeout(Duration)} for what that does and doesn't stop.
     * </p>
     *
     * @param facts   The facts to run the rules against, as {@link #run(FactStore)} takes them
     * @param options The settings for this run; {@link RunOptions#defaults()} changes nothing
     * @return What the run did, as {@link #runWithResult(FactStore)} reports it
     * @throws io.github.brantunger.unruly.api.exception.RuleExecutionException as {@link #run(FactStore)} throws it,
     *         and with a {@link java.util.concurrent.TimeoutException} cause if the run passes its deadline, or an
     *         {@link InterruptedException} cause if its thread is interrupted, which keeps the interrupt status set
     * @throws IllegalArgumentException as {@link #run(FactStore)} throws it
     * @throws IllegalStateException if {@link #load(List)} has not been called, or the engine is closed
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
     * Runs in progress finish first: the languages' sessions are closed as each run returns, and their compilers after
     * the last one. Afterwards, {@link #run(FactStore)} and {@link #load(List)} throw
     * {@link IllegalStateException}. Closing an engine that is already closed does nothing.
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
