package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;

/**
 * Compiles one rule list's MVEL expressions with the list's imports, and checks fact names against them.
 */
final class MvelExpressionCompiler implements ExpressionCompiler {

    private final Imports imports;
    private final FactNames factNames;

    /**
     * Creates the compiler for one rule list.
     *
     * @param imports The imports the rule list is compiled with
     */
    MvelExpressionCompiler(Imports imports) {
        this.imports = imports;
        this.factNames = new FactNames(imports);
    }

    /**
     * Compiles a condition after rejecting any assignment in its text. The read-only facts a condition runs against
     * only stop a write to a bare variable at run time. A property write such as {@code claim.approved = true} goes
     * through the fact's own setter, so assignments are rejected here instead.
     */
    @Override
    public CompiledCondition compileCondition(String source) {
        ConditionAssignments.Write write = ConditionAssignments.find(source);
        if (write != null && write.isStaticImport()) {
            throw new InvalidExpressionException("uses import_static (at position " + write.position()
                    + "), which declares the method as a variable, and conditions can't declare variables. Call the "
                    + "method through its class instead, such as Math.max(a, b).");
        }
        if (write != null) {
            throw new InvalidExpressionException("contains an assignment (" + write
                    + "). Conditions can't change facts or declare variables; use == to compare.");
        }
        return MvelExpression.compile(source, imports);
    }

    @Override
    public CompiledAction compileAction(String source) {
        return MvelExpression.compile(source, imports);
    }

    /**
     * Creates the session that holds one copy of the rule list's compiled MVEL expressions.
     */
    @Override
    public Session newSession() {
        return new MvelSession();
    }

    @Override
    public void checkFactName(String name) {
        factNames.check(name);
    }
}
