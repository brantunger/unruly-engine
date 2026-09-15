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
}
