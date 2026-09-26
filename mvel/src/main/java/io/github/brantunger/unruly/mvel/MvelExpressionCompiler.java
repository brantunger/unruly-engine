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

    // How MVEL's message for a compile error starts, before its description: [Error: unbalanced braces ( ... )], then
    // a line break and the excerpt of the expression MVEL found it near, as [Near : {... x = ( ....}].
    private static final String ERROR_START = "[Error: ";
    // What follows the description in MVEL's message. The description can hold the expression's text, or a nested
    // error's message, and so this too, but not the excerpt, which MVEL cuts before a line break, so the last one is
    // MVEL's.
    private static final String NEAR = "]\n[Near : {... ";
    // The line of a message without MVEL's excerpt that is its description, should MVEL ever write one: MVEL 2.5.4
    // always writes the excerpt. MVEL ends its lines with \n alone, so a line or paragraph separator from the
    // expression's text doesn't end one.
    private static final Pattern ERROR = Pattern.compile("^\\[Error: (.*)]$", Pattern.MULTILINE | Pattern.UNIX_LINES);
    // Where MVEL found the error, such as [Line: 1, Column: 11], on a line of its own. MVEL's message quotes the
    // expression, and a nested error's message, before it, so the last such line is MVEL's.
    private static final Pattern POSITION = Pattern.compile("^\\[Line: (\\d+), Column: (\\d+)]$",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
    // What MVEL says of a declaration whose first token isn't a class it knows, such as BigDecimal total = 0 without
    // the import, or x y.
    private static final String UNKNOWN_CLASS = "unknown class or illegal statement";
    // What MVEL names after it that is the type it couldn't resolve: an array type's name, such as Zzz[] in
    // Zzz[] z = null. MVEL names its last node, which is otherwise its own parser context, whose hash differs every
    // time, or the literal that ends the statement before, such as 5 in output.n = 5, then a line break and
    // BigDecimal total = 0.
    private static final Pattern UNKNOWN_ARRAY_TYPE = Pattern.compile(
            Pattern.quote(UNKNOWN_CLASS + ": ") + "[\\p{javaJavaIdentifierPart}.$]+(\\[])+");
    // What an expression that calls an imported class like a method, such as ArrayList(y), is reported as.
    private static final String CLASS_CALLED_LIKE_METHOD = "a class can't be called like a method: use new";
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
        MvelExpression.Analysis analysis = new MvelExpression.Analysis(source.text(), imports);
        try {
            MvelExpression expression = MvelExpression.compile(analysis);
            compiled.add(expression);
            return expression;
        } catch (CompileException e) {
            // The engine reports an expression too long for MVEL's recursive parser as such.
            if (ExceptionReads.rootCause(e) instanceof StackOverflowError) {
                throw e;
            }
            throw compileError(e, analysis.rejectedType());
        } catch (IndexOutOfBoundsException e) {
            // MVEL's parser reads out of bounds for some malformed expressions, such as a . or ? it can't read past or
            // blank parentheses, instead of reporting them. One from other code, such as the application's class
            // loader, is reported as is.
            if (!thrownInMvel(e)) {
                throw e;
            }
            throw positionless(MALFORMED_EXPRESSION, e);
        } catch (RuntimeException e) {
            // An imported class called like a method, such as ArrayList(y), fails MVEL's cast to a static method.
            if (calledLikeMethod(e)) {
                throw positionless(CLASS_CALLED_LIKE_METHOD, e);
            }
            // MVEL throws a plain RuntimeException, with no cause and no position, for some expressions it rejects,
            // such as int in = 1 or an ambiguous class name. Any other is reported as is.
            if (!rejectedPlainly(e)) {
                throw e;
            }
            throw positionless(FactNames.escape(FactNames.truncate(String.valueOf(e.getMessage()))), e);
        }
    }

    /**
     * Tells whether compiling failed because the expression calls an imported class like a method, such as
     * {@code ArrayList(y)} with {@code java.util} imported, going by the type of what the engine's configuration
     * threw in place of MVEL's cast (see {@link Imports.ClassCalledLikeMethod}), anywhere in the cause chain. Its
     * type, not its message or stack trace: HotSpot throws MVEL's own frequent {@link ClassCastException} without
     * either.
     *
     * @param e What compiling threw
     * @return {@code true} if an imported class was called like a method
     */
    private static boolean calledLikeMethod(RuntimeException e) {
        return ExceptionReads.causeChain(e).stream().anyMatch(Imports.ClassCalledLikeMethod.class::isInstance);
    }

    /**
     * Tells whether a {@link RuntimeException} from compiling is one MVEL threw to reject the expression: whether its
     * class is {@code RuntimeException} itself, the top frame of its stack trace is in MVEL's code and it has no
     * cause. MVEL throws one for a reserved word or a digit as a typed variable's name, such as in {@code int in = 1}
     * or {@code int 1x = 2}, for a typed variable declared twice, and for a class name two imported packages have,
     * such as {@code List} with {@code java.util} and {@code java.awt} imported. One MVEL throws around a failure of
     * other code, such as the application's class loader, has that failure as its cause.
     *
     * @param e The exception
     * @return {@code true} if MVEL threw it to reject the expression
     */
    static boolean rejectedPlainly(RuntimeException e) {
        return RuntimeException.class.equals(e.getClass()) && ExceptionReads.thrownFrom(e, ExceptionReads.MVEL_PACKAGE)
                && e.getCause() == null;
    }

    /**
     * Reports an error MVEL gave no line or column for, with one issue that has none either. For an expression MVEL
     * rejected with a plain {@link RuntimeException} (see {@link #rejectedPlainly}), MVEL's message, shortened and
     * escaped as the engine shortens and escapes its messages, is the description, such as {@code failed to compile:
     * illegal use of reserved word: in}. An expression MVEL's parser read out of bounds for, as it does for a
     * {@code .} or {@code ?} it can't read past, such as in {@code b.}, {@code b. == 1} or {@code foo(?)}, or for blank
     * parentheses, such as in {@code ( ) + 1}, is {@code failed to compile: malformed expression}. An expression that
     * calls an imported class like a method, such as {@code ArrayList(y)} with {@code java.util} imported, is
     * {@code failed to compile: a class can't be called like a method: use new}. What MVEL threw is the cause.
     *
     * @param description The description, escaped
     * @param e           What MVEL threw
     * @return The exception to throw
     */
    private static InvalidExpressionException positionless(String description, RuntimeException e) {
        return new InvalidExpressionException("failed to compile: " + description, List.of(
                new InvalidExpressionException.Issue(InvalidExpressionException.Issue.Severity.ERROR, 0, 0,
                        description)), e);
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
     * MVEL's description, from its message or its list, can hold the expression's own text, so it's shortened and
     * escaped as the engine shortens and escapes its messages: cut to 1,000 characters, saying how many were left out,
     * and a line break shown as {@code \n}, so it's one line in the issue too.
     * </p>
     *
     * <p>
     * The position is the last line of MVEL's message that holds only one, as MVEL's message quotes the expression
     * before it. When MVEL nests one error's message in another's, as for {@code x = 1 &&}, the description is the
     * innermost error's, and the position still the outer one's.
     * </p>
     *
     * <p>
     * MVEL's description of a declaration whose first token isn't a class it knows, such as
     * {@code BigDecimal total = 0} without the import, or {@code x y}, names MVEL's parser context, which differs every
     * time, or the literal that ends the statement before, and only names the type for an array type, such as
     * {@code Zzz[]}. The description is MVEL's words, {@code unknown class or illegal statement}, then the type's name
     * when it's an array type's, or else when MVEL's analysis pass could read it (see
     * {@link #compileError(CompileException, String)}), such as {@code unknown class or illegal statement: BigDecimal},
     * at MVEL's line and column.
     * </p>
     *
     * <p>
     * When the root cause is a {@link NullPointerException} thrown in MVEL's own code, as one is for an operator with
     * nothing after it, such as {@code x = y &&}, the description is
     * {@code not a statement, or badly formed structure}: MVEL's own for such a failure elsewhere in its parser.
     * When the root cause is an {@link IndexOutOfBoundsException} thrown in MVEL's own code, as one is for
     * {@code x == 1 && in}, and MVEL's description is only its message, the description is
     * {@code malformed expression}, as for one MVEL throws bare, but at MVEL's line and column. Both kinds count as
     * MVEL's without a stack trace too (see {@link #thrownInMvel}), so the description doesn't change once HotSpot
     * throws them without one or without a message.
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
        return compileError(e, null);
    }

    /**
     * Reports an error MVEL found while compiling, as {@link #compileError(CompileException)} does, naming the type of
     * a declaration MVEL couldn't resolve as MVEL's analysis pass read it.
     *
     * @param e            What MVEL threw
     * @param rejectedType The type MVEL's analysis pass rejected a declaration of, or {@code null} if it can't tell
     *                     (see {@link MvelExpression.Analysis#rejectedType()})
     * @return The exception to throw
     */
    static InvalidExpressionException compileError(CompileException e, String rejectedType) {
        String message = String.valueOf(ExceptionReads.messageOf(e));
        List<ErrorDetail> errors = e.getErrors();
        String description;
        List<Throwable> chain = ExceptionReads.causeChain(e);
        Throwable root = chain.get(chain.size() - 1);
        if (!errors.isEmpty()) {
            description = oneLine(errors);
        } else if (nullPointerInMvel(root)) {
            description = BADLY_FORMED;
        } else {
            String described = described(innermost(chain));
            description = outOfBoundsInMvel(root, described) ? MALFORMED_EXPRESSION
                    : FactNames.escape(FactNames.truncate(named(described, rejectedType)));
        }
        if (MISSING_DESCRIPTION.equals(description)) {
            description = failedAssert(root) ? BADLY_FORMED : description + causeNote(chain);
        }
        int line = 0;
        int column = 0;
        Matcher position = POSITION.matcher(message);
        while (position.find()) {
            line = Integer.parseInt(position.group(1));
            column = Integer.parseInt(position.group(2));
        }
        InvalidExpressionException.Issue issue = new InvalidExpressionException.Issue(
                InvalidExpressionException.Issue.Severity.ERROR, line, column, description);
        String where = issue.line() == 0 ? "" : " at line " + issue.line() + ", column " + issue.column();
        return new InvalidExpressionException("failed to compile" + where + ": " + description, List.of(issue), e);
    }

    /**
     * Finds the innermost MVEL error in a compile error's cause chain, whose message MVEL nests in the others'.
     *
     * @param chain What MVEL threw, a {@link CompileException}, and its causes
     * @return The last {@link CompileException} in the chain, which is the first link if there is no other
     */
    private static CompileException innermost(List<Throwable> chain) {
        CompileException innermost = (CompileException) chain.get(0);
        for (Throwable link : chain) {
            if (link instanceof CompileException compileError) {
                innermost = compileError;
            }
        }
        return innermost;
    }

    /**
     * Reads MVEL's description of an error from its message, which MVEL always writes as {@code [Error: }, the
     * description and {@code ]}, then a line break and the excerpt of the expression MVEL found it near, which starts
     * {@code [Near : }, such as {@code unbalanced braces ( ... )} from {@code [Error: unbalanced braces ( ... )]}. The
     * description ends at the last such {@code ]}, line break and excerpt, as the description can hold line breaks,
     * the expression's own text and a nested error's message, but MVEL's excerpt after it can't. A message without
     * that excerpt has its description on a line of its own, {@code [Error: ...]}, and one without that is the
     * description whole.
     *
     * <p>
     * Of what MVEL names after {@code unknown class or illegal statement: } for a declaration whose first token isn't
     * a class it knows, only an array type's name, such as {@code Zzz[]}, is kept: MVEL names its last node, which is
     * otherwise its parser context or the literal that ends the statement before, not the type.
     * </p>
     *
     * @param e What MVEL threw
     * @return The description, not escaped
     */
    private static String described(CompileException e) {
        String message = String.valueOf(ExceptionReads.messageOf(e));
        int near = message.lastIndexOf(NEAR);
        String description;
        if (message.startsWith(ERROR_START) && near >= ERROR_START.length()) {
            description = message.substring(ERROR_START.length(), near);
        } else {
            Matcher error = ERROR.matcher(message);
            description = error.find() ? error.group(1) : message;
        }
        return description.startsWith(UNKNOWN_CLASS + ": ") && !UNKNOWN_ARRAY_TYPE.matcher(description).matches()
                ? UNKNOWN_CLASS : description;
    }

    /**
     * Names the type of a declaration MVEL couldn't resolve after MVEL's words, when MVEL named none.
     *
     * @param description  MVEL's description, not escaped
     * @param rejectedType The type MVEL's analysis pass rejected a declaration of, or {@code null} if it can't tell
     * @return {@code unknown class or illegal statement: } and the type if MVEL's description is those words alone and
     *         the type is known, or else the description
     */
    private static String named(String description, String rejectedType) {
        return rejectedType != null && UNKNOWN_CLASS.equals(description)
                ? UNKNOWN_CLASS + ": " + rejectedType : description;
    }

    /**
     * Puts the errors MVEL listed with its exception on one line: one error's description alone, as the issue has
     * its line and column, or several as {@code (line,column) description}, joined with {@code ; }. The line is
     * shortened and escaped as the engine shortens and escapes its messages, as each description can hold the
     * expression's own text; the positions and separators hold nothing escaping changes.
     *
     * @param errors The errors, at least one
     * @return The errors on one line
     */
    private static String oneLine(List<ErrorDetail> errors) {
        if (errors.subList(1, errors.size()).isEmpty()) {
            return FactNames.escape(FactNames.truncate(String.valueOf(errors.get(0).getMessage())));
        }
        List<String> described = new ArrayList<>();
        for (ErrorDetail error : errors) {
            described.add("(" + error.getLineNumber() + "," + error.getColumn() + ") " + error.getMessage());
        }
        return FactNames.escape(FactNames.truncate(String.join("; ", described)));
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
     * Tells whether the root cause of a compile error is a {@link NullPointerException} MVEL's own code threw, as it
     * does for an operator with nothing after it, such as in {@code x = y &&}.
     *
     * @param root The root cause
     * @return {@code true} if it's a {@link NullPointerException} MVEL threw, as {@link #thrownInMvel} tells, which
     *         one HotSpot throws without a stack trace, once MVEL has thrown it often, is too
     */
    private static boolean nullPointerInMvel(Throwable root) {
        return root instanceof NullPointerException && thrownInMvel(root);
    }

    /**
     * Tells whether the root cause of a compile error is an {@link IndexOutOfBoundsException} MVEL's own code threw, or
     * the JDK's bounds check threw for it, as for {@code x == 1 && in}, which MVEL wraps in its own error with the
     * exception's message as its description, such as {@code Index 35 out of bounds for length 34}. A description of
     * MVEL's own, such as {@code unexpected end of statement}, which MVEL gives an out-of-bounds read it catches in
     * its parser, as for {@code if (x) y = 1}, is MVEL's to report.
     *
     * @param root        The root cause
     * @param description MVEL's description, not escaped
     * @return {@code true} if it's an {@link IndexOutOfBoundsException} MVEL threw, as {@link #thrownInMvel} tells,
     *         which one HotSpot throws without a stack trace or a message, once MVEL has thrown it often, is too, and
     *         the description is its message, or {@code null} if it has none
     */
    private static boolean outOfBoundsInMvel(Throwable root, String description) {
        return root instanceof IndexOutOfBoundsException
                && description.equals(String.valueOf(ExceptionReads.messageOf(root))) && thrownInMvel(root);
    }

    /**
     * Tells whether an exception from compiling, such as an {@link IndexOutOfBoundsException} or a
     * {@link NullPointerException}, came from MVEL's own code rather than from code MVEL called, such as the
     * application's class loader: whether the first frame of its stack trace outside the JDK is in MVEL's package, as
     * it is when the JDK's own bounds check threw for MVEL. One whose stack trace is empty or can't be read came out
     * of the call to MVEL all the same, as HotSpot throws a frequent one without a stack trace, so it's MVEL's too.
     *
     * @param e The exception
     * @return {@code true} if MVEL threw it
     */
    static boolean thrownInMvel(Throwable e) {
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
     * and its message if it has one, as {@code core.Failures} names one. Each is shortened and escaped as the engine
     * shortens and escapes its messages, the class name as a name, so the issue's description is one line too, and
     * the note is never cut off: it's added after the description is shortened.
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
        return " (caused by " + FactNames.quote(root.getClass().getName())
                + (rootMessage == null ? "" : ": " + FactNames.escape(FactNames.truncate(rootMessage))) + ")";
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
