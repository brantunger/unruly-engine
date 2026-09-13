package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;

import java.io.Serializable;

/**
 * One compiled MVEL condition or action.
 *
 * <p>
 * MVEL caches an accessor in a compiled expression the first time it runs, and replaces it without synchronization
 * when a later run binds the same name to a different kind of object, so {@link #copy()} compiles a new expression.
 * </p>
 *
 * @param source   The expression's source text
 * @param imports  The imports it is compiled with
 * @param compiled MVEL's compiled expression
 */
record MvelExpression(String source, Imports imports, Serializable compiled)
        implements CompiledCondition, CompiledAction {

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
    public Object evaluate(EvaluationContext context) {
        return MVEL.executeExpression(compiled, (Object) null, context.facts());
    }

    @Override
    public void execute(ActionContext context) {
        // Reads the facts; the output object and the action's own assignments stay in this action.
        MVEL.executeExpression(compiled, (Object) null, new ActionVariables(context.facts(), context.output()));
    }

    /**
     * Compiles another copy for a concurrent run. The expression already compiled with these imports, so MVEL's
     * analysis pass isn't repeated.
     */
    @Override
    public MvelExpression copy() {
        return new MvelExpression(source, imports, MVEL.compileExpression(source, newParserContext(imports)));
    }

    /**
     * Creates a context used by exactly one compilation. MVEL records variables, their types and inline
     * {@code import} statements on the context and its configuration, and a compiled expression goes on using
     * its context when it first runs. A context shared across rules let one rule change how another compiled,
     * and let {@code setRuleList()} modify it while a concurrent {@code run()} was still reading it. Only the
     * names found not to be classes are shared with the other compilations of the rule list; see
     * {@link Imports#newConfiguration()}.
     */
    private static ParserContext newParserContext(Imports imports) {
        return new ParserContext(imports.newConfiguration());
    }
}
