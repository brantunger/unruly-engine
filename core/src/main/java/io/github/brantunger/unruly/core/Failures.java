package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * How the engine treats what rules, listeners and expression languages throw: which errors must reach the caller
 * unchanged, and how an exception is described in an error message. Nothing here logs, so every failure is still
 * logged under the engine's logger name, {@code io.github.brantunger.unruly.engine}.
 * <b>Internal:</b> this class may change in any release. It's public only so that {@code api.LoggingRuleListener}
 * can escape text the way the engine does, rather than keeping a copy of the escaping that could drift.
 */
public final class Failures {

    /** How much of an exception's message an error message includes; see {@link #describe}. */
    static final int MAX_DESCRIPTION_LENGTH = 1_000;

    /** How much of a fact, rule or language name a message includes; see {@link #quote}. */
    static final int MAX_NAME_LENGTH = 200;

    private Failures() {
    }

    /**
     * Tells whether {@code thrown} is an {@link Error} the engine must not absorb, because the JVM itself is failing
     * and no message about a rule would help: a {@link VirtualMachineError} other than {@link StackOverflowError},
     * such as {@link OutOfMemoryError}, {@link InternalError} or {@link UnknownError}.
     *
     * <p>
     * Every other error comes from the code being run, so it's handled like an exception and reported with the name of
     * the rule it came from:
     * </p>
     * <ul>
     *     <li>{@link StackOverflowError} (runaway recursion), which is itself a {@code VirtualMachineError} and so is
     *     excluded here on purpose;</li>
     *     <li>{@link AssertionError} ({@code assert} in a rule, a listener or a language);</li>
     *     <li>every {@link LinkageError} — {@link NoClassDefFoundError}, {@link IllegalAccessError},
     *     {@link IncompatibleClassChangeError}, {@link ExceptionInInitializerError}, {@link VerifyError} and the rest —
     *     which means a class a rule uses is missing or can't be read: a configuration problem in one rule, not a
     *     failing JVM;</li>
     *     <li>any other {@link Error}, such as {@link java.io.IOError} or
     *     {@link java.util.ServiceConfigurationError}.</li>
     * </ul>
     *
     * @param thrown One throwable from a cause chain
     * @return {@code true} if {@code thrown} must be rethrown unchanged
     */
    private static boolean isFatal(Throwable thrown) {
        // StackOverflowError is a VirtualMachineError, so excluding it needs its own check.
        return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError);
    }

    /**
     * Finds the fatal {@link Error} (see {@link #isFatal}) in what was caught: the throwable itself, or one of its
     * causes. An error from Java code a rule calls, such as a method, a getter or a lambda held in a fact, reaches the
     * engine inside MVEL's own exception, so checking only the outer exception let an {@link OutOfMemoryError} be
     * absorbed into a {@link RuleExecutionException}.
     *
     * @param thrown What was caught, or {@code null}
     * @return The first fatal error in {@code thrown}'s cause chain, or {@code null} if there is none
     */
    static Error fatalError(Throwable thrown) {
        for (Throwable t : causeChain(thrown)) {
            if (isFatal(t)) {
                return (Error) t;
            }
        }
        return null;
    }

    /**
     * Finds the {@link Error} of any kind in what was caught: the throwable itself, or one of its causes. This is
     * what a cancelled run asks before it reports a stop, because an error from Java code a rule calls, such as a
     * method, a getter or a lambda held in a fact, reaches the engine wrapped in the expression language's own
     * exception, and a run past its deadline or interrupted would otherwise report it as a stop that names no rule
     * rather than as that rule's failure.
     *
     * <p>
     * Unlike {@link #fatalError}, which looks for the first error the engine must not absorb (see {@link #isFatal})
     * and so may pass over a non-fatal one above it, this finds the first error in the chain whether it is fatal or
     * not.
     * </p>
     *
     * @param thrown What was caught, or {@code null}
     * @return The first error in {@code thrown}'s cause chain, or {@code null} if there is none
     */
    static Error errorInChain(Throwable thrown) {
        for (Throwable t : causeChain(thrown)) {
            if (t instanceof Error error) {
                return error;
            }
        }
        return null;
    }

    static void throwIfPresent(Error fatal) {
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Chooses which of two fatal errors, from two things closed one after the other, the caller throws: the first. The
     * other was logged when it was caught, as {@link Closing} logs every failure to close, and goes no further.
     *
     * @param first  The fatal error from what was closed first, or {@code null}
     * @param second The fatal error from what was closed after it, or {@code null}
     * @return {@code first} if there is one, else {@code second}, which may be {@code null}
     */
    static Error first(Error first, Error second) {
        return first != null ? first : second;
    }

    /**
     * Chooses what the caller throws when closing what a failure left behind threw a fatal {@link Error}: a fatal
     * error beats any other failure, and of two fatal errors the first wins. So {@code closeFatal} replaces
     * {@code failure} only when neither {@code failure} nor any of its causes is fatal (see {@link #fatalError}), and
     * then carries it as a suppressed exception. A fatal error that loses was logged when it was caught, and goes no
     * further.
     *
     * @param failure    What was being thrown when the closing began
     * @param closeFatal The fatal error closing threw, or {@code null} if it threw none
     * @return {@code closeFatal}, with {@code failure} added to its suppressed exceptions, if the caller throws it in
     *         place of {@code failure}; otherwise {@code null}, and the caller throws {@code failure}
     */
    static Error fatalInsteadOf(Throwable failure, Error closeFatal) {
        if (closeFatal == null || fatalError(failure) != null) {
            return null;
        }
        closeFatal.addSuppressed(failure);
        return closeFatal;
    }

    /**
     * Describes an exception for an error message:
     * <ul>
     *     <li>its message, or its class name if it has none (NPEs and bare RuntimeExceptions carry no message)</li>
     *     <li>the class of its root cause when that has no message either, and the root cause's class and message
     *     when an exception above it has none: MVEL copies a cause's missing message into its own as
     *     {@code ": null"}, which would hide what went wrong</li>
     *     <li>at most {@value #MAX_DESCRIPTION_LENGTH} characters of the message: MVEL pads its messages with spaces up
     *     to the error's column, so a long expression produced messages hundreds of thousands of characters long</li>
     *     <li>the message {@link #escape escaped}, because the engine didn't write it: a language quotes the fact
     *     values a failing expression read, and those come from request data far more often than names do</li>
     * </ul>
     * The message is shortened before it's escaped, so the count of what was left out counts the exception's own
     * characters. The exception is never changed: its {@code getMessage()} still reads as the language wrote it.
     * A failure of a {@code run()} started from a condition or action is described by that run's innermost failure
     * only, so a failure nested many runs deep isn't repeated once per level.
     *
     * @param e The exception to describe
     * @return A description of the exception for an error message
     */
    static String describe(Throwable e) {
        RuleExecutionException nested = nestedRunFailure(e);
        if (nested != null) {
            return "a nested run() failed: " + nested.getMessage();
        }
        String text = escape(truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        return text + causeNote(causeChain(e));
    }

    /**
     * Names the root cause when a description would otherwise hide it: its class when it has no message, and its class
     * and message when an exception above it has none and the first exception's message doesn't already include it.
     *
     * @param chain An exception and its causes
     * @return {@code " (caused by ...)"}, or an empty string if nothing is hidden
     */
    private static String causeNote(List<Throwable> chain) {
        if (chain.subList(1, chain.size()).isEmpty()) {
            return "";
        }
        Throwable root = chain.get(chain.size() - 1);
        if (root.getMessage() == null) {
            return " (caused by " + quote(root.getClass().getName()) + ")";
        }
        String first = chain.get(0).getMessage();
        boolean hidden = chain.stream().anyMatch(t -> t.getMessage() == null)
                && (first == null || !first.contains(root.getMessage()));
        return hidden
                ? " (caused by " + quote(root.getClass().getName()) + ": " + escape(truncate(root.getMessage())) + ")"
                : "";
    }

    /**
     * Describes an exception with its class, as {@link Throwable#toString()} does, for a message about code the engine
     * calls outside any rule, such as the output factory: escaped and shortened like {@link #describe}.
     *
     * @param e The exception to describe
     * @return Its class name, and its message if it has one
     */
    static String describeWithClass(Throwable e) {
        return escape(truncate(e.toString()));
    }

    /**
     * Shortens a message to at most {@value #MAX_DESCRIPTION_LENGTH} characters, saying how many were left out.
     *
     * @param text The message
     * @return The message, shortened if it was longer
     */
    static String truncate(String text) {
        if (text.length() <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_DESCRIPTION_LENGTH) + "... (" + (text.length() - MAX_DESCRIPTION_LENGTH)
                + " more characters)";
    }

    /**
     * Finds the innermost failure of a {@code run()} started by the code that threw {@code e}. That run already
     * logged it and told its listeners. Only an exception an engine threw counts ({@link ReportedFailure}, of any
     * engine): a {@link RuleExecutionException} a language or a rule throws itself was never logged.
     *
     * @param e What was caught, or {@code null}
     * @return The innermost {@link ReportedFailure} in {@code e}'s cause chain, or {@code null}
     */
    static RuleExecutionException nestedRunFailure(Throwable e) {
        return innermostReported(e);
    }

    /**
     * Tells whether a {@code run()} started by the code that threw {@code e} stopped for the same reason as the run
     * around it, and so already logged that stop.
     *
     * @param e        What was caught
     * @param deadline The deadline the run around it passed, or {@code null} if its thread was interrupted
     * @return {@code true} if the innermost {@link ReportedFailure} in {@code e}'s cause chain is that same stop
     */
    static boolean nestedRunStopped(Throwable e, Instant deadline) {
        ReportedFailure innermost = innermostReported(e);
        return innermost != null && innermost.isStopFor(deadline);
    }

    private static ReportedFailure innermostReported(Throwable e) {
        ReportedFailure innermost = null;
        for (Throwable t : causeChain(e)) {
            if (t instanceof ReportedFailure failure) {
                innermost = failure;
            }
        }
        return innermost;
    }

    /**
     * Makes a fact, rule or language name safe to put in a message the engine logs: {@link #escape escaped}, and
     * shortened to {@value #MAX_NAME_LENGTH} characters. {@code mvel.FactNames} keeps a copy of this, because the
     * {@code mvel} package may not use this one.
     *
     * @param name The name
     * @return The name, escaped and shortened if it was longer
     */
    public static String quote(String name) {
        if (name.length() <= MAX_NAME_LENGTH) {
            return escape(name);
        }
        return escape(name.substring(0, MAX_NAME_LENGTH)) + "... (" + (name.length() - MAX_NAME_LENGTH)
                + " more characters)";
    }

    /**
     * Makes text the engine didn't write safe to put in a message it logs, without shortening it. Line breaks, tabs
     * and other control characters, including the Unicode line and paragraph separators, are escaped ({@code \n},
     * {@code \r}, {@code \t}, or a backslash, {@code u} and four hex digits), so neither a name nor a fact value that
     * reached the message from request data can start a log line of its own.
     *
     * <p>
     * Escaping text that has already been escaped changes nothing, because a backslash isn't a control character, so
     * a caller that can't tell whether a message has been through here may escape it again.
     * </p>
     *
     * @param text The text
     * @return The text, with every character that could start a line escaped
     */
    public static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    int type = Character.getType(c);
                    if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Sets the current thread's interrupt status again when what was caught was caused by an interrupt. A rule,
     * listener or language interrupted while it blocks throws an {@link InterruptedException}, which clears the
     * status, and the engine receives it wrapped by MVEL or the language; without this, the caller of {@code run()}
     * couldn't tell the thread had been interrupted. A {@link java.io.InterruptedIOException} doesn't count: a
     * {@link java.net.SocketTimeoutException} is one.
     *
     * @param thrown What was caught, or {@code null}
     */
    static void keepInterruptStatus(Throwable thrown) {
        for (Throwable t : causeChain(thrown)) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static Throwable rootCause(Throwable e) {
        List<Throwable> chain = causeChain(e);
        return chain.get(chain.size() - 1);
    }

    /** Lists {@code e} and its causes, stopping if the chain loops back on itself. */
    private static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    /**
     * Names a rule's condition or action for a message, such as {@code Condition for rule 'prime-rate'}.
     *
     * @param kind     Whether it's the rule's condition or its action
     * @param ruleName The rule's name
     * @return The name
     */
    static String expression(ExpressionKind kind, String ruleName) {
        return (kind == ExpressionKind.CONDITION ? "Condition" : "Action") + " for rule '" + quote(ruleName) + "'";
    }

    /**
     * Describes where an issue is for a message, such as {@code  at line 2, column 5}.
     *
     * @param issue The issue
     * @return The position with a leading space, or an empty string if the issue's line isn't known
     */
    static String position(InvalidExpressionException.Issue issue) {
        if (issue.line() == 0) {
            return "";
        }
        return " at line " + issue.line() + (issue.column() == 0 ? "" : ", column " + issue.column());
    }
}
