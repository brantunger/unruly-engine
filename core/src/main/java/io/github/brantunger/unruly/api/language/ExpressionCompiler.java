package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Compiles the conditions and actions of one rule list, checks the names of the facts they run against, and creates
 * the sessions they run with.
 *
 * <p>
 * {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} calls the compile methods on one
 * thread, and then, for an engine built with
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#copiesAtLoad(int) copiesAtLoad(n)}, {@link #newSession()}
 * and {@link #warmUp(Session)} on the same thread. {@link #checkFactName(String)} and {@link #newSession()} are also
 * called by runs of the rule list, possibly on many threads at once, so they must be thread-safe.
 * </p>
 *
 * <p>
 * The engine closes the compiler once it no longer needs the rule list, after closing every session the compiler
 * created: when a later {@code load()} has replaced the rule list and no run is still using it, when the engine
 * is closed, or when the rule list fails to load.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface is a {@code default} method, so an
 * existing language keeps compiling and working.
 * </p>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/custom.md">Writing an expression
 *      language</a>
 */
public interface ExpressionCompiler extends AutoCloseable {

    /**
     * Compiles a condition. A condition must evaluate to a {@link Boolean}, and can't change facts or declare
     * variables; a compiler that can see such a write rejects the condition here.
     *
     * @param source The condition: the name of its rule, {@code CONDITION}, and its text, which isn't blank
     * @return The compiled condition, which every run of the rule list shares. Keep what changes while it runs in a
     *         {@link Session}.
     * @throws io.github.brantunger.unruly.api.exception.InvalidExpressionException if the condition breaks a rule the
     *         engine enforces, such as assigning to a fact, or has an error the language can point to, such as a
     *         syntax error; its issues say where. Anything else is reported as the cause of a
     *         {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} too.
     */
    CompiledCondition compileCondition(Expression source);

    /**
     * Compiles an action. When it runs, the action either changes the output object, {@link ActionContext#output()},
     * which a language may bind to a name such as {@value ActionContext#OUTPUT_NAME}, and returns
     * {@link ActionResult#done()}, or returns
     * {@link ActionResult#set(java.util.Map)} with the properties for the engine to set on the output object.
     *
     * @param source The action: the name of its rule, {@code ACTION}, and its text, which isn't blank
     * @return The compiled action, which every run of the rule list shares. Keep what changes while it runs in a
     *         {@link Session}.
     * @throws io.github.brantunger.unruly.api.exception.InvalidExpressionException if the action breaks a rule the
     *         engine enforces, or has an error the language can point to, such as a syntax error; its issues say
     *         where. Anything else is reported as the cause of a
     *         {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} too.
     */
    CompiledAction compileAction(Expression source);

    /**
     * Creates a session for one copy of the rule list: the state this language's conditions and actions change while
     * they run. Every condition and action this compiler compiled is given the session of the run that evaluates it.
     *
     * @return A new session, or {@link Session#none()} if the compiled expressions keep no state between runs and are
     *         safe to run on several threads at once. Throwing or returning {@code null} fails the run that needed the
     *         session with a {@link io.github.brantunger.unruly.api.exception.RuleExecutionException}.
     */
    Session newSession();

    /**
     * Prepares a new session before any run uses it, such as by compiling this compiler's expressions into it, so
     * that the runs that use it first don't pay for that. By default, does nothing: a session prepares itself the
     * first time each expression runs, whatever that costs.
     *
     * <p>
     * {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} calls it on its own thread, once for
     * each session it creates with {@link #newSession()} for a copy made when the rules load
     * ({@link io.github.brantunger.unruly.api.RulesEngineBuilder#copiesAtLoad(int)}), after compiling every
     * expression and before any run uses the session. It isn't called for {@link Session#none()}, or for a session
     * created during a run, which prepares itself as it's used.
     * </p>
     *
     * @param session A session this compiler's {@link #newSession()} returned, which no run has used
     * @throws Exception if the session can't be prepared. It fails {@code load()} with a
     *                   {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} naming the
     *                   language, and the rules loaded before stay loaded, except a fatal {@link Error}, which is
     *                   rethrown unchanged.
     */
    default void warmUp(Session session) throws Exception {
        // A session prepares itself as it's used.
    }

    /**
     * Rejects the name of a fact that rules written in this language couldn't refer to, such as a keyword of the
     * language. The engine has already rejected {@code null} and blank names, and the names its languages reserve
     * (see {@link ExpressionLanguage#reservedFactNames()}). By default, every other name is accepted.
     *
     * <p>
     * The engine calls it for every fact of a run, and every declared fact, unless {@link #factNamesRead()} returns a
     * set: then only for the facts named in it.
     * </p>
     *
     * @param name The fact's name
     * @throws IllegalArgumentException if rules can't refer to a fact with this name; {@code run()} throws it as is,
     *         unless a fatal {@link Error} is among its causes or their suppressed exceptions. Anything else this
     *         method throws is logged and thrown from {@code run()} as an {@code IllegalArgumentException} naming the
     *         fact and the language, except a fatal {@code Error}, thrown, among the causes of what this method throws
     *         or suppressed on one of them, which is rethrown unchanged.
     */
    default void checkFactName(String name) {
        // Every name is accepted.
    }

    /**
     * Returns the names of the facts the conditions and actions this compiler compiled can read, or {@code null} if it
     * can't tell. By default, {@code null}, and the engine calls {@link #checkFactName(String)} for every fact, as a
     * fact reaches every rule.
     *
     * <p>
     * When it returns a set, the engine calls {@link #checkFactName(String)} only for the facts named in it: a fact
     * that no rule of this compiler reads is never checked by this language, so a keyword of this language can still
     * name a fact that only another language's rules read. It narrows nothing else: the names a language reserves
     * (see {@link ExpressionLanguage#reservedFactNames()}) are still rejected for every fact. A name the set holds that
     * is no fact's changes nothing, so a compiler that can tell only roughly returns more names, never fewer: a fact
     * left out is one a rule may read under a name this language can't refer to, and no check says so. A compiler that
     * can't tell for some expression, such as one that reads facts by a name it computes, returns {@code null}.
     * </p>
     *
     * <p>
     * {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} and
     * {@link io.github.brantunger.unruly.api.RulesEngine#validate(java.util.List)} call it once, on their own thread,
     * after every rule of the list has compiled, and before they check the declared facts; not when a rule failed to
     * compile. The engine keeps a copy of the set, so a later change to it changes nothing.
     * </p>
     *
     * @return The names, holding no {@code null}, or {@code null} if this compiler can't tell which facts its rules
     *         read. Throwing, or returning a set that holds {@code null}, fails {@code load()} with a
     *         {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} naming the language, except
     *         a fatal {@link Error}, which is rethrown unchanged.
     */
    // null says the compiler can't tell, which an empty set, a compiler whose rules read no fact, can't say.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    default @Nullable Set<String> factNamesRead() {
        return null;
    }

    /**
     * Releases what the compiler holds. The engine calls it once, after closing every session the compiler created. By
     * default, does nothing. Anything it throws is logged at WARN, unless a {@code run()} or {@code load()} it started
     * logged it already, and the engine still closes the other compilers. Only a fatal {@link Error} is then rethrown,
     * unchanged, unless a fatal error of the call's own came first. An {@link InterruptedException} in the cause chain
     * of what it throws, or suppressed there, puts back the interrupt status of the thread that closes it, which can be
     * a run's thread, for example when a rule's action calls {@code load()} or {@code close()}.
     */
    @Override
    default void close() {
        // Nothing to release.
    }
}
