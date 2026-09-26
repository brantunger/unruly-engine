package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.CompileException;
import org.mvel2.ParserContext;
import org.mvel2.ast.ASTNode;
import org.mvel2.ast.LiteralNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #644: the type of a declaration MVEL couldn't resolve is read from the node MVEL rejected it at, only when that node
 * is the declaration's type, and named after MVEL's words.
 */
@DisplayName("the type of a declaration MVEL rejects is named from MVEL's own node")
class RejectedTypeTest {

    private static final Imports IMPORTS = new Imports(Set.of(), Set.of(), RejectedTypeTest.class.getClassLoader());

    private static CompileException withMessage(String message) {
        return new CompileException("unused", new char[0], 0) {
            @Override
            public String getMessage() {
                return message;
            }
        };
    }

    /** A node MVEL would make for the text from {@code start} to {@code end}. */
    private static ASTNode node(char[] expr, int start, int end) {
        return new ASTNode(expr, start, end - start, 0, new ParserContext());
    }

    @ParameterizedTest(name = "in {0}, the node ''{1}'' up to {2}, MVEL stopping at {3}, names ''{4}''")
    @CsvSource(delimiter = '|', value = {
            "Zzz z = 1         | 0 | 3  | 6  | Zzz",
            "Zzz z             | 0 | 3  | 5  | Zzz",
            "Zzz\tz=1          | 0 | 3  | 6  | Zzz",
            "a.b.Zzz[] z = 1   | 0 | 9  | 12 | a.b.Zzz[]",
            "Zzz z = 1         | 0 | 3  | 8  | ''",
            "Zzz z = 1         | 0 | 3  | 3  | ''",
            "Zzz z = 1         | 0 | 3  | 2  | ''",
            "Zzz 1 = 1         | 0 | 3  | 6  | ''",
            "foo() z = 1       | 0 | 5  | 8  | ''",
            "foo .Zzz z = 1    | 0 | 8  | 11 | ''",
            "Zzz z = 1         | 0 | 3  | 99 | ''",
    })
    void typeNamedOnlyWhenMvelStoppedAfterIt(String text, int start, int end, int cursor, String expected) {
        char[] expr = text.toCharArray();

        String named = MvelExpression.Analysis.typeNamed(node(expr, start, end), expr, cursor);

        assertEquals(expected.isEmpty() ? null : expected, named);
    }

    @ParameterizedTest(name = "the node ''{0}'' holds a word Java or MVEL reserves, so it names nothing")
    @ValueSource(strings = {"this", "in", "class", "public", "empty", "nil", "record", "a.class", "int.Zzz", "Zzz.in",
            "this.Zzz[]"})
    void reservedWordNamesNothing(String token) {
        char[] expr = (token + " z = 1").toCharArray();

        assertNull(MvelExpression.Analysis.typeNamed(node(expr, 0, token.length()), expr, token.length() + 4));
    }

    @ParameterizedTest(name = "the node ''{0}'' holds no reserved word, though a class MVEL knows, so it names itself")
    @ValueSource(strings = {"String.Zzz", "Math.Entry", "Records", "thisZzz", "Zzz.inner"})
    void wordsMvelKnowsAsClassesAreNotReserved(String token) {
        char[] expr = (token + " z = 1").toCharArray();

        assertEquals(token, MvelExpression.Analysis.typeNamed(node(expr, 0, token.length()), expr, token.length() + 4));
    }

    @ParameterizedTest(name = "the node ''{0}'' has a word only a type''s own name can''t be in a package, so it names "
            + "itself")
    @ValueSource(strings = {"java.util.function.Functon", "com.acme.record.Msg", "com.acme.in.Msg", "com.acme.var.Msg",
            "com.acme.def.Msg", "com.acme.is.Msg", "com.acme.with.Msg", "com.acme.empty.Msg", "nil.Zzz[]"})
    void wordsOnlyATypeCantBeNameAPackage(String token) {
        char[] expr = (token + " z = 1").toCharArray();

        assertEquals(token, MvelExpression.Analysis.typeNamed(node(expr, 0, token.length()), expr, token.length() + 4));
    }

    @Test
    @DisplayName("a no-break space counts as whitespace after the type")
    void noBreakSpaceAfterTheType() {
        char[] expr = ("Zzz" + (char) 0xa0 + "z = 1").toCharArray();

        assertEquals("Zzz", MvelExpression.Analysis.typeNamed(node(expr, 0, 3), expr, 6));
    }

    @Test
    @DisplayName("no node names nothing")
    void noNode() {
        assertNull(MvelExpression.Analysis.typeNamed(null, "Zzz z = 1".toCharArray(), 6));
    }

    @Test
    @DisplayName("a node of another kind, such as the literal that ends the statement before, names nothing")
    void nodeOfAnotherKind() {
        char[] expr = "Zzz z = 1".toCharArray();
        ASTNode literal = new LiteralNode(5, new ParserContext());

        assertNull(MvelExpression.Analysis.typeNamed(literal, expr, 6));
    }

    @Test
    @DisplayName("an analysis that passes rejects no type")
    void passingAnalysisRejectsNoType() {
        MvelExpression.Analysis analysis = new MvelExpression.Analysis("x = 1", IMPORTS);

        analysis.compile();

        assertNull(analysis.rejectedType());
    }

    @Test
    @DisplayName("an analysis that rejects a declaration of an unknown type reads the type")
    void rejectingAnalysisReadsTheType() {
        MvelExpression.Analysis analysis = new MvelExpression.Analysis("java.math.BigDecimall total = 0", IMPORTS);

        assertThrows(CompileException.class, analysis::compile);
        assertEquals("java.math.BigDecimall", analysis.rejectedType());
    }

    @Test
    @DisplayName("the type is named after MVEL's words, escaped as the engine escapes its messages")
    void typeNamedAndEscaped() {
        CompileException mvel = withMessage("[Error: unknown class or illegal statement: "
                + ParserContext.class.getName() + "@1a2b]\n[Near : {... A B = 1 ....}]\n[Line: 1, Column: 6]");

        assertEquals(List.of(new Issue(Severity.ERROR, 1, 6, "unknown class or illegal statement: A\\u202eB")),
                MvelExpressionCompiler.compileError(mvel, "A" + (char) 0x202e + "B").issues());
    }

    @Test
    @DisplayName("a long type's name is shortened with MVEL's words")
    void longTypeShortened() {
        CompileException mvel = withMessage("[Error: unknown class or illegal statement: 5]");

        String description = MvelExpressionCompiler.compileError(mvel, "Z".repeat(1_200)).issues().get(0).message();

        assertEquals("unknown class or illegal statement: " + "Z".repeat(964) + "... (236 more characters)",
                description);
    }

    @Test
    @DisplayName("an array type MVEL names isn't named twice")
    void arrayTypeNotNamedTwice() {
        CompileException mvel = withMessage("[Error: unknown class or illegal statement: Foo[]]");

        assertEquals("failed to compile: unknown class or illegal statement: Foo[]",
                MvelExpressionCompiler.compileError(mvel, "Foo[]").getMessage());
    }

    @Test
    @DisplayName("another description names no type")
    void otherDescriptionNamesNoType() {
        CompileException mvel = withMessage("[Error: unexpected token: Zzz]");

        assertEquals("failed to compile: unexpected token: Zzz",
                MvelExpressionCompiler.compileError(mvel, "Zzz").getMessage());
    }
}
