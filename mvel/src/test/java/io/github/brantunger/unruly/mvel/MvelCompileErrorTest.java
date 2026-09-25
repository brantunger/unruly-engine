package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.CompileException;
import org.mvel2.ErrorDetail;

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

    private static ErrorDetail error(int line, int column, String message) {
        ErrorDetail error = new ErrorDetail(new char[0], 0, true, message);
        error.setLineNumber(line);
        error.setColumn(column);
        return error;
    }

    private static CompileException listing(ErrorDetail... errors) {
        return new CompileException("Failed to compileShared: " + errors.length + " compilation error(s): ",
                List.of(errors), "new Nosuch()".toCharArray(), 4, null);
    }

    @Test
    @DisplayName("one error MVEL lists is its description alone, a line break in it escaped")
    void oneListedError() {
        InvalidExpressionException ex = MvelExpressionCompiler.compileError(
                listing(error(1, 5, "could not resolve class: Nosuch\n - (9,9) not an error")));

        String description = "could not resolve class: Nosuch\\n - (9,9) not an error";
        assertEquals("failed to compile at line 1, column 5: " + description, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 5, description)), ex.issues());
    }

    @Test
    @DisplayName("several errors MVEL lists are each with their line and column, on one line")
    void severalListedErrors() {
        InvalidExpressionException ex = MvelExpressionCompiler.compileError(
                listing(error(1, 5, "a\r\nb"), error(2, 3, "c" + (char) 0x85 + "d" + (char) 0x2028 + "e"
                        + (char) 0x2029 + "f\tg")));

        String description = "(1,5) a\\r\\nb; (2,3) c\\u0085d\\u2028e\\u2029f\\tg";
        assertEquals("failed to compile at line 1, column 5: " + description, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 5, description)), ex.issues());
    }

    /** An assert's error whose stack trace can't be read, or is {@code null}. */
    private static final class UnreadableStackTrace extends AssertionError {
        private static final long serialVersionUID = 1L;
        private final boolean throwing;

        UnreadableStackTrace(boolean throwing) {
            this.throwing = throwing;
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            if (throwing) {
                throw new IllegalStateException("stack trace accessor broke");
            }
            return null;
        }
    }

    @ParameterizedTest(name = "an assert with an unreadable stack trace (getStackTrace() throws: {0}) is named")
    @ValueSource(booleans = {true, false})
    void failedAssertWithUnreadableStackTrace(boolean throwing) {
        CompileException mvel = withMessage("[Error: null]");
        mvel.initCause(new UnreadableStackTrace(throwing));

        assertEquals("failed to compile: null (caused by " + UnreadableStackTrace.class.getName() + ")",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("an assert that failed inside MVEL reads as a badly formed structure")
    void failedAssertInsideMvel() {
        AssertionError assertion = new AssertionError();
        assertion.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.mvel2.ast.OperatorNode", "<init>", "OperatorNode.java", 32)});
        CompileException mvel = withMessage("[Error: null]\n[Line: 1, Column: 5]");
        mvel.initCause(assertion);

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        assertEquals("failed to compile at line 1, column 5: not a statement, or badly formed structure",
                ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 5, "not a statement, or badly formed structure")),
                ex.issues());
    }

    @Test
    @DisplayName("an assert that failed outside MVEL is named as the root cause")
    void failedAssertOutsideMvel() {
        CompileException mvel = withMessage("[Error: null]");
        mvel.initCause(new AssertionError());

        assertEquals("failed to compile: null (caused by java.lang.AssertionError)",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("an assert that failed in the JDK's code MVEL called is named as the root cause")
    void failedAssertInJdkCalledByMvel() {
        AssertionError assertion = new AssertionError();
        assertion.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.util.ArrayList", "add", "ArrayList.java", 1),
                new StackTraceElement("org.mvel2.ast.OperatorNode", "<init>", "OperatorNode.java", 32)});
        CompileException mvel = withMessage("[Error: null]");
        mvel.initCause(assertion);

        assertEquals("failed to compile: null (caused by java.lang.AssertionError)",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("MVEL's description over a line separator is read to the ] that ends MVEL's line, and escaped")
    void descriptionWithLineSeparator() {
        String description = "class not found: X" + (char) 0x2028 + "Y" + (char) 0x2029 + "Z" + (char) 0x85 + "W";
        CompileException mvel = withMessage("[Error: " + description + "]\n[Near : {... X ....}]\n     ^\n"
                + "[Line: 1, Column: 8]");

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        String escaped = "class not found: X\\u2028Y\\u2029Z\\u0085W";
        assertEquals("failed to compile at line 1, column 8: " + escaped, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, escaped)), ex.issues());
    }

    @Test
    @DisplayName("a message without MVEL's markers is escaped too")
    void plainMessageEscaped() {
        InvalidExpressionException ex = MvelExpressionCompiler.compileError(withMessage("first\nsecond"));

        assertEquals("failed to compile: first\\nsecond", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "first\\nsecond")), ex.issues());
    }

    @Test
    @DisplayName("an assert with no stack trace is named as the root cause")
    void failedAssertWithoutStackTrace() {
        AssertionError assertion = new AssertionError();
        assertion.setStackTrace(new StackTraceElement[0]);
        CompileException mvel = withMessage("[Error: null]");
        mvel.initCause(assertion);

        assertEquals("failed to compile: null (caused by java.lang.AssertionError)",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    private static IndexOutOfBoundsException outOfBounds(String... frameClasses) {
        IndexOutOfBoundsException e = new IndexOutOfBoundsException("Index 2 out of bounds for length 2");
        StackTraceElement[] frames = new StackTraceElement[frameClasses.length];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement(frameClasses[i], "m", null, -1);
        }
        e.setStackTrace(frames);
        return e;
    }

    @Test
    @DisplayName("an IndexOutOfBoundsException MVEL's code threw is MVEL's")
    void outOfBoundsFromMvel() {
        assertTrue(MvelExpressionCompiler.thrownInMvel(outOfBounds("org.mvel2.optimizers.AbstractOptimizer")));
    }

    @Test
    @DisplayName("one the JDK's bounds check threw for MVEL is MVEL's: the JDK's frames are skipped")
    void outOfBoundsFromTheJdkForMvel() {
        assertTrue(MvelExpressionCompiler.thrownInMvel(outOfBounds("jdk.internal.util.Preconditions$1",
                "java.lang.String", "sun.x.Y", "com.sun.x.Y", "org.mvel2.compiler.AbstractParser")));
    }

    @Test
    @DisplayName("one the application's code threw, even through the JDK, isn't MVEL's")
    void outOfBoundsFromOtherCode() {
        assertFalse(MvelExpressionCompiler.thrownInMvel(outOfBounds("java.lang.ClassLoader", "com.example.Loader",
                "org.mvel2.util.ParseTools")));
    }

    @Test
    @DisplayName("one without a stack trace, as HotSpot throws a frequent one, came out of MVEL all the same")
    void outOfBoundsWithoutStackTrace() {
        assertTrue(MvelExpressionCompiler.thrownInMvel(outOfBounds()));
    }

    @Test
    @DisplayName("one with only the JDK's frames came out of MVEL all the same")
    void outOfBoundsWithOnlyJdkFrames() {
        assertTrue(MvelExpressionCompiler.thrownInMvel(outOfBounds("java.lang.String")));
    }

    @Test
    @DisplayName("one whose stack trace can't be read came out of MVEL all the same")
    void outOfBoundsWithUnreadableStackTrace() {
        IndexOutOfBoundsException unreadable = new IndexOutOfBoundsException() {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("stack trace accessor broke");
            }
        };

        assertTrue(MvelExpressionCompiler.thrownInMvel(unreadable));
    }
}
