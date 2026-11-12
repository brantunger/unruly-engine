package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.ExpressionKind;
import io.github.brantunger.unruly.api.language.InvalidExpressionException;
import io.github.brantunger.unruly.api.language.UnrulyException;

import java.util.List;

/**
 * An application's own subclasses of the engine's exceptions, as a language or a {@link RulesEngine} test double
 * defines, for {@link SerializedExceptionsTest}. Each has the same name and {@code serialVersionUID} in the copy of
 * this file compiled against unruly-engine 2.35.4 that wrote the streams in
 * {@code src/test/resources/serialized-2.35.4}, as an application's class has before and after it moves to 3.0. Read
 * {@code generator/generate.sh} there, which makes that copy, before changing anything here.
 */
final class SerializedSubclasses {

    private SerializedSubclasses() {
    }

    /**
     * Returns the issue the compile failure and the rejection hold.
     *
     * @return An error at line 1, column 2
     */
    static InvalidExpressionException.Issue issue() {
        return new InvalidExpressionException.Issue(InvalidExpressionException.Issue.Severity.ERROR, 1, 2, "bad");
    }

    /** A subclass of the base exception, which holds nothing of its own. */
    static final class Base extends UnrulyException {
        private static final long serialVersionUID = 1L;

        Base() {
            super("my exception");
        }
    }

    /** A subclass of the exception {@code load()} throws, with a rule name, a kind and an issue. */
    static final class CompileFailure extends RuleCompilationException {
        private static final long serialVersionUID = 1L;

        CompileFailure() {
            super("my compile failure", null, "r7", ExpressionKind.ACTION, List.of(issue()));
        }
    }

    /** A subclass of the exception {@code run()} throws, with a rule name and a kind. */
    static final class RunFailure extends RuleExecutionException {
        private static final long serialVersionUID = 1L;

        RunFailure() {
            super("my run failure", null, "r8", ExpressionKind.CONDITION);
        }
    }

    /** A subclass of the exception a language rejects an expression with, with an issue. */
    static final class Rejection extends InvalidExpressionException {
        private static final long serialVersionUID = 1L;

        Rejection() {
            super("my rejection", List.of(issue()));
        }
    }
}
