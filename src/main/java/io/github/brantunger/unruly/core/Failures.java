package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * How the engine treats what rules, listeners and expression languages throw: which errors must reach the caller
 * unchanged, and how an exception is described in an error message. Nothing here logs, so every failure is still
 * logged under {@link AbstractRulesEngine}'s logger name.
 */
final class Failures {

    /** How much of an exception's message an error message includes; see {@link #describe}. */
    static final int MAX_DESCRIPTION_LENGTH = 1_000;

    /** How much of a fact, rule or language name a message includes; see {@link #quote}. */
    static final int MAX_NAME_LENGTH = 200;

    private Failures() {
    }

    /**
     * Tells whether {@code thrown} is an {@link Error} the engine must not absorb. A {@link StackOverflowError}
     * (runaway recursion) or {@link AssertionError} ({@code assert} in a rule or listener) comes from the code
     * being run and is handled like an exception. Any other error, such as {@link OutOfMemoryError}, is left
     * for the caller to see unchanged.
     *
     * @param thrown One throwable from a cause chain
     * @return {@code true} if {@code thrown} must be rethrown unchanged
     */
    private static boolean isFatal(Throwable thrown) {
        return thrown instanceof Error && !(thrown instanceof StackOverflowError || thrown instanceof AssertionError);
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

    static void throwIfPresent(Error fatal) {
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Describes an exception for an error message:
     * <ul>
     *     <li>its message, or its class name if it has none (NPEs and bare RuntimeExceptions carry no message)</li>
     *     <li>the class of its root cause when that has no message either, since MVEL copies a cause's missing message
     *     into its own as {@code ": null"}</li>
     *     <li>at most {@value #MAX_DESCRIPTION_LENGTH} characters of the message: MVEL pads its messages with spaces up
     *     to the error's column, so a long expression produced messages hundreds of thousands of characters long</li>
     * </ul>
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
        String text = truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        List<Throwable> chain = causeChain(e);
        Throwable root = chain.get(chain.size() - 1);
        return chain.size() > 1 && root.getMessage() == null
                ? text + " (caused by " + root.getClass().getName() + ")"
                : text;
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
     * logged it and told its listeners.
     *
     * @param e What was caught, or {@code null}
     * @return The innermost {@link RuleExecutionException} in {@code e}'s cause chain, or {@code null}
     */
    static RuleExecutionException nestedRunFailure(Throwable e) {
        RuleExecutionException innermost = null;
        for (Throwable t : causeChain(e)) {
            if (t instanceof RuleExecutionException failure) {
                innermost = failure;
            }
        }
        return innermost;
    }

    /**
     * Makes a fact, rule or language name safe to put in a message the engine logs. Line breaks, tabs and other
     * control characters, including the Unicode line and paragraph separators, are escaped ({@code \n}, {@code \r},
     * {@code \t}, or a backslash, {@code u} and four hex digits), so a name from request data can't start a forged
     * log line. A name longer than {@value #MAX_NAME_LENGTH} characters is shortened. {@code mvel.FactNames} and
     * {@code api.LoggingRuleListener} keep a copy, because those packages can't use this one.
     *
     * @param name The name
     * @return The name, escaped and shortened if it was longer
     */
    static String quote(String name) {
        int shown = Math.min(name.length(), MAX_NAME_LENGTH);
        StringBuilder quoted = new StringBuilder(shown);
        for (int i = 0; i < shown; i++) {
            char c = name.charAt(i);
            switch (c) {
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    int type = Character.getType(c);
                    if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        if (name.length() > MAX_NAME_LENGTH) {
            quoted.append("... (").append(name.length() - MAX_NAME_LENGTH).append(" more characters)");
        }
        return quoted.toString();
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
}
