package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.mvel2.CompileException;

import java.util.ArrayList;
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
    // MVEL's description of an error whose message is missing, such as a class's failed static initializer.
    private static final String MISSING_DESCRIPTION = "null";

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
            MvelExpression expression = MvelExpression.compile(source.text(), imports);
            compiled.add(expression);
            return expression;
        } catch (CompileException e) {
            // The engine reports an expression too long for MVEL's recursive parser as such.
            if (ExceptionReads.rootCause(e) instanceof StackOverflowError) {
                throw e;
            }
            throw compileError(e);
        }
    }

    /**
     * Reports an error MVEL found while compiling as an {@link InvalidExpressionException}, with one issue that has
     * MVEL's description and, when MVEL gives one, its line and column. MVEL's own exception is the cause.
     *
     * <p>
     * When MVEL's description is missing, as it is for a class whose static initializer threw (MVEL copies the
     * {@link ExceptionInInitializerError}'s missing message into its own as {@code [Error: null]}), the description
     * names the root cause in the engine's note, in the exception's message and the issue alike, such as
     * {@code failed to compile at line 1, column 1: null (caused by java.lang.RuntimeException: disk full)}, or its
     * class alone if it has no message. A message that can't be read, MVEL's or the root cause's, reads
     * {@code (message unavailable: ...)}, naming the class of what reading it threw.
     * </p>
     *
     * @param e What MVEL threw
     * @return The exception to throw
     */
    static InvalidExpressionException compileError(CompileException e) {
        String message = String.valueOf(ExceptionReads.messageOf(e));
        Matcher error = ERROR.matcher(message);
        String description = error.find() ? error.group(1) : message;
        if (MISSING_DESCRIPTION.equals(description)) {
            description += causeNote(ExceptionReads.causeChain(e));
        }
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
     * Names the root cause of a compile error whose description MVEL left missing, in the engine's note: its class,
     * and its message if it has one, as {@code core.Failures} names one. The engine escapes and shortens the whole
     * message it reports.
     *
     * @param chain What MVEL threw and its causes
     * @return {@code " (caused by ...)"}, or an empty string if there is no cause
     */
    private static String causeNote(List<Throwable> chain) {
        if (chain.subList(1, chain.size()).isEmpty()) {
            return "";
        }
        Throwable root = chain.get(chain.size() - 1);
        String rootMessage = ExceptionReads.messageOf(root);
        return " (caused by " + root.getClass().getName() + (rootMessage == null ? "" : ": " + rootMessage) + ")";
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
