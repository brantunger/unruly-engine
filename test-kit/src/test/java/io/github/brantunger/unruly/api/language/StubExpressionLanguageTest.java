package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the stub expression language's defaults and knobs")
class StubExpressionLanguageTest {

    private static final Expression CONDITION = new Expression("r", ExpressionKind.CONDITION, "c");
    private static final Expression ACTION = new Expression("r", ExpressionKind.ACTION, "a");

    private static ExpressionCompiler compilerOf(ExpressionLanguage language) {
        return language.newCompiler(LanguageTestContexts.compile());
    }

    /** Runs {@code action} against an empty fact store and the given output, with no session. */
    private static ActionResult run(CompiledAction action, Object output) throws Exception {
        return action.execute(LanguageTestContexts.action(Map.of(), output), Session.none());
    }

    @Test
    @DisplayName("by default it is named stub, every condition is true and every action does nothing")
    void defaults() throws Exception {
        StubExpressionLanguage language = new StubExpressionLanguage();
        ExpressionCompiler compiler = compilerOf(language);
        Map<String, Object> output = new HashMap<>();

        assertEquals("stub", language.name());
        assertEquals(StubExpressionLanguage.LANGUAGE_NAME, language.name());
        assertEquals(true, compiler.compileCondition(CONDITION).evaluate(
                LanguageTestContexts.evaluation(Map.of("x", 1)), Session.none()));
        assertSame(ActionResult.done(), run(compiler.compileAction(ACTION), output));
        assertEquals(Map.of(), output, "the default action leaves the output alone");
        assertSame(Session.none(), compiler.newSession(), "a stub keeps no state between runs");
        assertDoesNotThrow(() -> compiler.checkFactName("anything"), "every fact name is accepted");
    }

    @Test
    @DisplayName("named() gives the language the name its rules are written in")
    void named() {
        assertEquals("x", StubExpressionLanguage.named("x").name());
        assertEquals("x", StubExpressionLanguage.named("x").checkFactName(name -> {
        }).name(), "a later knob keeps the name");
    }

    @Test
    @DisplayName("action() gives every rule the same action, whatever the action's text is")
    void action() throws Exception {
        ExpressionCompiler compiler = compilerOf(new StubExpressionLanguage().action((context, session) -> {
            asMap(context.output()).put("ran", true);
            return ActionResult.done();
        }));
        Map<String, Object> output = new HashMap<>();

        run(compiler.compileAction(ACTION), output);
        run(compiler.compileAction(new Expression("r", ExpressionKind.ACTION, "something else")), output);

        assertEquals(Map.of("ran", true), output);
    }

    @Test
    @DisplayName("compileAction() sees each action's expression, so actions can differ by their text")
    void compileAction() throws Exception {
        ExpressionCompiler compiler = compilerOf(new StubExpressionLanguage()
                .compileAction(expression -> (context, session) ->
                        ActionResult.set(Map.of("text", expression.text()))));

        assertEquals(Map.of("text", "a"), run(compiler.compileAction(ACTION), new HashMap<>()).properties());
        assertEquals(Map.of("text", "b"),
                run(compiler.compileAction(new Expression("r", ExpressionKind.ACTION, "b")), new HashMap<>())
                        .properties());
    }

    @Test
    @DisplayName("newSession() hands out the sessions the supplier creates, and what it throws comes out")
    void newSession() {
        Session one = new Session() {
        };
        ExpressionCompiler sessions = compilerOf(new StubExpressionLanguage().newSession(() -> one));
        IllegalStateException failure = new IllegalStateException("no session");
        ExpressionCompiler failing = compilerOf(new StubExpressionLanguage().newSession(() -> {
            throw failure;
        }));

        assertSame(one, sessions.newSession());
        assertSame(failure, assertThrows(IllegalStateException.class, failing::newSession));
    }

    @Test
    @DisplayName("checkFactName() is called with each fact name, and rejects the names it throws for")
    void checkFactName() {
        List<String> checked = new ArrayList<>();
        ExpressionCompiler compiler = compilerOf(new StubExpressionLanguage().checkFactName(name -> {
            checked.add(name);
            if ("banned".equals(name)) {
                throw new IllegalArgumentException("'banned' is not allowed");
            }
        }));

        compiler.checkFactName("fine");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> compiler.checkFactName("banned"));

        assertEquals("'banned' is not allowed", ex.getMessage());
        assertEquals(List.of("fine", "banned"), checked);
    }

    @Test
    @DisplayName("onNewCompiler() is called with the context of every compiler the language creates")
    void onNewCompiler() {
        List<CompileContext> seen = new ArrayList<>();
        ExpressionLanguage language = new StubExpressionLanguage().onNewCompiler(seen::add);
        CompileContext first = LanguageTestContexts.compile();
        CompileContext second = LanguageTestContexts.compile();

        language.newCompiler(first);
        language.newCompiler(second);

        assertEquals(List.of(first, second), seen);
    }

    @Test
    @DisplayName("a knob returns a new language, so a configured one can be shared without being changed")
    void knobsDontChangeTheLanguageTheyAreCalledOn() throws Exception {
        StubExpressionLanguage stub = StubExpressionLanguage.named("x");

        StubExpressionLanguage changed = stub.action((context, session) -> ActionResult.set(Map.of("k", 1)))
                .checkFactName(name -> {
                    throw new IllegalArgumentException("rejected");
                });

        assertNotSame(stub, changed);
        ExpressionCompiler original = compilerOf(stub);
        assertSame(ActionResult.done(), run(original.compileAction(ACTION), new HashMap<>()));
        assertDoesNotThrow(() -> original.checkFactName("anything"));
        assertEquals("x", changed.name(), "the new language keeps what wasn't changed");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object output) {
        return (Map<String, Object>) output;
    }
}
