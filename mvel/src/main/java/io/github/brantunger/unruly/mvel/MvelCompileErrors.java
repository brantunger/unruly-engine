package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.MessageText;
import io.github.brantunger.unruly.api.language.Throwables;
import org.jspecify.annotations.Nullable;
import org.mvel2.CompileException;
import org.mvel2.ErrorDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How an MVEL compile error reads: tells which of what compiling an expression threw is a compile error (see
 * {@link #translate}), and turns one, or what the compiler found in a condition, into an
 * {@link InvalidExpressionException} with one issue at {@code ERROR}, its message and its issue built in one place.
 * What isn't one, such as an overflow or a failure of the application's class loader, is given back to be thrown as
 * it is.
 */
final class MvelCompileErrors {

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
    // The keyword of an import; MVEL skips whitespace after it (see RuleText.isMvelWhitespace).
    private static final String IMPORT_KEYWORD = "import";
    // MVEL's description of an error whose message is missing, such as a class's failed static initializer.
    private static final String MISSING_DESCRIPTION = "null";
    // What MVEL says of a malformed statement such as b = = 1 when an assert inside MVEL doesn't stop it first.
    private static final String BADLY_FORMED = "not a statement, or badly formed structure";
    // What an expression MVEL's parser reads out of bounds for, such as b., ? or ( ), is reported as.
    private static final String MALFORMED_EXPRESSION = "malformed expression";
    // The engine's description of an expression MVEL's analysis went round in a loop over (see analysisLoop).
    private static final String ANALYSIS_LOOP = "MVEL's analysis went round in a loop, as it does for a call through a "
            + "class named with its package with something glued to it, such as java.lang.Math.abs(1)x";
    // What an import of a whole package whose last '.' MVEL can't read, at index 32,768 or later of the expression, is
    // reported as. MVEL keeps that index in a short, so it wraps, and MVEL reads the name out of bounds.
    private static final String PACKAGE_IMPORT_TOO_FAR = "the '.' before the '*' of an import of a whole package must "
            + "be within the first 32,768 characters of the expression, as far as MVEL can read one: move the import "
            + "nearer the start, or import the package with the engine's imports";
    // Where MVEL reads the name of an import of a whole package, and the class it is in.
    private static final String IMPORT_NODE_CLASS = ExceptionReads.MVEL_PACKAGE + "ast.ImportNode";
    private static final String PACKAGE_IMPORT_METHOD = "getPackageImport";
    // The JDK's packages, whose frames are skipped to find whose code threw, as when the JDK's bounds check threw.
    private static final List<String> JDK_PACKAGES = List.of("java.", "jdk.", "sun.", "com.sun.");

    private MvelCompileErrors() {
    }

    /**
     * Reads a message as MVEL writes one for a compile error, with the regular expressions this class reads MVEL's
     * position and description with, so the JDK's classes their first match uses, such as {@code Matcher} and its
     * {@code IntHashSet}, are loaded now: the JVM's first compile error, maybe deep in a stack, would otherwise be the
     * first to load them, which can overflow, and the error would be reported as the overflow (#1097). Called by
     * {@link MvelExpressionLanguage#prepare()}.
     */
    static void warmUp() {
        String message = ERROR_START + UNKNOWN_CLASS + ": Zzz[]" + NEAR + "x ....}]\n[Line: 1, Column: 1]";
        POSITION.matcher(message).find();
        ERROR.matcher(message).find();
        UNKNOWN_ARRAY_TYPE.matcher(UNKNOWN_CLASS + ": Zzz[]").matches();
        // A load that fails for an import of a whole package MVEL can't read makes MVEL build a String with bounds
        // outside its text, and the JDK formats that exception's message with String.format. JDK 21's
        // Formatter compiles a regular expression in its static initializer, whose classes that failing load would
        // be the first to load (#1093). This loads them: an optional group and a range, for Pattern's Branch,
        // BranchConn and range predicate, as MVEL's former escape pattern did by accident. Formatter itself is left
        // to its first use (#1097, #1100); JDK 24 and later parse the format without a pattern.
        Pattern.compile("([0-9])?x").matcher("x").matches();
    }

    /**
     * A place in an expression's text, as MVEL's own compile errors give one. Not private, so
     * {@link MvelExpressionLanguage#prepare()} can load it: a failed load's first use of it, maybe deep in a stack,
     * could overflow loading it (#1097). A plain class, not a record: nothing compares or prints one, and a record's
     * own methods would link through {@code ObjectMethods}, which can fail for good when first called deep in the
     * stack (#996).
     */
    // Each accessor is named after the field it returns, as a record's are.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    static final class Position {
        private final int line;
        private final int column;

        /**
         * @param line   The line, counting from 1
         * @param column The column, counting from 1
         */
        Position(int line, int column) {
            this.line = line;
            this.column = column;
        }

        /** The line, counting from 1. */
        int line() {
            return line;
        }

        /** The column, counting from 1. */
        int column() {
            return column;
        }
    }

    /**
     * Returns what to throw for what compiling an expression threw: the {@link InvalidExpressionException} that
     * describes it when it's a compile error the engine describes, or else the exception itself, to be thrown as it
     * is. A {@link CompileException} is reported by {@link #analysisLoop} when MVEL's analysis went round in a loop
     * (see {@link #looped}), returned as it is when an overflow is its cause (see {@link Throwables#isOverflow}),
     * and otherwise reported by {@link #compileError(CompileException, String)}; an {@link Imports.ImportRejected} by
     * {@link #importRejected}; an {@link IndexOutOfBoundsException} by {@link #malformed} when MVEL's own code threw
     * it (see {@link #thrownInMvel}); and any other by {@link #classCalledLikeMethod} when an imported class was
     * called like a method (see {@link #calledLikeMethod}), or by {@link #plainRejection} when MVEL threw it to
     * reject the expression (see {@link #rejectedPlainly}). An {@link IndexOutOfBoundsException} or other exception
     * from code MVEL called, such as the application's class loader, is returned as it is.
     *
     * @param e        What compiling threw
     * @param analysis The analysis pass over the expression, for its text and the type it rejected
     * @return The exception to throw: one that describes the error, or {@code e} itself
     */
    // A chain of instanceof checks rather than a pattern switch, whose call site the JVM links with an invokedynamic
    // the first time it runs, which could overflow deep in a stack, as a lambda's could (#1097).
    static RuntimeException translate(RuntimeException e, MvelAnalysis analysis) {
        if (e instanceof CompileException compileError) {
            if (looped(compileError)) {
                return analysisLoop(compileError);
            }
            // The engine reports an overflow by its cause: an expression too long for MVEL's recursive parser, or a
            // compile called too deep in the stack or on a thread with a small one. A class an overflow left unusable
            // isn't one, and is reported as MVEL's compile error, as on JDK 21, whose NoClassDefFoundError has no
            // cause (#1116).
            if (Throwables.isOverflow(compileError)) {
                return e;
            }
            return compileError(compileError, analysis.rejectedType());
        }
        if (e instanceof Imports.ImportRejected rejected) {
            return importRejected(analysis.sourceText(), rejected);
        }
        if (e instanceof IndexOutOfBoundsException) {
            // MVEL's parser reads out of bounds for some malformed expressions, such as a . or ? it can't read past or
            // blank parentheses, instead of reporting them. One from other code, such as the application's class
            // loader, is reported as is.
            return thrownInMvel(e) ? malformed(e) : e;
        }
        // An imported class called like a method, such as ArrayList(y), fails MVEL's cast to a static method.
        if (calledLikeMethod(e)) {
            return classCalledLikeMethod(e);
        }
        // MVEL throws a plain RuntimeException, with no cause and no position, for some expressions it rejects, such
        // as int in = 1 or an ambiguous class name. Any other is reported as is.
        return rejectedPlainly(e) ? plainRejection(e) : e;
    }

    /**
     * Reports an assignment or {@code import_static} found in a condition, with one issue that says where it starts:
     * the line and column, counting from 1, as MVEL's own compile errors say.
     *
     * @param condition The condition's source text
     * @param write     What was found, and where
     * @return The exception to throw
     */
    static InvalidExpressionException rejected(String condition, ConditionAssignments.Write write) {
        Position position = positionOf(condition, write.position());
        String where = "at line " + position.line() + ", column " + position.column();
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
        return new InvalidExpressionException(message, issues(position.line(), position.column(), description));
    }

    /**
     * Reports an {@code import} in the expression's own text of a package or a class too long, or with too many
     * parts, to look up, with one issue at the name, such as {@code Can't import 'a.a.a...': it has 65 dot-separated
     * parts, and an import may have at most 64}: the engine's own words for such an import. An import of a class with a
     * public static field whose value is {@code null} is reported the same way, such as
     * {@code Can't import 'acme.Bar.*': its public static field 'X' is null}. The line and column count
     * from 1, as MVEL's own compile errors do, and the exception's message reads as {@link #compileError}'s, such as
     * {@code failed to compile at line 1, column 8: Can't import ...}.
     *
     * <p>
     * The position is that of the first {@code import} of the name in the text outside string literals and comments:
     * the name where it first follows {@code import} and any whitespace. The name's text in a string or a comment
     * before it isn't taken for it, even after {@code import}. The scan ends a comment where MVEL ends it, so
     * {@code /*}{@code /} is a whole comment (#747), and MVEL passes the name as a slice of the text, from just after
     * the keyword and the characters it skips as whitespace, so the {@code import} MVEL read is always found, as it is
     * for a class import, whose name the class loader passes without the whitespace that ends it. With no
     * {@code import} of the name in the text at all, which MVEL never passes, the error is reported with no line or
     * column, as {@link #positionless} reports one.
     * </p>
     *
     * <p>
     * A name that escapes to more than the message has room for is shortened, as MVEL's descriptions are (see
     * {@link MessageText#quoteWithin}), so the message is at most 1,000 characters.
     * </p>
     *
     * @param text The expression's source text, which holds the name as MVEL read it
     * @param e    What the configuration threw
     * @return The exception to throw
     */
    static InvalidExpressionException importRejected(String text, Imports.ImportRejected e) {
        // Where the scan reads code: a literal or a comment is skipped whole, as the check for assignments in a
        // condition skips it. A comment that doesn't end takes the index to the end of the text, and a literal that
        // doesn't end, past it. An array rather than a BitSet, whose first use, here in a failing load, would
        // initialize it, maybe deep in a stack (#1097).
        boolean[] code = new boolean[text.length()];
        int index = 0;
        while (index < text.length()) {
            code[index] = true;
            index = switch (text.charAt(index)) {
                case '\'', '"' -> ConditionAssignments.endOfLiteral(text, index);
                case '/' -> ConditionAssignments.endOfSlash(text, index);
                default -> index + 1;
            };
        }
        // MVEL passes the name as a slice of the text just after its import, so the name follows an import there, and
        // that import is in code as MVEL reads it. The scan reads comments and literals as MVEL does (#747), so it
        // finds MVEL's import in code. The first import of the name in the text is the answer only if that ever
        // failed, and is still a place in the text, so the position is always within it.
        String name = e.rejectedName();
        // The first in code, and only if there is none, the first of all, each import of the name found after the last
        // one ends, as a regular expression's find() finds them. A loop rather than a stream and a lambda, and a scan
        // rather than a regular expression: this runs only when a load fails, and the JVM's first such load, maybe
        // deep in a stack, would link the lambda and first use the JDK's stream classes, or the classes of a pattern
        // the warm-up didn't use, there, any of which can overflow (#1012, #1093, #1097).
        int at = -1;
        int first = -1;
        int keyword = text.indexOf(IMPORT_KEYWORD);
        while (at < 0 && keyword >= 0) {
            int named = nameAfterImport(text, keyword, name);
            if (named < 0) {
                keyword = text.indexOf(IMPORT_KEYWORD, keyword + 1);
            } else {
                first = first < 0 ? named : first;
                at = code[keyword] ? named : -1;
                keyword = text.indexOf(IMPORT_KEYWORD, named + name.length());
            }
        }
        if (at < 0) {
            // With no import of the name in the text at all, which MVEL never passes, there is no place to report it
            // at: positionOf would read before the text's start.
            if (first < 0) {
                return positionless(e.describedWithin(roomAfter(messageStart(0, 0))), e);
            }
            at = first;
        }
        Position position = positionOf(text, at);
        String description = e.describedWithin(roomAfter(messageStart(position.line(), position.column())));
        return at(position.line(), position.column(), description, e);
    }

    /**
     * Finds a name just after an {@code import}, past the whitespace MVEL skips after the keyword, as
     * {@code import[\x00-\x20]*(name)} would match at the keyword: the most whitespace that leaves the name after it.
     *
     * @param text    The expression's source text
     * @param keyword Where an {@code import} starts in it
     * @param name    The name
     * @return Where the name starts, or -1 if it doesn't follow that {@code import}
     */
    private static int nameAfterImport(String text, int keyword, String name) {
        int after = keyword + IMPORT_KEYWORD.length();
        int end = after;
        while (end < text.length() && RuleText.isMvelWhitespace(text.charAt(end))) {
            end++;
        }
        for (int at = end; at >= after; at--) {
            if (text.startsWith(name, at)) {
                return at;
            }
        }
        return -1;
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
    static boolean calledLikeMethod(RuntimeException e) {
        // A loop rather than a stream and a method reference, as in looped() (#1097).
        for (Throwable link : Throwables.causeChain(e)) {
            if (link instanceof Imports.ClassCalledLikeMethod) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a {@link RuntimeException} from compiling is one MVEL threw to reject the expression: whether its
     * class is {@code RuntimeException} itself, MVEL's code threw it, going by the top frame of its stack trace, and it
     * has no cause. MVEL throws one for a reserved word or a digit as a typed variable's name, such as in
     * {@code int in = 1} or {@code int 1x = 2}, for a typed variable declared twice, and for a class name two imported
     * packages have, such as {@code List} with {@code java.util} and {@code java.awt} imported. One MVEL throws around
     * a failure of other code, such as the application's class loader, has that failure as its cause.
     *
     * <p>
     * Unlike {@link #thrownInMvel}, the check doesn't look past the JDK's frames, so an exception from code MVEL
     * called, such as a {@link RuntimeException} from the application's class loader or an {@link AssertionError} from
     * the JDK (see {@link #compileError}), isn't MVEL's. One with an empty stack trace came out of the call to MVEL
     * all the same, as every exception does on a JVM run with {@code -XX:-StackTraceInThrowable}, so it's MVEL's, as
     * {@link #thrownInMvel} decides too. One whose stack trace can't be read, which {@link #thrownInMvel} counts as
     * MVEL's, isn't: only a subclass can fail to give its stack trace, and MVEL throws a plain
     * {@link AssertionError} or {@link RuntimeException}. A failure while a rule runs is read the other way, as
     * {@code CalledCodeFailures} tells: there one with no stack trace can be the rule's own code's, and is left as MVEL
     * threw it.
     * </p>
     *
     * @param e The exception
     * @return {@code true} if MVEL threw it to reject the expression
     */
    static boolean rejectedPlainly(RuntimeException e) {
        return RuntimeException.class.equals(e.getClass())
                && ExceptionReads.topFrameIn(e, ExceptionReads.MVEL_PACKAGE, true) && e.getCause() == null;
    }

    /**
     * Starts the message of an error MVEL found: {@code failed to compile: }, or with a line other than 0,
     * {@code failed to compile at line 1, column 8: }, before its description.
     *
     * @param line   The line, counting from 1, or 0 if there is none
     * @param column The column, counting from 1
     * @return The message's start
     */
    private static String messageStart(int line, int column) {
        return "failed to compile" + (line == 0 ? "" : " at line " + line + ", column " + column) + ": ";
    }

    /**
     * Tells how many characters a message's start leaves for its description in the engine's limit, so the engine
     * reports the message without shortening it again (see {@link MessageText#escapeWithin}).
     *
     * @param start The message's start, from {@link #messageStart}
     * @return The room for the description
     */
    private static int roomAfter(String start) {
        return MessageText.MAX_TEXT_LENGTH - start.length();
    }

    /**
     * Finds the line and column of a place in an expression's text, counting from 1, as MVEL's own compile errors do.
     *
     * @param text   The expression's source text
     * @param offset The place, as an index into the text counting from 0
     * @return The line and column
     */
    private static Position positionOf(String text, int offset) {
        String before = text.substring(0, offset);
        // A loop rather than a stream, whose first use, here in a failing load, would initialize the JDK's classes
        // for it, maybe deep in a stack (#1012).
        int line = 1;
        for (int i = 0; i < before.length(); i++) {
            if (before.charAt(i) == NEW_LINE) {
                line++;
            }
        }
        int column = offset - (before.lastIndexOf(NEW_LINE) + 1) + 1;
        return new Position(line, column);
    }

    /**
     * The one issue of a compile error: at {@code ERROR}, with the error's line, column and description.
     *
     * @param line        The line, counting from 1, or 0 if there is none
     * @param column      The column, counting from 1, or 0 if there is none
     * @param description The description, escaped
     * @return The issues to report
     */
    private static List<InvalidExpressionException.Issue> issues(int line, int column, String description) {
        return List.of(new InvalidExpressionException.Issue(
                InvalidExpressionException.Issue.Severity.ERROR, line, column, description));
    }

    /**
     * Reports an error found while compiling, with one issue that has its description, line and column, and the
     * message {@code failed to compile at line 1, column 8: } or, with no line, {@code failed to compile: }, then the
     * description (see {@link #messageStart}).
     *
     * @param line        The line, counting from 1, or 0 if there is none
     * @param column      The column, counting from 1, or 0 if there is none
     * @param description The description, escaped and shortened to the room its message's start leaves
     * @param cause       What was thrown
     * @return The exception to throw
     */
    private static InvalidExpressionException at(int line, int column, String description, Throwable cause) {
        return new InvalidExpressionException(messageStart(line, column) + description,
                issues(line, column, description), cause);
    }

    /**
     * Reports an expression MVEL's parser read out of bounds for, as {@code failed to compile: malformed expression}
     * (see {@link #positionless}).
     *
     * @param e What MVEL threw
     * @return The exception to throw
     */
    static InvalidExpressionException malformed(RuntimeException e) {
        return positionless(MALFORMED_EXPRESSION, e);
    }

    /**
     * Tells whether compiling failed because MVEL's analysis went round in a loop, going by the
     * {@link Imports.AnalysisLoop} that stopped it, anywhere in the cause chain (see {@link MvelAnalysis#compile()}).
     *
     * @param e What compiling threw
     * @return {@code true} if MVEL's analysis went round in a loop
     */
    static boolean looped(CompileException e) {
        // A loop rather than a stream and a method reference: every compile error comes here first, and the JVM's
        // first, maybe deep in a stack, would link the method reference and first use the JDK's stream classes there,
        // either of which can overflow, and the compile error would be reported as the overflow (#1097).
        for (Throwable link : Throwables.causeChain(e)) {
            if (link instanceof Imports.AnalysisLoop) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports an expression MVEL's analysis went round in a loop over (see {@link #looped}), as {@code failed to
     * compile: MVEL's analysis went round in a loop, as it does for ...} (see {@link #positionless}): MVEL gives no
     * place for it.
     *
     * @param e What compiling threw
     * @return The exception to throw
     */
    static InvalidExpressionException analysisLoop(CompileException e) {
        return positionless(ANALYSIS_LOOP, e);
    }

    /**
     * Reports an expression that calls an imported class like a method (see {@link #calledLikeMethod}), as
     * {@code failed to compile: a class can't be called like a method: use new} (see {@link #positionless}).
     *
     * @param e What compiling threw
     * @return The exception to throw
     */
    static InvalidExpressionException classCalledLikeMethod(RuntimeException e) {
        return positionless(CLASS_CALLED_LIKE_METHOD, e);
    }

    /**
     * Reports an expression MVEL rejected with a plain {@link RuntimeException} (see {@link #rejectedPlainly}), with
     * MVEL's message as the description (see {@link #positionless}).
     *
     * @param e What MVEL threw
     * @return The exception to throw
     */
    static InvalidExpressionException plainRejection(RuntimeException e) {
        return positionless(MessageText.escapeWithin(String.valueOf(e.getMessage()), roomAfter(messageStart(0, 0))), e);
    }

    /**
     * Reports an error MVEL gave no line or column for, with one issue that has none either. For an expression MVEL
     * rejected with a plain {@link RuntimeException} (see {@link #rejectedPlainly}), MVEL's message, escaped as the
     * engine escapes its messages and shortened so the whole message fits in the engine's limit (see
     * {@link MessageText#escapeWithin}), is the description, such as {@code failed to compile: illegal use of reserved
     * word: in}. An expression MVEL's parser read out of bounds for, as it does for a
     * {@code .} or {@code ?} it can't read past, such as in {@code b.}, {@code b. == 1} or {@code foo(?)}, or for blank
     * parentheses, such as in {@code ( ) + 1}, is {@code failed to compile: malformed expression}. An expression that
     * calls an imported class like a method, such as {@code ArrayList(y)} with {@code java.util} imported, is
     * {@code failed to compile: a class can't be called like a method: use new}. An import rejected whose name isn't
     * in the text after any {@code import}, which MVEL never passes (see {@link #importRejected}), is
     * {@code failed to compile: Can't import ...}. What MVEL threw is the cause.
     *
     * @param description The description, escaped
     * @param e           What MVEL threw
     * @return The exception to throw
     */
    private static InvalidExpressionException positionless(String description, RuntimeException e) {
        return at(0, 0, description, e);
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
     * engine escapes its messages, a line break shown as {@code \n}, so it's one line in the issue too, and shortened,
     * saying how many of its characters were left out, so the whole message, {@code failed to compile at line 1,
     * column 8: } and the engine's note on the root cause included, is at most 1,000 characters (see
     * {@link MessageText#escapeWithin}). The engine then reports the message without shortening it again, and the issue
     * has the same description.
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
     * MVEL can't read the name of an import of a whole package, such as {@code import java.util.*;}, whose {@code .}
     * before the {@code *} is at index 32,768 or later of the expression, and says {@code unexpected end of
     * statement} (see {@link #packageImportUnread}). The description says that instead, at MVEL's line and column,
     * which are those of the import's end: {@code the '.' before the '*' of an import of a whole package must be
     * within the first 32,768 characters of the expression, ...}.
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
     * {@code not a statement, or badly formed structure}: MVEL's own for {@code b = = 1} with assertions off. One
     * without a stack trace counts as MVEL's too (see {@link #rejectedPlainly}).
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
     *                     (see {@link MvelAnalysis#rejectedType()})
     * @return The exception to throw
     */
    static InvalidExpressionException compileError(CompileException e, @Nullable String rejectedType) {
        String message = String.valueOf(Throwables.messageOf(e));
        int line = 0;
        int column = 0;
        Matcher position = POSITION.matcher(message);
        while (position.find()) {
            line = Integer.parseInt(position.group(1));
            column = Integer.parseInt(position.group(2));
        }
        int room = roomAfter(messageStart(line, column));
        List<ErrorDetail> errors = e.getErrors();
        String description;
        List<Throwable> chain = Throwables.causeChain(e);
        Throwable root = chain.get(chain.size() - 1);
        if (!errors.isEmpty()) {
            description = oneLine(errors, room);
        } else if (packageImportUnread(chain)) {
            description = PACKAGE_IMPORT_TOO_FAR;
        } else if (nullPointerInMvel(root)) {
            description = BADLY_FORMED;
        } else {
            String described = described(innermost(chain));
            description = outOfBoundsInMvel(root, described) ? MALFORMED_EXPRESSION
                    : MessageText.escapeWithin(named(described, rejectedType), room);
        }
        if (MISSING_DESCRIPTION.equals(description)) {
            description = failedAssert(root) ? BADLY_FORMED
                    : description + causeNote(chain, room - description.length());
        }
        return at(line, column, description, e);
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
        String message = String.valueOf(Throwables.messageOf(e));
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
    private static String named(String description, @Nullable String rejectedType) {
        return rejectedType != null && UNKNOWN_CLASS.equals(description)
                ? UNKNOWN_CLASS + ": " + rejectedType : description;
    }

    /**
     * Puts the errors MVEL listed with its exception on one line: one error's description alone, as the issue has
     * its line and column, or several as {@code (line,column) description}, joined with {@code ; }. The line is
     * escaped as the engine escapes its messages and shortened to the room given (see
     * {@link MessageText#escapeWithin}), as each description can hold the expression's own text; the positions and
     * separators hold nothing escaping changes.
     *
     * @param errors The errors, at least one
     * @param room   The most characters the line may take
     * @return The errors on one line
     */
    private static String oneLine(List<ErrorDetail> errors, int room) {
        if (errors.subList(1, errors.size()).isEmpty()) {
            return MessageText.escapeWithin(String.valueOf(errors.get(0).getMessage()), room);
        }
        List<String> described = new ArrayList<>();
        for (ErrorDetail error : errors) {
            described.add("(" + error.getLineNumber() + "," + error.getColumn() + ") " + error.getMessage());
        }
        return MessageText.escapeWithin(String.join("; ", described), room);
    }

    /**
     * Tells whether an exception in a compile error's cause chain is MVEL failing to read the name of an import of a
     * whole package, such as {@code import java.util.*;}, whose last {@code .} is at index 32,768 or later of the
     * expression: MVEL 2.5.4 keeps that index in a {@code short}, which wraps, so reading the name throws a
     * {@link StringIndexOutOfBoundsException}, which MVEL reports as {@code unexpected end of statement}. It goes by
     * the frame of MVEL's method that reads the name, so one thrown without a stack trace isn't recognised, and MVEL's
     * own description stays.
     *
     * @param link An exception in the chain
     * @return {@code true} if it's a {@link StringIndexOutOfBoundsException} thrown reading a package import's name
     */
    private static boolean packageImportUnread(Throwable link) {
        if (!(link instanceof StringIndexOutOfBoundsException)) {
            return false;
        }
        // A loop rather than a stream and a lambda, as in packageImportUnread(List) (#1097).
        for (StackTraceElement frame : ExceptionReads.stackTraceOf(link)) {
            if (IMPORT_NODE_CLASS.equals(frame.getClassName())
                    && PACKAGE_IMPORT_METHOD.equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether any exception in a compile error's cause chain is MVEL failing to read a package import's name
     * (see {@link #packageImportUnread(Throwable)}).
     *
     * @param chain What MVEL threw, and its causes
     * @return {@code true} if one of them is
     */
    private static boolean packageImportUnread(List<Throwable> chain) {
        // A loop rather than a stream and a method reference: this runs only when a load fails, and the JVM's first
        // such load, maybe deep in a stack, would link the method reference and first use the JDK's stream classes
        // there, either of which can overflow, and the compile error would be reported as the overflow (#1097).
        for (Throwable link : chain) {
            if (packageImportUnread(link)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the root cause of a compile error is an {@code assert} inside MVEL that failed.
     *
     * @param root The root cause
     * @return {@code true} if it's an {@link AssertionError} MVEL's code threw, and not code MVEL called, such as the
     *         JDK's, going by the top frame of its stack trace, as for {@link #rejectedPlainly}, which one without
     *         a stack trace is too
     */
    private static boolean failedAssert(Throwable root) {
        return root instanceof AssertionError && ExceptionReads.topFrameIn(root, ExceptionReads.MVEL_PACKAGE, true);
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
                && description.equals(String.valueOf(Throwables.messageOf(root))) && thrownInMvel(root);
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
     * and its message if it has one, as {@code core.Failures} names one. Each is escaped as the engine escapes its
     * messages, so the issue's description is one line too, and shortened so the note fits in the room (see
     * {@link MessageText#escapeWithin}), so the note is never cut off: the class name as a name, within what the rest
     * of the note leaves with the message's count alone, and the message within what the class name leaves.
     *
     * @param chain What MVEL threw and its causes
     * @param room  The most characters the note may take
     * @return {@code " (caused by ...)"}, or an empty string if there is no cause
     */
    private static String causeNote(List<Throwable> chain, int room) {
        if (chain.subList(1, chain.size()).isEmpty()) {
            return "";
        }
        Throwable root = chain.get(chain.size() - 1);
        String rootMessage = Throwables.messageOf(root);
        String className = root.getClass().getName();
        if (rootMessage == null) {
            return " (caused by " + MessageText.quoteWithin(className, room - " (caused by )".length()) + ")";
        }
        // The class name leaves room for the message, or for its count alone if that's shorter. Escaped, a message is
        // never shorter than it is raw, so only one shorter raw than its count is escaped to measure it.
        int count = MessageText.leftOut(rootMessage.length()).length();
        int least = ": ".length() + (rootMessage.length() < count
                ? Math.min(MessageText.escape(rootMessage).length(), count) : count);
        String named = " (caused by " + MessageText.quoteWithin(className, room - " (caused by )".length() - least);
        return named + ": " + MessageText.escapeWithin(rootMessage, room - named.length() - ": )".length()) + ")";
    }
}
