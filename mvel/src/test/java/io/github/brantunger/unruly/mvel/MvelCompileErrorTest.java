package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.CompileException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MVEL's compile errors become issues with MVEL's description and position")
class MvelCompileErrorTest {

    private static CompileException withMessage(String message) {
        return new CompileException("unused", new char[0], 0) {
            @Override
            public String getMessage() {
                return message;
            }
        };
    }

    @Test
    @DisplayName("MVEL's description and position are read from its message")
    void descriptionAndPosition() {
        CompileException mvel = withMessage("[Error: unbalanced braces ( ... )]\n[Near : {... x ....}]\n"
                + "[Line: 3, Column: 7]");

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        assertEquals("failed to compile at line 3, column 7: unbalanced braces ( ... )", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 3, 7, "unbalanced braces ( ... )")), ex.issues());
        assertSame(mvel, ex.getCause());
    }

    @Test
    @DisplayName("a message without MVEL's markers is used as it is, with no position")
    void plainMessage() {
        InvalidExpressionException ex = MvelExpressionCompiler.compileError(withMessage("something else"));

        assertEquals("failed to compile: something else", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "something else")), ex.issues());
    }

    @Test
    @DisplayName("a missing description names the root cause, in the message and the issue")
    void missingDescriptionNamesRootCause() {
        CompileException mvel = withMessage("[Error: null]\n[Line: 1, Column: 1]");
        mvel.initCause(new ExceptionInInitializerError(new RuntimeException("plain init failure")));

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        assertEquals("failed to compile at line 1, column 1: null (caused by java.lang.RuntimeException: plain init "
                + "failure)", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1,
                "null (caused by java.lang.RuntimeException: plain init failure)")), ex.issues());
        assertSame(mvel, ex.getCause());
    }

    @Test
    @DisplayName("no message at all is a missing description too")
    void noMessageNamesRootCause() {
        CompileException mvel = withMessage(null);
        mvel.initCause(new IllegalStateException("bad state"));

        assertEquals("failed to compile: null (caused by java.lang.IllegalStateException: bad state)",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("a root cause without a message is named by its class")
    void rootCauseWithoutAMessage() {
        CompileException mvel = withMessage("[Error: null]");
        mvel.initCause(new ExceptionInInitializerError(new IllegalStateException()));

        assertEquals("failed to compile: null (caused by java.lang.IllegalStateException)",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("a root cause whose message can't be read is named with the note that it's unavailable")
    void rootCauseMessageUnreadable() {
        CompileException mvel = withMessage("[Error: null]");
        RuntimeException root = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("message accessor broke");
            }
        };
        mvel.initCause(root);

        assertEquals("failed to compile: null (caused by " + root.getClass().getName()
                        + ": (message unavailable: java.lang.IllegalStateException))",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("a missing description with no cause has no note")
    void missingDescriptionWithoutCause() {
        InvalidExpressionException ex = MvelExpressionCompiler.compileError(withMessage("[Error: null]"));

        assertEquals("failed to compile: null", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "null")), ex.issues());
    }

    @Test
    @DisplayName("a description MVEL gives has no note, even with a cause")
    void descriptionWithCause() {
        CompileException mvel = withMessage("[Error: could not access field]");
        mvel.initCause(new IllegalStateException("bad state"));

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        assertEquals("failed to compile: could not access field", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "could not access field")), ex.issues());
    }

    @Test
    @DisplayName("a message of MVEL's that can't be read is reported as unavailable, naming what reading it threw")
    void messageUnreadable() {
        CompileException mvel = new CompileException("unused", new char[0], 0) {
            @Override
            public String getMessage() {
                throw new IllegalStateException("message accessor broke");
            }
        };

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        assertEquals("failed to compile: (message unavailable: java.lang.IllegalStateException)", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "(message unavailable: java.lang.IllegalStateException)")),
                ex.issues());
    }
}
