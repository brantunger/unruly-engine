package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wrapper {@code unreservedOutputReadAsFact} builds its first engine with forwards every call to the language, and
 * records only a rejection of the name it watches (#1039).
 */
@DisplayName("the wrapper that watches a language's checkFactName forwards every call, and records only a rejection"
        + " of the name it watches (#1039)")
class FactNameWatchTest {

    /** A language whose compiler records the calls it gets, and rejects the fact names {@code a} and {@code b}. */
    private static final class Recording implements ExpressionLanguage {
        private final List<String> calls = new ArrayList<>();
        private final CompiledCondition condition = (context, session) -> true;
        private final CompiledAction action = (context, session) -> ActionResult.done();
        private final Session session = new Session() {
        };

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void prepare() {
            calls.add("prepare");
        }

        @Override
        public Set<String> reservedFactNames() {
            return Set.of("ctx");
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    return condition;
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    return action;
                }

                @Override
                public Session newSession() {
                    return session;
                }

                @Override
                public void warmUp(Session warmed) {
                    calls.add("warmUp");
                }

                @Override
                public void checkFactName(String name) {
                    if ("a".equals(name) || "b".equals(name)) {
                        throw new IllegalArgumentException("not a name: " + name);
                    }
                }

                @Override
                public void close() {
                    calls.add("close");
                }
            };
        }
    }

    @Test
    @DisplayName("every call reaches the language, and only a rejection of the watched name is recorded")
    void forwardsAndRecords() throws Exception {
        Recording language = new Recording();
        AtomicBoolean rejected = new AtomicBoolean();
        ExpressionLanguage watched = FactNameWatch.watchingRejection(language, "a", rejected);

        watched.prepare();
        assertEquals("recording", watched.name());
        assertEquals(Set.of("ctx"), watched.reservedFactNames());
        ExpressionCompiler compiler = watched.newCompiler(LanguageTestContexts.compile());
        assertSame(language.condition, compiler.compileCondition(new Expression("r", ExpressionKind.CONDITION, "c")));
        assertSame(language.action, compiler.compileAction(new Expression("r", ExpressionKind.ACTION, "a")));
        assertSame(language.session, compiler.newSession());
        compiler.warmUp(language.session);
        compiler.checkFactName("x");
        assertThrows(IllegalArgumentException.class, () -> compiler.checkFactName("b"));
        assertFalse(rejected.get(), "a rejection of another name was recorded");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> compiler.checkFactName("a"));
        compiler.close();

        assertEquals("not a name: a", thrown.getMessage());
        assertTrue(rejected.get(), "the rejection of the watched name wasn't recorded");
        assertEquals(List.of("prepare", "warmUp", "close"), language.calls);
    }
}
