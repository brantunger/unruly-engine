package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.mvel2.CompileException;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles one rule list's MVEL expressions with the list's imports, and checks fact names against them.
 */
final class MvelExpressionCompiler implements ExpressionCompiler {

    private final Imports imports;
    private final FactNames factNames;
    // Every expression compiled, for warmUp(). Only load()'s thread compiles and warms up, so a plain list will do.
    private final List<MvelExpression> compiled = new ArrayList<>();

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
    public CompiledCondition compileCondition(Expression source) {
        ConditionAssignments.Write write = ConditionAssignments.find(source.text());
        if (write != null) {
            throw MvelCompileErrors.rejected(source.text(), write);
        }
        return compile(source);
    }

    @Override
    public CompiledAction compileAction(Expression source) {
        return compile(source);
    }

    private MvelExpression compile(Expression source) {
        MvelAnalysis analysis = new MvelAnalysis(source.text(), imports);
        try {
            MvelExpression expression = MvelExpression.compile(analysis);
            compiled.add(expression);
            return expression;
        } catch (CompileException e) {
            // The engine reports an expression too long for MVEL's recursive parser as such.
            if (ExceptionReads.rootCause(e) instanceof StackOverflowError) {
                throw e;
            }
            throw MvelCompileErrors.compileError(e, analysis.rejectedType());
        } catch (Imports.ImportTooLarge e) {
            throw MvelCompileErrors.importTooLarge(source.text(), e);
        } catch (IndexOutOfBoundsException e) {
            // MVEL's parser reads out of bounds for some malformed expressions, such as a . or ? it can't read past or
            // blank parentheses, instead of reporting them. One from other code, such as the application's class
            // loader, is reported as is.
            if (!MvelCompileErrors.thrownInMvel(e)) {
                throw e;
            }
            throw MvelCompileErrors.malformed(e);
        } catch (RuntimeException e) {
            // An imported class called like a method, such as ArrayList(y), fails MVEL's cast to a static method.
            if (MvelCompileErrors.calledLikeMethod(e)) {
                throw MvelCompileErrors.classCalledLikeMethod(e);
            }
            // MVEL throws a plain RuntimeException, with no cause and no position, for some expressions it rejects,
            // such as int in = 1 or an ambiguous class name. Any other is reported as is.
            if (!MvelCompileErrors.rejectedPlainly(e)) {
                throw e;
            }
            throw MvelCompileErrors.plainRejection(e);
        }
    }

    /**
     * Creates the session that holds one copy of the rule list's compiled MVEL expressions.
     */
    @Override
    public Session newSession() {
        return new MvelSession();
    }

    /**
     * Compiles every condition and action of the rule list into the session, which otherwise compiles each one the
     * first time it runs. The first session warmed up takes the compilations made when the rules loaded; every later
     * one compiles the expressions again.
     *
     * @param session A session {@link #newSession()} returned
     * @throws IllegalArgumentException if the session isn't one this compiler created
     */
    @Override
    public void warmUp(Session session) {
        MvelSession mvel = MvelExpression.mvelSession(session);
        compiled.forEach(mvel::compiled);
    }

    @Override
    public void checkFactName(String name) {
        factNames.check(name);
    }
}
