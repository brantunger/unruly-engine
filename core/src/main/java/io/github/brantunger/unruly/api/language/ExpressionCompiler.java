package io.github.brantunger.unruly.api.language;

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
     * Compiles an action. When it runs, the action either changes the output object it sees as
     * {@value ActionContext#OUTPUT_NAME} and returns {@link ActionResult#done()}, or returns
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
     * language. The engine has already rejected {@value ActionContext#OUTPUT_NAME} and {@code null}. By default,
     * every other name is accepted.
     *
     * @param name The fact's name
     * @throws IllegalArgumentException if rules can't refer to a fact with this name; {@code run()} throws it as is.
     *         Anything else this method throws is logged and thrown from {@code run()} as an
     *         {@code IllegalArgumentException} naming the fact and the language, except a fatal {@link Error}, which
     *         is rethrown unchanged.
     */
    default void checkFactName(String name) {
        // Every name is accepted.
    }

    /**
     * Releases what the compiler holds. The engine calls it once, after closing every session the compiler created. By
     * default, does nothing. Anything it throws is logged at WARN, and the engine still closes the other compilers.
     * Only a fatal {@link Error} is then rethrown, unchanged, unless a fatal error of the call's own came first.
     */
    @Override
    default void close() {
        // Nothing to release.
    }
}
