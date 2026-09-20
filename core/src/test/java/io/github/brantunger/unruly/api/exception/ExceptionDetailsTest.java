package io.github.brantunger.unruly.api.exception;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.language.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the exceptions' expression kinds, issues and failures")
class ExceptionDetailsTest {

    private static final Issue ISSUE = new Issue(Severity.ERROR, 1, 2, "bad");

    @Test
    @DisplayName("an issue can't have a negative line or column, or a null severity or message; 0 means unknown")
    void issueValidated() {
        assertEquals(0, new Issue(Severity.WARNING, 0, 0, "unknown position").line());
        assertThrows(IllegalArgumentException.class, () -> new Issue(Severity.ERROR, -1, 0, "m"));
        assertThrows(IllegalArgumentException.class, () -> new Issue(Severity.ERROR, 0, -1, "m"));
        assertThrows(NullPointerException.class, () -> new Issue(null, 0, 0, "m"));
        assertThrows(NullPointerException.class, () -> new Issue(Severity.ERROR, 0, 0, null));
    }

    @Test
    @DisplayName("an invalid expression keeps a copy of its issues, and its cause")
    void invalidExpressionIssues() {
        List<Issue> issues = new ArrayList<>(List.of(ISSUE));
        IllegalStateException cause = new IllegalStateException("parser");

        InvalidExpressionException ex = new InvalidExpressionException("broken", issues, cause);
        issues.clear();

        assertEquals(List.of(ISSUE), ex.issues());
        assertSame(cause, ex.getCause());
        assertThrows(UnsupportedOperationException.class, () -> ex.issues().add(ISSUE));
        assertEquals(List.of(), new InvalidExpressionException("no issues").issues());
        assertEquals(List.of(ISSUE), new InvalidExpressionException("issues", List.of(ISSUE)).issues());
    }

    @Test
    @DisplayName("a compilation failure made without a kind has none, no issues, and is its own only failure")
    void compilationFailureDefaults() {
        RuleCompilationException ex = new RuleCompilationException("m", null, "r");

        assertNull(ex.getExpressionKind());
        assertEquals(List.of(), ex.issues());
        assertEquals(List.of(ex), ex.failures());
        assertNull(new RuleCompilationException("m").getRuleName());
        assertNull(new RuleCompilationException("m", new IllegalStateException()).getExpressionKind());
    }

    @Test
    @DisplayName("a failure of several rules takes the first's name, kind, issues and cause")
    void severalFailures() {
        RuleCompilationException first = new RuleCompilationException("first", null, "a", ExpressionKind.ACTION,
                List.of(ISSUE));
        RuleCompilationException second = new RuleCompilationException("second", null, "b", ExpressionKind.CONDITION,
                List.of());
        List<RuleCompilationException> failures = new ArrayList<>(List.of(first, second));

        RuleCompilationException ex = new RuleCompilationException("2 rules failed", failures);
        failures.clear();

        assertEquals(List.of(first, second), ex.failures());
        assertEquals("a", ex.getRuleName());
        assertEquals(ExpressionKind.ACTION, ex.getExpressionKind());
        assertEquals(List.of(ISSUE), ex.issues());
        assertSame(first, ex.getCause());
        List<RuleCompilationException> none = List.of();
        assertThrows(IllegalArgumentException.class, () -> new RuleCompilationException("none", none));
    }

    @Test
    @DisplayName("an execution failure has an expression kind only when it's given one")
    void executionFailureKind() {
        assertEquals(ExpressionKind.CONDITION,
                new RuleExecutionException("m", null, "r", ExpressionKind.CONDITION).getExpressionKind());
        assertNull(new RuleExecutionException("m", null, "r").getExpressionKind());
    }

    @Test
    @DisplayName("an expression needs a rule name, a kind and text")
    void expressionValidated() {
        assertThrows(NullPointerException.class, () -> new Expression(null, ExpressionKind.ACTION, "a"));
        assertThrows(NullPointerException.class, () -> new Expression("r", null, "a"));
        assertThrows(NullPointerException.class, () -> new Expression("r", ExpressionKind.ACTION, null));
    }
}
