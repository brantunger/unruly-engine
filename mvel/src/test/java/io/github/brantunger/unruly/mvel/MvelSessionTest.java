package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.MVEL;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an MVEL session runs its own compiled copy of each expression")
class MvelSessionTest {

    private final MvelExpression expression = MvelExpression.compile("x + 1",
            new Imports(Set.of(), Set.of(), getClass().getClassLoader()));

    @Test
    @DisplayName("the MVEL compiler's sessions are MVEL sessions")
    void compilerCreatesMvelSessions() {
        Session session = new MvelExpressionLanguage().newCompiler(LanguageTestContexts.compile()).newSession();

        assertInstanceOf(MvelSession.class, session);
    }

    @Test
    @DisplayName("a session compiles an expression once, and another session gets a different compiled copy")
    void oneCompiledCopyPerSession() {
        MvelSession session = new MvelSession();
        Serializable compiled = session.compiled(expression);

        assertSame(compiled, session.compiled(expression));
        assertNotSame(compiled, new MvelSession().compiled(expression));
    }

    @Test
    @DisplayName("each new compiled copy runs: the first is the one compiled when the rules loaded")
    void newCompiledCopiesRun() {
        Serializable first = expression.newCompiled();
        Serializable second = expression.newCompiled();

        assertNotSame(first, second);
        assertEquals(2, MVEL.executeExpression(first, Map.of("x", 1)));
        assertEquals(3, MVEL.executeExpression(second, Map.of("x", 2)));
    }

    @Test
    @DisplayName("an expression evaluated with another language's session fails, naming the session")
    void foreignSessionRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> expression.evaluate(LanguageTestContexts.evaluation(Map.of("x", 1)), Session.none()));

        assertTrue(ex.getMessage().startsWith("An MVEL expression runs with a session its compiler created, not "),
                ex.getMessage());
    }
}
