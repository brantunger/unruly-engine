package io.github.brantunger.unruly.core;

import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.internal.Causes;
import io.github.brantunger.unruly.internal.IsoInstant;
import io.github.brantunger.unruly.internal.Text;

/**
 * Runs a run's rules for its engine: evaluates their conditions, runs their actions and sets the properties the
 * actions return on the output object, creates that object, stops a run that was cancelled before or during a rule,
 * reports each rule's failure to the listeners through {@link ListenerNotifier}, and records each rule in a Flight
 * Recorder event. The engine decides which rules fire, with the matches this finds, inside the scope it opens for the
 * run.
 *
 * @param <O> The output object to instantiate
 */
final class RuleExecutor<O> {

    /**
     * What evaluating the conditions found: the rules that matched, in evaluation order, and every rule's outcome. A
     * plain class, not a record: nothing compares or prints one, and a record's own methods would link through
     * {@code ObjectMethods}, which can fail for good when first called deep in the stack (#996).
     */
    // Each accessor is named after the field it returns, as a record's are.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    static final class Matches {
        private final List<CompiledRule> matched;
        private final List<RuleEvaluation> evaluations;

        /**
         * @param matched     The rules whose condition was true, in evaluation order. The list is the engine's own
         *                    and isn't published, so it isn't copied.
         * @param evaluations One evaluation for every rule, in evaluation order; immutable, for the run's result
         */
        Matches(List<CompiledRule> matched, List<RuleEvaluation> evaluations) {
            this.matched = matched;
            this.evaluations = evaluations;
        }

        /** The rules whose condition was true, in evaluation order. */
        List<CompiledRule> matched() {
            return matched;
        }

