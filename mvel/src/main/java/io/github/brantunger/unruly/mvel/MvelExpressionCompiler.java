package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.mvel2.CompileException;
import org.mvel2.ErrorDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles one rule list's MVEL expressions with the list's imports, and checks fact names against them.
 */
final class MvelExpressionCompiler implements ExpressionCompiler {

    // The first line of MVEL's message for a compile error, such as [Error: unbalanced braces ( ... )]. MVEL ends its
    // lines with \n alone, so a line or paragraph separator from the expression's text doesn't end one.
    private static final Pattern ERROR = Pattern.compile("^\\[Error: (.*)]$", Pattern.MULTILINE | Pattern.UNIX_LINES);
    // Where MVEL found the error, such as [Line: 1, Column: 11].
    private static final Pattern POSITION = Pattern.compile("\\[Line: (\\d+), Column: (\\d+)]");
    private static final char NEW_LINE = '\n';
    // MVEL's description of an error whose message is missing, such as a class's failed static initializer.
    private static final String MISSING_DESCRIPTION = "null";
    // What MVEL says of a malformed statement such as b = = 1 when an assert inside MVEL doesn't stop it first.
    private static final String BADLY_FORMED = "not a statement, or badly formed structure";
    // What an expression MVEL's parser reads out of bounds for, such as b., ? or ( ), is reported as.
    private static final String MALFORMED_EXPRESSION = "malformed expression";
    // The JDK's packages, whose frames are skipped to find whose code threw, as when the JDK's bounds check threw.
    private static final List<String> JDK_PACKAGES = List.of("java.", "jdk.", "sun.", "com.sun.");

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
        } catch (IndexOutOfBoundsException e) {
            // MVEL's parser reads out of bounds for some malformed expressions, such as a . or ? it can't read past or
            // blank parentheses, instead of reporting them. One from other code, such as the application's class
            // loader, is reported as is.
            if (!thrownInMvel(e)) {
                throw e;
            }
            throw malformed(e);
        }
    }

    /**
     * Reports an expression MVEL's parser read out of bounds for, as it does for a {@code .} or {@code ?} it can't
     * read past, such as in {@code b.}, {@code b. == 1} or {@code foo(?)}, or for blank parentheses, such as in
     * {@code ( ) + 1}, with one issue that has no line or column: {@code failed to compile: malformed expression}.
     * What MVEL threw is the cause.
     *
     * @param e What MVEL threw
     * @return The exception to throw
     */
    private static InvalidExpressionException malformed(IndexOutOfBoundsException e) {
        return new InvalidExpressionException("failed to compile: " + MALFORMED_EXPRESSION, List.of(
                new InvalidExpressionException.Issue(InvalidExpressionException.Issue.Severity.ERROR, 0, 0,
                        MALFORMED_EXPRESSION)), e);
    }

    /**
     * Reports an error MVEL found while compiling as an {@link InvalidExpressionException}, with one issue that has
     * MVEL's description and, when MVEL gives one, its line and column. MVEL's own exception is the cause.
     *
     * <p>
     * The errors MVEL's strong typing finds, which MVEL lists with the exception, are put on one line: one error is
     * its description alone, such as {@code could not resolve class: Nosuch}, and several are each
     * {@code (line,column) description}, joined with {@code ; }.
     * </p>
     *
     * <p>
     * MVEL's description, from its message or its list, can hold the expression's own text, so it's escaped as the
     * engine escapes its messages, such as a line break as {@code \n}, and is one line in the issue too.
     * </p>
     *
     * <p>
     * When MVEL's description is missing and the root cause is an {@link AssertionError} thrown in MVEL's own code, as
     * one is for {@code b = = 1} with assertions on, the description is
     * {@code not a statement, or badly formed structure}: MVEL's own for {@code b = = 1} with assertions off.
     * Otherwise, when MVEL's description is missing, as it is for a class whose static initializer threw (MVEL copies
     * the {@link ExceptionInInitializerError}'s missing message into its own as {@code [Error: null]}), the
     * description names the root cause in the engine's note, in the exception's message and the issue alike, such as
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
        List<ErrorDetail> errors = e.getErrors();
        String description;
        if (errors.isEmpty()) {
            Matcher error = ERROR.matcher(message);
            description = FactNames.escape(error.find() ? error.group(1) : message);
        } else {
            description = oneLine(errors);
        }
        if (MISSING_DESCRIPTION.equals(description)) {
            description = failedAssert(ExceptionReads.rootCause(e)) ? BADLY_FORMED
                    : description + causeNote(ExceptionReads.causeChain(e));
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
     * Puts the errors MVEL listed with its exception on one line: one error's description alone, as the issue has
     * its line and column, or several as {@code (line,column) description}, joined with {@code ; }. Each description
     * is escaped as the engine escapes its messages, as it can hold the expression's own text.
     *
     * @param errors The errors, at least one
     * @return The errors on one line
     */
    private static String oneLine(List<ErrorDetail> errors) {
        if (errors.subList(1, errors.size()).isEmpty()) {
            return FactNames.escape(String.valueOf(errors.get(0).getMessage()));
        }
        List<String> described = new ArrayList<>();
        for (ErrorDetail error : errors) {
            described.add("(" + error.getLineNumber() + "," + error.getColumn() + ") "
                    + FactNames.escape(String.valueOf(error.getMessage())));
        }
        return String.join("; ", described);
    }

    /**
     * Tells whether the root cause of a compile error is an {@code assert} inside MVEL that failed.
     *
     * @param root The root cause
     * @return {@code true} if it's an {@link AssertionError} whose top stack frame is in MVEL's code, and not in code
     *         MVEL called, such as the JDK's
     */
    private static boolean failedAssert(Throwable root) {
        return root instanceof AssertionError && ExceptionReads.thrownFrom(root, ExceptionReads.MVEL_PACKAGE);
    }

    /**
     * Tells whether an {@link IndexOutOfBoundsException} from compiling came from MVEL's own code rather than from
     * code MVEL called, such as the application's class loader: whether the first frame of its stack trace outside
     * the JDK is in MVEL's package, as it is when the JDK's own bounds check threw for MVEL. One whose stack trace is
     * empty or can't be read came out of the call to MVEL all the same, as HotSpot throws a frequent one without a
     * stack trace, so it's MVEL's too.
     *
     * @param e The exception
     * @return {@code true} if MVEL threw it
     */
    static boolean thrownInMvel(IndexOutOfBoundsException e) {
        for (StackTraceElement frame : ExceptionReads.stackTraceOf(e)) {
            String className = frame.getClassName();
            if (!isJdk(className)) {
                return className.startsWith(ExceptionReads.MVEL_PACKAGE);
            }
        }
        return true;
    }

    private static boolean isJdk(String className) {
        for (String jdkPackage : JDK_PACKAGES) {
            if (className.startsWith(jdkPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names the root cause of a compile error whose description MVEL left missing, in the engine's note: its class,
     * and its message if it has one, as {@code core.Failures} names one, escaped as the engine escapes its messages so
     * the issue's description is one line too. The engine shortens the whole message it reports.
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
        return " (caused by " + root.getClass().getName()
                + (rootMessage == null ? "" : ": " + FactNames.escape(rootMessage)) + ")";
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
