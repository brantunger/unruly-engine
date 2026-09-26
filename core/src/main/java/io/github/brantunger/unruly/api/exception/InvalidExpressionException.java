package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Thrown by an {@link io.github.brantunger.unruly.api.language.ExpressionCompiler} to reject an expression: one that
 * breaks a rule the engine enforces, such as a condition that assigns to a fact, or one with an error the language can
 * point to, such as a syntax error.
 *
 * <p>
 * The message says what is wrong without naming the rule, such as
 * {@code contains an assignment ('=' at line 1, column 16)}, and the {@link #issues() issues} say where, when the
 * language knows. {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} reports it as a
 * {@link RuleCompilationException} whose message names the expression and the rule first:
 * {@code Condition for rule 'prime-rate' contains an assignment ('=' at line 1, column 16)}. That exception has the
 * same issues, and this exception as its cause. Anything else an expression compiler throws is the cause too, but the
 * message then reads {@code Condition for rule 'prime-rate' failed to compile: } followed by the exception's message.
 * </p>
 */
public class InvalidExpressionException extends UnrulyException {
    private static final long serialVersionUID = 1L;

    /** Where and what the language found wrong, possibly nothing. */
    // The list is from List.copyOf, which serializes, though its declared type, List, isn't Serializable.
    @SuppressWarnings("serial")
    private final List<Issue> reportedIssues;

    /**
     * Constructs a new exception with the specified detail message and no issues.
     *
     * @param message What is wrong with the expression, without the rule's name
     */
    public InvalidExpressionException(@Nullable String message) {
        this(message, List.of());
    }

    /**
     * Constructs a new exception with the specified detail message and issues.
     *
     * @param message What is wrong with the expression, without the rule's name
     * @param issues  Where and what the language found wrong; copied
     * @throws NullPointerException if {@code issues} or one of its elements is {@code null}
     */
    public InvalidExpressionException(@Nullable String message, List<Issue> issues) {
        // No cause, so one can still be set with initCause.
        super(message);
        this.reportedIssues = checked(issues);
    }

    /**
     * Constructs a new exception with the specified detail message, issues and cause.
     *
     * @param message What is wrong with the expression, without the rule's name
     * @param issues  Where and what the language found wrong; copied
     * @param cause   What the language's own compiler threw, or {@code null}
     * @throws NullPointerException if {@code issues} or one of its elements is {@code null}
     */
    public InvalidExpressionException(@Nullable String message, List<Issue> issues, @Nullable Throwable cause) {
        super(message, cause);
        this.reportedIssues = checked(issues);
    }

    private static List<Issue> checked(List<Issue> issues) {
        Objects.requireNonNull(issues, "issues must not be null");
        for (Issue issue : issues) {
            Objects.requireNonNull(issue, "issues must not contain null");
        }
        return List.copyOf(issues);
    }

    /**
     * Returns where and what the language found wrong with the expression.
     *
     * @return The issues, possibly none; unmodifiable
     */
    public List<Issue> issues() {
        return reportedIssues;
    }

    /**
     * One problem a language found in an expression.
     *
     * @param severity Whether the problem stops the expression compiling
     * @param line     The line the problem is on, counting from 1, or 0 if it isn't known
     * @param column   The column the problem is at, counting from 1, or 0 if it isn't known
     * @param message  What the problem is
     */
    public record Issue(Severity severity, int line, int column, String message) implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Creates an issue.
         *
         * @throws NullPointerException     if {@code severity} or {@code message} is {@code null}
         * @throws IllegalArgumentException if {@code line} or {@code column} is negative
         */
        public Issue {
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(message, "message must not be null");
            if (line < 0 || column < 0) {
                throw new IllegalArgumentException(
                        "line and column must not be negative, but were " + line + " and " + column);
            }
        }

        /** Whether an issue stops an expression compiling. */
        public enum Severity {

            /** The expression can't be compiled. */
            ERROR,

            /** The expression compiles, but may not do what its author meant. */
            WARNING
        }
    }
}
