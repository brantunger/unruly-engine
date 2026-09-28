package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionWatch sees the sessions a language returns, and fails a check that one of them breaks")
class SessionWatchTest {

    /** A session whose close() throws, as the watch must report. */
    private static final class FailingSession implements Session {

        private final IllegalStateException failure = new IllegalStateException("can't close");

        @Override
        public void close() {
            throw failure;
        }

        @Override
        public String toString() {
            return "the failing session";
        }
    }

    /**
     * A language whose compiler returns {@code session}, and whose conditions record the session they're evaluated
     * with and return {@code true}.
     */
    private static ExpressionLanguage language(Session session, List<Session> evaluatedWith) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "sessions";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, used) -> {
                            evaluatedWith.add(used);
                            return true;
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (actionContext, used) -> null;
                    }

                    @Override
                    public Session newSession() {
                        return session;
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("a session whose close() throws fails the check, with what it threw")
    void sessionThrewOnClose() {
        SessionWatch watch = new SessionWatch();
        FailingSession session = new FailingSession();
        Session watched = watch.watching(language(session, List.of())).newCompiler(LanguageTestContexts.compile())
                .newSession();
        watch.assertNoneThrewOnClose();

        assertSame(session.failure, assertThrows(IllegalStateException.class, watched::close));

        assertEquals(1, watch.closed());
        AssertionFailedError e = assertThrows(AssertionFailedError.class, watch::assertNoneThrewOnClose);
        assertEquals("a session's close() threw java.lang.IllegalStateException: can't close, which the engine only"
                + " logs at WARN", e.getMessage());
    }

    @Test
    @DisplayName("a condition's detail that is a session the language returned fails the check")
    void detailIsASession() {
        SessionWatch watch = new SessionWatch();
        FailingSession session = new FailingSession();
        watch.watching(language(session, List.of())).newCompiler(LanguageTestContexts.compile()).newSession();

        watch.assertNotASession(new FailingSession());
        watch.assertNotASession(null);
        AssertionFailedError e = assertThrows(AssertionFailedError.class, () -> watch.assertNotASession(session));
        assertEquals("the condition's detail is the session it ran with, which the engine gives to another run or"
                + " closes: the failing session", e.getMessage());
    }

    @Test
    @DisplayName("a watched condition's evaluate hands the language its own session, and returns what it returned")
    void evaluateUnwraps() throws Exception {
        SessionWatch watch = new SessionWatch();
        FailingSession session = new FailingSession();
        List<Session> evaluatedWith = new CopyOnWriteArrayList<>();
        ExpressionCompiler compiler = watch.watching(language(session, evaluatedWith))
                .newCompiler(LanguageTestContexts.compile());
        Session watched = compiler.newSession();
        assertNotSame(session, watched);
        CompiledCondition condition = compiler.compileCondition(new Expression("r", ExpressionKind.CONDITION, "c"));

        assertEquals(true, condition.evaluate(LanguageTestContexts.evaluation(Map.of()), watched));
        assertEquals(List.of(session), evaluatedWith);
    }
}
