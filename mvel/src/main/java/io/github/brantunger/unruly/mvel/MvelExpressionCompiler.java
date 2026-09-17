package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.mvel2.CompileException;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles one rule list's MVEL expressions with the list's imports, and checks fact names against them.
 */
final class MvelExpressionCompiler implements ExpressionCompiler {

    // The first line of MVEL's message for a compile error, such as [Error: unbalanced braces ( ... )].
    private static final Pattern ERROR = Pattern.compile("^\\[Error: (.*)]$", Pattern.MULTILINE);
    // Where MVEL found the error, such as [Line: 1, Column: 11].
    private static final Pattern POSITION = Pattern.compile("\\[Line: (\\d+), Column: (\\d+)]");
    private static final char NEW_LINE = '\n';

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
    public CompiledCondition compileCondition(Expression source) {
        ConditionAssignments.Write write = ConditionAssignments.find(source.text());
        if (write != null) {
            throw rejected(source.text(), write);
        }
        return compile(source);
    }

    /**
     * Reports an assignment or {@code import_static} found in a condition, with one issue that says where it starts:
     * the line and column, counting from 1, as MVEL's own compile errors say.
     *
     * @param condition The condition's source text
     * @param write     What was found, and where
     * @return The exception to throw
     */
    private static InvalidExpressionException rejected(String condition, ConditionAssignments.Write write) {
        String before = condition.substring(0, write.position());
        int line = 1 + (int) before.chars().filter(ch -> ch == NEW_LINE).count();
        int column = write.position() - (before.lastIndexOf(NEW_LINE) + 1) + 1;
        String where = "at line " + line + ", column " + column;
        String description;
        String message;
        if (write.isStaticImport()) {
            description = "uses import_static, which declares the method as a variable";
            message = "uses import_static (" + where + "), which declares the method as a variable, and conditions "
                    + "can't declare variables. Call the method through its class instead, such as Math.max(a, b).";
        } else {
            description = "contains an assignment ('" + write.text() + "')";
            message = "contains an assignment ('" + write.text() + "' " + where
                    + "). Conditions can't change facts or declare variables; use == to compare.";
        }
        return new InvalidExpressionException(message, List.of(new InvalidExpressionException.Issue(
                InvalidExpressionException.Issue.Severity.ERROR, line, column, description)));
    }

    @Override
    public CompiledAction compileAction(Expression source) {
        return compile(source);
    }

    private MvelExpression compile(Expression source) {
        try {
            return MvelExpression.compile(source.text(), imports);
        } catch (CompileException e) {
            // The engine reports an expression too long for MVEL's recursive parser as such.
            if (rootCause(e) instanceof StackOverflowError) {
                throw e;
            }
            throw compileError(e);
        }
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * Reports an error MVEL found while compiling as an {@link InvalidExpressionException}, with one issue that has
     * MVEL's description and, when MVEL gives one, its line and column. MVEL's own exception is the cause.
     *
     * @param e What MVEL threw
     * @return The exception to throw
     */
    static InvalidExpressionException compileError(CompileException e) {
        String message = String.valueOf(e.getMessage());
        Matcher error = ERROR.matcher(message);
        String description = error.find() ? error.group(1) : message;
        Matcher position = POSITION.matcher(message);
        InvalidExpressionException.Issue issue = position.find()
                ? new InvalidExpressionException.Issue(InvalidExpressionException.Issue.Severity.ERROR,
                Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2)), description)
                : new InvalidExpressionException.Issue(InvalidExpressionException.Issue.Severity.ERROR, 0, 0,
                description);
        String where = issue.line() == 0 ? "" : " at line " + issue.line() + ", column " + issue.column();
        return new InvalidExpressionException("failed to compile" + where + ": " + description, List.of(issue), e);
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