        /** One evaluation for every rule, in evaluation order; immutable, for the run's result. */
        List<RuleEvaluation> evaluations() {
            return evaluations;
        }
    }

    /** The body of one run: what the engine does with the rules, its copy of them and the run's facts. */
    @FunctionalInterface
    interface RunBody<O> {

        /**
         * Runs the rules.
         *
         * @param rules The rule set the run uses
         * @param copy  The run's copy of the rules
         * @param facts The run's fact values, already checked, and the views built over them
         * @return What the run did
         */
        RunResult<O> run(CopyPool rules, CopyPool.Copy copy, RunFacts facts);
    }

    // The engine's logger, under its documented name.
    private final Logger log;
    // Calls the listeners, and reports the failures of rules and runs to them.
    private final ListenerNotifier notifier;
    // The engine's number, which each rule's Flight Recorder event carries.
    private final long engineId;
    // What sets the properties actions return.
    private final OutputWriter<? super O> outputWriter;
    // Makes each run's output object, once a run has a rule to fire.
    private final Supplier<O> outputFactory;

    /**
     * Creates the executor of an engine's runs.
     *
     * @param log           The engine's logger
     * @param notifier      What calls the engine's listeners
     * @param engineId      The engine's number, for its Flight Recorder events
     * @param outputWriter  What sets the properties actions return
     * @param outputFactory What makes each run's output object, not {@code null}
     */
    RuleExecutor(Logger log, ListenerNotifier notifier, long engineId, OutputWriter<? super O> outputWriter,
                 Supplier<O> outputFactory) {
        this.log = log;
        this.notifier = notifier;
        this.engineId = engineId;
        this.outputWriter = outputWriter;
        this.outputFactory = outputFactory;
    }

    /**
     * Evaluates the rules' conditions one after another, in list order, recording each rule's outcome, and keeps the
     * rules that matched. An engine that fires only the first match stops evaluating there: the rules after it are
     * recorded as not evaluated, so a broken condition among them can't fail a run that is already decided. A rule
     * the run skips is recorded as skipped wherever it is, and no listener hears about it.
     *
     * @param ruleList   The rules, in evaluation order
     * @param copy       The run's copy of the rules, whose sessions the conditions run with
     * @param facts      The run's facts and the views built over them
     * @param untilFirst Whether to stop evaluating at the first match
     * @return The matched rules and every rule's outcome
     * @throws RuleExecutionException if a condition fails, or the run was cancelled before one
     */
    Matches match(List<CompiledRule> ruleList, CopyPool.Copy copy, RunFacts facts, boolean untilFirst) {
        List<CompiledRule> matched = new ArrayList<>();
        List<RuleEvaluation> evaluations = new ArrayList<>(ruleList.size());
        for (CompiledRule rule : ruleList) {
            evaluations.add(evaluation(rule, copy, facts, matched, untilFirst));
        }
        // An immutable copy, which the result's own List.copyOf then keeps as it is rather than copying again.
        return new Matches(matched, List.copyOf(evaluations));
    }

    /**
     * Evaluates one rule's condition, unless the run skips the rule or is decided already, and adds the rule to
     * {@code matched} when the condition is true.
     *
     * @return The rule's outcome, with the detail its language explained the condition with, if it did
     */
    private RuleEvaluation evaluation(CompiledRule rule, CopyPool.Copy copy, RunFacts facts,
                                      List<CompiledRule> matched, boolean untilFirst) {
        // Before the first-match check, so a skipped rule reads as skipped wherever it is.
        if (facts.selection().skips(rule.rule())) {
            return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.SKIPPED);
        }
        if (untilFirst && !matched.isEmpty()) {
            return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.NOT_EVALUATED);
        }
        ConditionResult condition = matches(rule, copy, facts);
        if (!Boolean.TRUE.equals(condition.value())) {
            return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.NOT_MATCHED, condition.detail());
        }
        matched.add(rule);
        return RuleEvaluation.of(rule.rule(), RuleEvaluation.Outcome.MATCHED, condition.detail());
    }

    /**
     * Evaluates one rule's condition, telling the listeners about it.
     *
     * @param rule  The rule whose condition to evaluate
     * @param copy  The run's copy of the rules, whose sessions the condition runs with
     * @param facts The run's facts and the views built over them
     * @return What the language returned: a {@link Boolean} value, and the detail it explained it with, if any
     * @throws RuleExecutionException if the run was cancelled before this rule
     */
    ConditionResult matches(CompiledRule rule, CopyPool.Copy copy, RunFacts facts) {
        checkNotCancelled(rule, facts.deadline());
        return parseCondition(rule, copy, facts);
    }

    private ConditionResult parseCondition(CompiledRule rule, CopyPool.Copy copy, RunFacts facts) {
        RuleEvent event = FlightRecorderEvents.startRule();
        String outcome = RuleEvent.FAILED;
        facts.tally().countEvaluated();
        try {
            ConditionResult result = evaluateCondition(rule, copy, facts);
            outcome = Boolean.TRUE.equals(result.value()) ? RuleEvent.MATCHED : RuleEvent.NOT_MATCHED;
            return result;
        } catch (Throwable t) {
            outcome = failedOutcome(t);
            throw t;
        } finally {
            if (event != null) {
                event.commit(engineId, facts.runId(), rule, ExpressionKind.CONDITION, outcome);
            }
        }
    }

    private ConditionResult evaluateCondition(CompiledRule rule, CopyPool.Copy copy, RunFacts facts) {
        // The run's evaluation context has its own read-only view, whose messages are about conditions, so a
        // listener that writes to the facts isn't told about conditions.
        Map<String, Object> listenerFacts = facts.forListeners();
        notifier.notifyBefore(facts.runs(), rule, "beforeEvaluate",
                listener -> listener.beforeEvaluate(rule.rule(), listenerFacts));

        // Evaluated without a target type: asking MVEL for Boolean.class coerces any value, so a
        // condition like `status` (a non-empty string) would silently match instead of failing. Always with
        // evaluateWithDetail, never evaluate, or the detail of a language that explains its conditions is lost.
        ConditionResult condition;
        try {
            // A call-out of its own, after the listeners': what a run the condition starts logs is below it, and
            // what one a listener or an earlier rule started logged isn't (see LoggedFailures). The rule's failure is
            // described, and built, before onError marks the next call-out.
            facts.runs().callOut();
            condition = rule.compiledCondition().evaluateWithDetail(facts.evaluation(),
                    copy.sessions().get(rule.language()));
        } catch (Throwable t) {
            RuleExecutionException stop = stopped(rule, facts.deadline(), t);
            throw stop != null ? stop : expressionFailure(rule, ExpressionKind.CONDITION, t);
        }
        // Unboxing a null here would surface as an internal NPE naming MVEL's own
        // signature, which tells the caller nothing about their rule. So would reading a null result.
        Object evaluated = condition == null ? null : condition.value();
        String wrongResult = evaluated instanceof Boolean ? null : "Condition for rule '" + rule.displayName()
                + (condition == null ? "' returned no result from evaluateWithDetail" : "' evaluated to "
                + (evaluated == null ? "null" : "a " + evaluated.getClass().getName()))
                + ". A condition expression must evaluate to a boolean.";
        stopIfCancelled(rule, ExpressionKind.CONDITION, facts.deadline(), wrongResult);
        if (!(evaluated instanceof Boolean result)) {
            throw notifier.failure(rule, ExpressionKind.CONDITION, wrongResult, null);
        }

        notifier.notifyAfter(facts.runs(), rule, "afterEvaluate",
                listener -> listener.afterEvaluate(rule.rule(), listenerFacts, result));

        return condition;
    }

    /**
     * Execute a single {@link CompiledRule} object's action field against the input
     * data
     *
     * @param rule         The rule object to obtain the action expression to fire
     *                     the rule for
     * @param copy         The run's copy of the rules, whose session the action runs with
     * @param outputObject an empty output object to set output data into
     * @param facts        The run's facts and the views built over them
     * @return {@code outputObject}, which the action changed in place or whose properties were set from the action's
     *         result. An action can't replace it: the engine keeps its own reference, and a language such as MVEL
     *         fails the rule with a {@link RuleExecutionException} when an action assigns to {@code output}, except
     *         inside a {@code def} function, where the assignment creates a variable local to the function.
     * @throws RuleExecutionException if the run was cancelled before this rule
     */
    O executeRule(CompiledRule rule, CopyPool.Copy copy, O outputObject, RunFacts facts) {
        checkNotCancelled(rule, facts.deadline());
        return parseAction(rule, copy, outputObject, facts);
    }

    /**
     * Creates a fresh output object from the engine's factory. Without this, a factory that throws
     * escaped {@code run()} unwrapped, and one that returned {@code null} surfaced later as an action
     * failure blamed on whichever rule ran first.
     *
     * @param facts The run's facts, and what the run carries with them
     * @return The new output object, never {@code null}
     * @throws RuleExecutionException if the factory throws or returns {@code null}. A {@link VirtualMachineError}
     *                                other than {@link StackOverflowError}, and other than an {@link InternalError}
     *                                that wraps one (see {@link Causes#isOverflow}), is logged, then rethrown
     *                                unchanged, also
     *                                when it is the cause of what the factory throws or suppressed on it; any other
     *                                {@link Error}, and a {@link Throwable} that is neither an exception nor an error,
     *                                is wrapped like an exception. A failure of a {@code run()} the factory started
     *                                reads {@code Output factory threw: a nested run() failed: } and the innermost
     *                                failure that run logged, and one of a {@code load()} it started
     *                                {@code Output factory threw: a nested load() failed: } and that load's failure;
     *                                neither is logged again, as that run or load logged it; nor is a fatal error that
     *                                run logged (see {@link LoggedFailures}). A nested failure wrapped by the factory
     *                                in an exception with a message of its own reads {@code Output factory threw: },
     *                                that message and the nested failure as a note (see
     *                                {@link NestedFailures#describe}), and is logged; a fatal error wrapped so is
     *                                still rethrown.
     */
    O createOutput(RunFacts facts) {
        O output;
        try {
            facts.runs().callOut();
            output = outputFactory.get();
        } catch (Throwable e) {
            Failures.keepInterruptStatus(e);
            // Described before reportCalledCodeFailure() asks unlogged(), which records a fatal error it's told of
            // for the first time.
            String msg = outputFailureMessage(e);
            Failures.reportCalledCodeFailure(msg, e);
            throw NestedFailures.report(msg, e);
        }
        if (output == null) {
            String msg = "Output factory returned null. It must return a new output object on every call.";
            Failures.logError(log, msg);
            throw NestedFailures.report(msg, null);
        }
        return output;
    }

    /**
     * Describes what the output factory threw, for {@link #createOutput}, or else says only its class if describing it
     * throws, as it can when the JVM has no memory left, so a fatal {@link Error} is still rethrown. Written out
     * rather than as a lambda, so the JVM's first failure links no call site, which could overflow deep in a stack
     * (#1093).
     *
     * @param e What the factory threw
     * @return The message
     */
    private static String outputFailureMessage(Throwable e) {
        // Built beforehand, so building it can't fail where describing did.
        String plain = "Output factory threw " + e.getClass().getName();
        try {
            Faults.at(Faults.Step.FAILURE_DESCRIBING);
            return NestedFailures.below(e).logged() != null || NestedFailures.wrapsLoggedFatal(e)
                    ? "Output factory threw: " + NestedFailures.describe(e)
                    : "Output factory threw " + Failures.describeWithClass(e);
        } catch (Throwable unreadable) {
            return plain;
        }
    }

    private O parseAction(CompiledRule rule, CopyPool.Copy copy, O outputResult, RunFacts facts) {
        RuleEvent event = FlightRecorderEvents.startRule();
        String outcome = RuleEvent.FAILED;
        try {
            O output = executeAction(rule, copy, outputResult, facts);
            facts.tally().countFired(rule.language());
            outcome = RuleEvent.FIRED;
            return output;
        } catch (Throwable t) {
            outcome = failedOutcome(t);
            throw t;
        } finally {
            if (event != null) {
                event.commit(engineId, facts.runId(), rule, ExpressionKind.ACTION, outcome);
            }
        }
    }

    private O executeAction(CompiledRule rule, CopyPool.Copy copy, O outputResult, RunFacts facts) {
        notifier.notifyBefore(facts.runs(), rule, "beforeExecute",
                listener -> listener.beforeExecute(rule.rule(), outputResult));

        // The context gives the action a read-only view: an action changes the output object, never the facts other
        // rules see.
        // Not shared like the evaluation context: a first-match engine builds a fresh output object per rule. The
        // run's state is shared, so an action finds what the run's conditions kept, and it has the run's deadline,
        // start and tags as the conditions do.
        ActionContext context = new EngineActionContext(facts.values(), outputResult, facts.evaluation().run());
        ActionResult result;
        try {
            // A call-out of its own, as the condition is: what a run the rule's condition started logged isn't below
            // the action.
            facts.runs().callOut();
            result = rule.compiledAction().execute(context, copy.sessions().get(rule.language()));
        } catch (Throwable t) {
            RuleExecutionException stop = stopped(rule, facts.deadline(), t);
            throw stop != null ? stop : expressionFailure(rule, ExpressionKind.ACTION, t);
        }
        String wrongResult = result != null ? null : "Action for rule '" + rule.displayName()
                + "' returned no result. An action returns ActionResult.done() or ActionResult.set(...).";
        // Before the properties it returned are set: a run past its deadline changes the output no further.
        stopIfCancelled(rule, ExpressionKind.ACTION, facts.deadline(), wrongResult);
        if (result == null) {
            throw notifier.failure(rule, ExpressionKind.ACTION, wrongResult, null);
        }
        for (Map.Entry<String, Object> property : result.properties().entrySet()) {
            setProperty(rule, outputResult, property.getKey(), property.getValue(), facts);
        }
        // After them too: a writer that took the run past its deadline stops it, though what it set stays set.
        stopIfCancelled(rule, ExpressionKind.ACTION, facts.deadline(), null);

        notifier.notifyAfter(facts.runs(), rule, "afterExecute",
                listener -> listener.afterExecute(rule.rule(), outputResult));

        return outputResult;
    }

    /**
     * Sets one property an action returned on the output object, with the engine's writer, which is given the rule's
     * language's conversion of the value (see {@link CompiledRule#converter()}). A failure fails the rule, or stops a
     * cancelled run, as a failing action does (see {@link #stopped}).
     *
     * @throws RuleExecutionException if the property can't be set, or the run was cancelled while it was being set
     */
    // The cause of an InvocationTargetException is what the writer threw, which the failure or the stop carries, as
    // it carries the exception itself when it has none.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void setProperty(CompiledRule rule, O output, String property, Object value, RunFacts facts) {
        try {
            // A call-out of its own, as the action that returned the property is.
            facts.runs().callOut();
            outputWriter.set(output, property, value, rule.converter());
        } catch (InvocationTargetException e) {
            // A writer of its own may throw one with no cause, or one of its own whose getCause() throws.
            Throwable cause = Causes.causeOf(e);
            Throwable thrown = cause != null ? cause : e;
            RuleExecutionException stop = stopped(rule, facts.deadline(), thrown);
            throw stop != null ? stop : propertyFailure(rule, property, thrown);
        } catch (Throwable e) {
            RuleExecutionException stop = stopped(rule, facts.deadline(), e);
            throw stop != null ? stop : propertyFailure(rule, property, e);
        }
    }

    private RuleExecutionException propertyFailure(CompiledRule rule, String property, Throwable cause) {
        return notifier.failure(rule, ExpressionKind.ACTION, "Failed to set '" + Text.quote(property)
                + "' on the output for rule '" + rule.displayName() + "': " + NestedFailures.describe(cause), cause);
    }

    /**
     * Stops a run that must not go on to {@code rule}, because its thread has been interrupted or it has passed its
     * deadline. Checked before each condition and before each action, and again when each returns
     * ({@link #stopIfCancelled}), which is as often as the engine gets control back: an expression that doesn't return
     * can only be stopped by a language that can stop inside one, through
     * {@link io.github.brantunger.unruly.api.language.EvaluationContext#isCancelled()}, or, at the deadline, through
     * an action it registered with
     * {@link io.github.brantunger.unruly.api.language.EvaluationContext#onCancel(Runnable)}.
     *
     * <p>
     * No listener is told about the rule, because nothing was started for it: the check runs before
     * {@code beforeEvaluate} and {@code beforeExecute}, so no callback is open to close with {@code onError}. The
     * run's own {@code onRunError} is called, like any other failure of a run.
     * </p>
     *
     * @param rule     The rule the run would go on to
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @throws RuleExecutionException if the run must stop
     */
    private void checkNotCancelled(CompiledRule rule, Deadline deadline) {
        ReportedFailure stop = cancellation(rule, "before", deadline, null);
        if (stop != null) {
            throw stop;
        }
    }

    /**
     * Returns the exception a cancelled run stops with, or {@code null} if the run may go on. The message naming the
     * rule is built only when the run stops, because this is checked several times for every rule.
     *
     * @param rule     The rule the run stopped before or during
     * @param stage    {@code "before"} or {@code "during"}, where the run stopped, as its message says: before the
     *                 rule's condition or action, or while one ran
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @param thrown   What the expression threw, or {@code null}: when a run it started stopped for the same reason,
     *                 that run logged the stop, and it isn't logged again
     * @return The exception, or {@code null}
     */
    private ReportedFailure cancellation(CompiledRule rule, String stage, Deadline deadline, Throwable thrown) {
        // isInterrupted(), not interrupted(): the status stays set, so an executor shutting down still sees it.
        Cancellation.Reason reason = Cancellation.reason(deadline);
        if (reason == Cancellation.Reason.INTERRUPTED) {
            return cancelled("run() was interrupted " + stage + " rule '" + rule.displayName() + "'",
                    new InterruptedException(), null, thrown);
        }
        if (reason == Cancellation.Reason.TIMED_OUT) {
            return cancelled("run() passed its deadline of " + IsoInstant.text(deadline.instant()) + " " + stage
                    + " rule '" + rule.displayName() + "'", Cancellation.timedOut(deadline), deadline, thrown);
        }
        return null;
    }

    /**
     * Reports a condition or action that threw as its rule's failure. An overflow (see {@link Causes#isOverflow}),
     * bare or wrapped, as in an {@link InternalError} the JDK wraps one in, says the stack overflowed, unless what it
     * threw holds a nested {@code run()}'s or {@code load()}'s failure, which is described as
     * {@link NestedFailures#describe} describes it, naming that run's or load's rule.
     *
     * @param rule   The rule whose expression threw
     * @param kind   Whether the condition or the action threw
     * @param thrown What it threw
     * @return The exception to throw
     */
    private RuleExecutionException expressionFailure(CompiledRule rule, ExpressionKind kind, Throwable thrown) {
        String what = kind == ExpressionKind.CONDITION ? "Failed to evaluate condition" : "Failed to execute action";
        String why = NestedFailures.below(thrown).logged() != null ? NestedFailures.describe(thrown)
                : NestedFailures.describeCallOut(thrown, false);
        return notifier.failure(rule, kind, what + " for rule '" + rule.displayName() + "': " + why, thrown);
    }

    /**
     * Decides what a condition or action that threw means, or the output writer setting a property an action
     * returned. When the run has been cancelled by then, the throw is
     * taken as the expression giving up, as a run started from inside it does when it stops at the deadline it
     * inherited, so the run stops the way a cancelled run always does: no rule name, logged at WARN, with an
     * {@link InterruptedException} or a {@link TimeoutException} as the cause and what the expression threw kept as a
     * suppressed exception. The rule's open callback is closed with {@code onError}. When the run hasn't been
     * cancelled, the rule failed; an {@link Error} in what the expression threw makes that the answer even when it
     * has, because the code being run broke rather than gave up. Either way a fatal {@link Error} in what it threw
     * is rethrown as for any failure. The caller reports the rule's failure itself, rather than handing this a lambda
     * that does, so the JVM's first failure links no call site, which could overflow deep in a stack (#1093).
     *
     * @param rule     The rule whose expression threw
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @param thrown   What the expression threw
     * @return The exception to throw for a stopped run, or {@code null} if the caller reports the rule's failure: when
     *         the run wasn't cancelled or {@code thrown} has an {@link Error} anywhere in its cause chain, or one
     *         suppressed on it at any depth (see {@link NestedFailures#errorInChain})
     */
    private RuleExecutionException stopped(CompiledRule rule, Deadline deadline, Throwable thrown) {
        // An interrupt the expression caught and wrapped, or left suppressed, is put back first, so it counts as one
        // here too, and an Error inside what it threw is the rule's failure as it always is, cancelled or not.
        Failures.keepInterruptStatus(thrown);
        ReportedFailure stop = NestedFailures.errorInChain(thrown) == null
                ? cancellation(rule, "during", deadline, thrown) : null;
        if (stop == null) {
            return null;
        }
        stop.addSuppressedByEngine(thrown);
        return notifier.closedWithStop(rule, stop);
    }

    /**
     * Stops a run that was cancelled while a condition or action ran, once that expression has returned, so a run
     * past its deadline or interrupted never returns a result, even when the expression was its last one.
     *
     * @param rule        The rule whose expression returned
     * @param kind        Whether the condition or the action returned
     * @param deadline    When the run must stop, {@link Deadline#NONE} if it has none
     * @param wrongResult Why what the expression returned would have failed the rule, or {@code null} if it wouldn't.
     *                    A stopped run keeps it as a suppressed exception, as it keeps what an expression threw.
     * @throws RuleExecutionException if the run was cancelled
     */
    private void stopIfCancelled(CompiledRule rule, ExpressionKind kind, Deadline deadline, String wrongResult) {
        ReportedFailure stop = cancellation(rule, "during", deadline, null);
        if (stop != null) {
            if (wrongResult != null) {
                stop.addSuppressedByEngine(new RuleExecutionException(wrongResult, null, rule.rule().getRuleName(),
                        kind));
            }
            throw notifier.closedWithStop(rule, stop);
        }
    }

    /**
     * Reports a run that stopped because it was cancelled. Logged at WARN, not ERROR: nothing failed, and the caller
     * asked for it, whether by interrupting the thread or by setting a timeout.
     *
     * @param msg      What to log and what the exception says
     * @param cause    An {@link InterruptedException} or a {@link TimeoutException}, so a caller can tell which
     *                 happened
     * @param deadline The deadline the run passed, or {@code null} if it was interrupted
     * @param thrown   What the expression threw, or {@code null}. A run it started that stopped for the same reason
     *                 has logged the stop already, so it isn't logged a second time.
     * @return The exception to throw, which belongs to no rule
     */
    private ReportedFailure cancelled(String msg, Exception cause, Deadline deadline, Throwable thrown) {
        if (!NestedFailures.nestedRunStopped(thrown, deadline)) {
            Failures.logWarn(log, msg);
        }
        return NestedFailures.stop(msg, cause, deadline);
    }

    /**
     * Classifies what a condition or action threw, here rather than in {@link RuleEvent}, so a rule that fails doesn't
     * load the event class where {@link FlightRecorderEvents#USABLE} found it can't be. A fatal {@link Error} leaves
     * the rule stopped when a listener's {@code onError} threw the error while closing a stop, which
     * {@link ListenerNotifier#closedWithStop} recorded for {@code onRunError}; anything else leaves it stopped when it
     * is the stop of a run that was interrupted or passed its deadline. Otherwise the rule failed, as it does for a
     * {@link Throwable} that is neither a {@link RuleExecutionException} nor an {@link Error}.
     *
     * @param thrown What the rule's evaluation ends with
     * @return {@link RuleEvent#STOPPED} or {@link RuleEvent#FAILED}
     */
    private String failedOutcome(Throwable thrown) {
        if (thrown instanceof Error) {
            return ReportedFailure.isStop(notifier.recordedFatalFailure()) ? RuleEvent.STOPPED : RuleEvent.FAILED;
        }
        return ReportedFailure.isStop(thrown) ? RuleEvent.STOPPED : RuleEvent.FAILED;
    }
}
