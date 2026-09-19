package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Session;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One compiled MVEL condition or action, shared by every run of its rule list.
 *
 * <p>
 * MVEL caches an accessor in a compiled expression the first time it runs, and replaces it without synchronization
 * when a later run binds the same name to a different kind of object, so MVEL's compiled form isn't shared: each
 * {@link MvelSession} runs its own, from {@link #newCompiled()}.
 * </p>
 */
final class MvelExpression implements CompiledCondition, CompiledAction {

    private final String source;
    private final Imports imports;
    // MVEL's compiled expression from when the rule list loaded, which no run has used, until a session takes it.
    private final AtomicReference<Serializable> loaded;

    private MvelExpression(String source, Imports imports, Serializable loaded) {
        this.source = source;
        this.imports = imports;
        this.loaded = new AtomicReference<>(loaded);
    }

    /**
     * Compiles an expression.
     *
     * @param source  The expression's source text
     * @param imports The imports to compile it with
     * @return The compiled expression
     */
    static MvelExpression compile(String source, Imports imports) {
        // compileExpression alone accepts some malformed input (e.g. `x == == 1`) and defers the error to
        // run(). The analysis pass catches more of it up front.
        MVEL.analysisCompile(source, newParserContext(imports));
        return new MvelExpression(source, imports, MVEL.compileExpression(source, newParserContext(imports)));
    }

    @Override
    public Object evaluate(EvaluationContext context, Session session) {
        return MVEL.executeExpression(compiledIn(session), (Object) null, context.facts());
    }

    @Override
    public ActionResult execute(ActionContext context, Session session) {
        // Reads the facts; the output object and the action's own assignments stay in this action, which changes the
        // output in place.
        MVEL.executeExpression(compiledIn(session), (Object) null,
                new ActionVariables(context.facts(), context.output()));
        return ActionResult.done();
    }

    /**
     * Returns MVEL's compiled form of this expression for a new session: the one compiled when the rule list loaded,
     * the first time, then a new compilation. The expression already compiled with these imports, so MVEL's analysis
     * pass isn't repeated. Sessions on several threads can call it at once.
     *
     * @return A compiled expression that no other session runs
     */
    Serializable newCompiled() {
        Serializable first = loaded.getAndSet(null);
        return first != null ? first : MVEL.compileExpression(source, newParserContext(imports));
    }

    private Serializable compiledIn(Session session) {
        return mvelSession(session).compiled(this);
    }

    /**
     * Returns the session as the MVEL session it must be.
     *
     * @param session A session an MVEL compiler created
     * @return The session
     * @throws IllegalArgumentException if another language's compiler created it
     */
    static MvelSession mvelSession(Session session) {
        if (session instanceof MvelSession mvel) {
            return mvel;
        }
        throw new IllegalArgumentException("An MVEL expression runs with a session its compiler created, not "
                + session);
    }

    /**
     * Creates a context used by exactly one compilation. MVEL records variables, their types and inline
     * {@code import} statements on the context and its configuration, and a compiled expression goes on using
     * its context when it first runs. A context shared across rules let one rule change how another compiled,
     * and let {@code load()} modify it while a concurrent {@code run()} was still reading it. Only the
     * names found not to be classes are shared with the other compilations of the rule list; see
     * {@link Imports#newConfiguration()}.
     */
    private static ParserContext newParserContext(Imports imports) {
        ParserContext context = new ParserContext(imports.newConfiguration());
        if (imports.stronglyTyped()) {
            // Applied here, not only where the rule list loads, because a session compiles the expression again for
            // its own copy: without this, only the first copy would be the one that was type-checked.
            context.setStrongTyping(true);
            imports.inputs().forEach(context::addInput);
        }
        return context;
    }
}
