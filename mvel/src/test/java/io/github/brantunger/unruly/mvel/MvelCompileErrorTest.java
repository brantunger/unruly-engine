package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.CompileException;
import org.mvel2.ErrorDetail;
import org.mvel2.ParserContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // #661: without the dot after MVEL's package, a package whose name starts the same counted as MVEL's.
    @Test
    @DisplayName("one from a package whose name only starts as MVEL's does isn't MVEL's")
    void outOfBoundsFromALookAlikePackage() {
        assertFalse(MvelExpressionCompiler.thrownInMvel(outOfBounds("org.mvel2extra.Parser")));
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

    @Test
    @DisplayName("a declaration's unknown first token is MVEL's words alone, in place of MVEL's parser context")
    void unknownClassWithoutTheParserContext() {
        CompileException mvel = withMessage("[Error: unknown class or illegal statement: "
                + ParserContext.class.getName() + "@1a2b3c]\n[Near : {... ....}]\n[Line: 1, Column: 6]");

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        assertEquals("failed to compile at line 1, column 6: unknown class or illegal statement", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 6, "unknown class or illegal statement")), ex.issues());
    }

    // #651: a description naming something else MVEL's parser context was left as it is, and a literal MVEL named,
    // such as 5, was named as the unknown class.
    @ParameterizedTest(name = "what MVEL names after an unknown class, {0}, isn''t kept unless it''s an array type")
    @ValueSource(strings = {"org.mvel2.ParserContext@", "5", "true", "null", "1.5", "Foo []", "Zzz[", "[]", "Zzz[]x"})
    void parserContextWithoutItsHash(String named) {
        CompileException mvel = withMessage("[Error: unknown class or illegal statement: " + named + "]");

        assertEquals("failed to compile: unknown class or illegal statement",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @ParameterizedTest(name = "an array type MVEL names after an unknown class, {0}, is kept")
    @ValueSource(strings = {"Zzz[]", "Zzz[][]", "java.util.Zzz[]", "Outer$Zzz[]", "_1[]"})
    void unknownArrayTypeKept(String named) {
        CompileException mvel = withMessage("[Error: unknown class or illegal statement: " + named + "]");

        assertEquals("failed to compile: unknown class or illegal statement: " + named,
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("the description ends at the last ] MVEL's excerpt follows, so a line break in it is kept")
    void descriptionEndsAtTheLastExcerpt() {
        String description = "class not found: import a.B;\n[Error: x]\n]\n[Near : {... y ....}]';\nz = 1";
        CompileException mvel = withMessage("[Error: " + description + "]\n[Near : {... import a.B; ....}]\n"
                + "     ^\n[Line: 1, Column: 8]");

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        String escaped = "class not found: import a.B;\\n[Error: x]\\n]\\n[Near : {... y ....}]';\\nz = 1";
        assertEquals("failed to compile at line 1, column 8: " + escaped, ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 8, escaped)), ex.issues());
    }

    @Test
    @DisplayName("an empty description before MVEL's excerpt is empty")
    void emptyDescriptionBeforeTheExcerpt() {
        CompileException mvel = withMessage("[Error: ]\n[Near : {... x ....}]\n[Line: 1, Column: 2]");

        assertEquals(List.of(new Issue(Severity.ERROR, 1, 2, "")), MvelExpressionCompiler.compileError(mvel).issues());
    }

    @Test
    @DisplayName("a message whose excerpt has no [Error: before it is read line by line")
    void excerptWithoutTheStart() {
        CompileException mvel = withMessage("x]\n[Near : {... x ....}]\n[Error: y]\n[Line: 1, Column: 2]");

        assertEquals(List.of(new Issue(Severity.ERROR, 1, 2, "y")), MvelExpressionCompiler.compileError(mvel).issues());
    }

    // #661: an unanchored pattern read the description out of the middle of a line.
    @Test
    @DisplayName("a message without MVEL's excerpt or a line of its own that starts [Error: is used whole")
    void errorMarkerInsideALine() {
        assertEquals("failed to compile: x [Error: a] y",
                MvelExpressionCompiler.compileError(withMessage("x [Error: a] y")).getMessage());
    }

    // #651: the description was "null (caused by java.lang.ArrayIndexOutOfBoundsException)".
    @Test
    @DisplayName("an out-of-bounds read MVEL wraps, without a stack trace or message, is a malformed expression")
    void wrappedOutOfBoundsWithoutStackTrace() {
        CompileException mvel = withMessage("[Error: null]\n[Near : {... in ....}]\n[Line: 1, Column: 11]");
        ArrayIndexOutOfBoundsException fastThrown = new ArrayIndexOutOfBoundsException();
        fastThrown.setStackTrace(new StackTraceElement[0]);
        mvel.initCause(fastThrown);

        InvalidExpressionException ex = MvelExpressionCompiler.compileError(mvel);

        assertEquals("failed to compile at line 1, column 11: malformed expression", ex.getMessage());
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 11, "malformed expression")), ex.issues());
        assertSame(mvel, ex.getCause());
    }

    @Test
    @DisplayName("an out-of-bounds read from other code MVEL called, wrapped, keeps MVEL's description")
    void wrappedOutOfBoundsFromOtherCode() {
        CompileException mvel = withMessage("[Error: Index 2 out of bounds for length 2]\n[Near : {... x ....}]\n"
                + "[Line: 1, Column: 3]");
        mvel.initCause(outOfBounds("com.example.Loader", "org.mvel2.util.ParseTools"));

        assertEquals(List.of(new Issue(Severity.ERROR, 1, 3, "Index 2 out of bounds for length 2")),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    @Test
    @DisplayName("an out-of-bounds read MVEL wraps with a description of its own keeps MVEL's description")
    void wrappedOutOfBoundsWithMvelsDescription() {
        CompileException mvel = withMessage("[Error: unexpected end of statement]\n[Near : {... y = 1 ....}]\n"
                + "[Line: 1, Column: 13]");
        mvel.initCause(outOfBounds("org.mvel2.compiler.AbstractParser"));

        assertEquals(List.of(new Issue(Severity.ERROR, 1, 13, "unexpected end of statement")),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    // #652: MVEL's description was as long as the expression it quotes.
    @Test
    @DisplayName("MVEL's description is shortened to 1,000 characters before it's escaped")
    void longDescriptionShortened() {
        CompileException mvel = withMessage("[Error: " + "\n".repeat(1_500) + "]\n[Near : {... x ....}]\n"
                + "[Line: 1, Column: 1]");

        String description = "\\n".repeat(1_000) + "... (500 more characters)";
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1, description)),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    @Test
    @DisplayName("one error MVEL lists is shortened before it's escaped")
    void longListedErrorShortened() {
        InvalidExpressionException ex = MvelExpressionCompiler.compileError(
                listing(error(1, 5, "could not resolve class: " + "\n".repeat(1_000))));

        String description = "could not resolve class: " + "\\n".repeat(975) + "... (25 more characters)";
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 5, description)), ex.issues());
    }

    @Test
    @DisplayName("several errors MVEL lists are shortened together, on one line")
    void longListOfErrorsShortened() {
        ErrorDetail[] errors = new ErrorDetail[100];
        for (int i = 0; i < errors.length; i++) {
            errors[i] = error(1, i, "could not resolve class: N" + i);
        }

        String description = MvelExpressionCompiler.compileError(listing(errors)).issues().get(0).message();

        assertTrue(description.startsWith("(1,0) could not resolve class: N0; (1,1) could not resolve class: N1; "),
                description);
        assertTrue(description.endsWith("... (2678 more characters)"), description);
        assertEquals(1_026, description.length());
    }

    @Test
    @DisplayName("the root cause's message in the engine's note is shortened, and the note isn't cut off")
    void longRootCauseMessageShortened() {
        CompileException mvel = withMessage("[Error: null]\n[Line: 1, Column: 1]");
        mvel.initCause(new ExceptionInInitializerError(new IllegalStateException("L".repeat(5_000))));

        String description = "null (caused by java.lang.IllegalStateException: " + "L".repeat(1_000)
                + "... (4000 more characters))";
        assertEquals(List.of(new Issue(Severity.ERROR, 1, 1, description)),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    // #661: without the escape, the issue carried a raw U+2028.
    @Test
    @DisplayName("MVEL's message for a declaration it rejects plainly is escaped")
    void plainRejectionEscaped() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        Rule rule = Rule.builder().ruleName("r").condition("true").action("int 1x" + (char) 0x2028 + "y = 2;").build();

        RuleCompilationException ex = engine.validate(List.of(rule)).get(0);

        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, "not an identifier: 1x\\u2028y = 2")), ex.issues());
    }

    // #652: MVEL's message for a declaration it rejects plainly was as long as the declaration.
    @Test
    @DisplayName("MVEL's message for a declaration it rejects plainly is shortened")
    void plainRejectionShortened() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build();
        Rule rule = Rule.builder().ruleName("r").condition("true").action("int 1" + "x".repeat(1_200) + " = 2")
                .build();

        RuleCompilationException ex = engine.validate(List.of(rule)).get(0);

        String description = "not an identifier: 1" + "x".repeat(980) + "... (220 more characters)";
        assertEquals(List.of(new Issue(Severity.ERROR, 0, 0, description)), ex.issues());
    }

    @Test
    @DisplayName("the position is MVEL's last line, not one quoted from the expression")
    void positionIsTheLastLine() {
        CompileException mvel = withMessage("[Error: not a statement]\n"
                + "[Near : {... x = '[Line: 9, Column: 9]' + ....}]\n[Line: 8, Column: 8]\n     ^\n"
                + "[Line: 1, Column: 28]");

        assertEquals(List.of(new Issue(Severity.ERROR, 1, 28, "not a statement")),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    @Test
    @DisplayName("a nested error's description is the innermost one's, at the outer position")
    void nestedErrorHasInnermostDescription() {
        CompileException inner = withMessage("[Error: was expecting type: java.lang.Boolean]\n[Line: 1, Column: 0]");
        CompileException outer = withMessage("[Error: [Error: was expecting type: java.lang.Boolean]\n"
                + "[Line: 1, Column: 0]]\n[Near : {... && ....}]\n[Line: 2, Column: 1]");
        outer.initCause(new IllegalStateException(inner));

        assertEquals(List.of(new Issue(Severity.ERROR, 2, 1, "was expecting type: java.lang.Boolean")),
                MvelExpressionCompiler.compileError(outer).issues());
    }

    private static NullPointerException nullPointer(String... frameClasses) {
        NullPointerException npe = new NullPointerException();
        StackTraceElement[] frames = new StackTraceElement[frameClasses.length];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement(frameClasses[i], "m", null, -1);
        }
        npe.setStackTrace(frames);
        return npe;
    }

    @ParameterizedTest(name = "a NullPointerException MVEL threw (description {0}) is a badly formed structure")
    @ValueSource(strings = {"Cannot invoke \"org.mvel2.ast.ASTNode.getEgressType()\" because \"node\" is null", "null"})
    void nullPointerInMvel(String description) {
        CompileException mvel = withMessage("[Error: " + description + "]\n[Line: 2, Column: 1]");
        mvel.initCause(nullPointer("org.mvel2.util.CompilerTools"));

        assertEquals(List.of(new Issue(Severity.ERROR, 2, 1, "not a statement, or badly formed structure")),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    @Test
    @DisplayName("a NullPointerException from other code MVEL called keeps its description")
    void nullPointerOutsideMvel() {
        CompileException mvel = withMessage("[Error: from the application]");
        mvel.initCause(nullPointer("com.example.Loader"));

        assertEquals("failed to compile: from the application", MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("a NullPointerException from other code, thrown through the JDK, keeps the generic report")
    void nullPointerFromOtherCodeThroughTheJdk() {
        CompileException mvel = withMessage("[Error: null]");
        mvel.initCause(nullPointer("java.util.Objects", "com.example.Loader", "org.mvel2.util.ParseTools"));

        assertEquals("failed to compile: null (caused by java.lang.NullPointerException)",
                MvelExpressionCompiler.compileError(mvel).getMessage());
    }

    @Test
    @DisplayName("one the JDK threw for MVEL is a badly formed structure: the JDK's frames are skipped")
    void nullPointerFromTheJdkForMvel() {
        CompileException mvel = withMessage("[Error: null]\n[Line: 2, Column: 1]");
        mvel.initCause(nullPointer("java.util.Objects", "org.mvel2.util.CompilerTools"));

        assertEquals(List.of(new Issue(Severity.ERROR, 2, 1, "not a statement, or badly formed structure")),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    @ParameterizedTest(name = "one without MVEL''s frames (only the JDK''s: {0}), as HotSpot throws a frequent one, "
            + "is a badly formed structure")
    @ValueSource(booleans = {false, true})
    void nullPointerWithoutMvelsFrames(boolean jdkFrames) {
        CompileException mvel = withMessage("[Error: null]\n[Line: 2, Column: 1]");
        mvel.initCause(jdkFrames ? nullPointer("java.util.Objects") : nullPointer());

        assertEquals(List.of(new Issue(Severity.ERROR, 2, 1, "not a statement, or badly formed structure")),
                MvelExpressionCompiler.compileError(mvel).issues());
    }

    private static RuntimeException thrownFrom(RuntimeException e, String frameClass) {
        e.setStackTrace(new StackTraceElement[] {new StackTraceElement(frameClass, "m", null, -1)});
        return e;
    }

    @Test
    @DisplayName("a plain RuntimeException MVEL threw with no cause rejects a declaration")
    void plainRuntimeExceptionFromMvel() {
        assertTrue(MvelExpressionCompiler.rejectedPlainly(thrownFrom(new RuntimeException("not an identifier: 1x"),
                "org.mvel2.util.ParseTools")));
    }

    @Test
    @DisplayName("one MVEL threw around another failure doesn't: that failure is reported as is")
    void plainRuntimeExceptionWithACause() {
        assertFalse(MvelExpressionCompiler.rejectedPlainly(thrownFrom(new RuntimeException("class not found: Widget",
                new IllegalStateException("from the application's class loader")), "org.mvel2.util.ParseTools")));
    }

    @Test
    @DisplayName("one from other code MVEL called doesn't")
    void plainRuntimeExceptionFromOtherCode() {
        assertFalse(MvelExpressionCompiler.rejectedPlainly(thrownFrom(new RuntimeException("from the loader"),
                "com.example.Loader")));
    }

    @Test
    @DisplayName("a subclass of RuntimeException MVEL threw doesn't")
    void runtimeExceptionSubclassFromMvel() {
        assertFalse(MvelExpressionCompiler.rejectedPlainly(thrownFrom(new IllegalStateException("bad state"),
                "org.mvel2.util.ParseTools")));
    }
}
