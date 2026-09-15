package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
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
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLoggingTest.logsOf;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("rule exceptions carry the name of the rule that failed")
class RuleNameOnExceptionTest {

    private final RulesEngine<Map<String, Object>> engine =
            RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /** Runs {@code action}, which must throw {@code type}, without printing the engine's ERROR log. */
    private static <T extends Throwable> T thrown(Class<T> type, Executable action) {
        AtomicReference<T> thrown = new AtomicReference<>();
        logsOf(() -> thrown.set(assertThrows(type, action)));
        return thrown.get();
    }

    /** A language that fails to create a session, so the first run fails before it evaluates any rule. */
    private static ExpressionLanguage failingSessionLanguage() {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "no-session";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluationContext, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return (actionContext, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        throw new IllegalStateException("can't create a session");
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("a failing condition, a failing action and a non-boolean condition name their rule")
    void runFailures() {
        engine.load(List.of(rule("condition", "missing > 1", "output.put('a', 1)")));
        assertEquals("condition", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());

        engine.load(List.of(rule("action", "true", "missing.call()")));
        assertEquals("action", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());

        engine.load(List.of(rule("text", "'text'", "output.put('a', 1)")));
        assertEquals("text", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());

        engine.load(List.of(rule("null", "null", "output.put('a', 1)")));
        assertEquals("null", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());
    }

    @Test
    @DisplayName("a rule that fails to compile, has a blank expression, a language the engine lacks or a duplicate name is named")
    void compilationFailures() {
        assertEquals("syntax", thrown(RuleCompilationException.class,
                () -> engine.load(List.of(rule("syntax", "applicant.creditScore >=", "x")))).getRuleName());
        assertEquals("blank-condition", thrown(RuleCompilationException.class,
                () -> engine.load(List.of(rule("blank-condition", " ", "x")))).getRuleName());
        assertEquals("blank-action", thrown(RuleCompilationException.class,
                () -> engine.load(List.of(rule("blank-action", "true", "")))).getRuleName());
        assertEquals("elsewhere", thrown(RuleCompilationException.class,
                () -> engine.load(List.of(Rule.builder().ruleName("elsewhere").language("nope")
                        .condition("true").action("x").build()))).getRuleName());
        assertEquals("twice", thrown(RuleCompilationException.class,
                () -> engine.load(List.of(rule("twice", "true", "x"), rule("twice", "true", "x"))))
                .getRuleName());
    }

    @Test
    @DisplayName("a language that fails to create a session names no rule: the failure is about the language")
    void sessionFailure() {
        RulesEngine<Map<String, Object>> sessions = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(failingSessionLanguage()).build();
        sessions.load(List.of(Rule.builder().ruleName("unstarted").language("no-session").condition("c")
                .action("a").build()));

        RuleExecutionException ex = thrown(RuleExecutionException.class, () -> sessions.run(new FactMap<>()));

        assertNull(ex.getRuleName());
        assertTrue(ex.getMessage().startsWith("The 'no-session' expression language failed to create a session"),
                ex.getMessage());
    }

    @Test
    @DisplayName("the exception onError receives for a fatal Error from a before* callback names the rule")
    void listenerFatalError() {
        AtomicReference<RuleExecutionException> reported = new AtomicReference<>();
        RulesEngine<Map<String, Object>> listened = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .listener(new RuleListener() {
                    @Override
                    public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                        throw new OutOfMemoryError("simulated");
                    }

                    @Override
                    public void onError(Rule rule, RuleExecutionException error) {
                        reported.set(error);
                    }
                })
                .build();
        listened.load(List.of(rule("heard", "true", "x")));

        thrown(OutOfMemoryError.class, () -> listened.run(new FactMap<>()));

        assertEquals("heard", reported.get().getRuleName());
    }

    @Test
    @DisplayName("the name isn't escaped: a name with a line break is returned as it was given")
    void nameNotEscaped() {
        engine.load(List.of(rule("two\nlines", "missing > 1", "x")));

        RuleExecutionException ex = thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("two\nlines", ex.getRuleName());
        assertTrue(ex.getMessage().contains("rule 'two\\nlines'"), ex.getMessage());
    }

    @Test
    @DisplayName("failures that aren't about one rule have no rule name")
    void noRuleName() {
        assertNull(thrown(RuleCompilationException.class, () -> engine.load(Arrays.asList((Rule) null)))
                .getRuleName());

        RulesEngine<Map<String, Object>> failingOutput = RulesEngineBuilder.<Map<String, Object>>allMatches(() -> {
            throw new IllegalStateException("no output");
        }).build();
        failingOutput.load(List.of(rule("r", "true", "x")));
        assertNull(thrown(RuleExecutionException.class, () -> failingOutput.run(new FactMap<>())).getRuleName());

        RulesEngine<Map<String, Object>> broken = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new ExpressionLanguage() {
                    @Override
                    public String name() {
                        return "broken";
                    }

                    @Override
                    public ExpressionCompiler newCompiler(CompileContext context) {
                        throw new IllegalStateException("no compiler");
                    }
                })
                .build();
        assertNull(thrown(RuleCompilationException.class, () -> broken.load(List.of(Rule.builder()
                .ruleName("r").language("broken").condition("c").action("a").build()))).getRuleName());
    }

    @Test
    @DisplayName("the constructors without a rule name leave it null, and the new one keeps it")
    void constructors() {
        IllegalStateException cause = new IllegalStateException();

        assertNull(new RuleExecutionException("m").getRuleName());
        assertNull(new RuleExecutionException("m", cause).getRuleName());
        assertNull(new RuleCompilationException("m").getRuleName());
        assertNull(new RuleCompilationException("m", cause).getRuleName());
        RuleExecutionException execution = new RuleExecutionException("m", cause, "r");
        RuleCompilationException compilation = new RuleCompilationException("m", cause, "r");
        assertEquals("r", execution.getRuleName());
        assertSame(cause, execution.getCause());
        assertEquals("r", compilation.getRuleName());
        assertSame(cause, compilation.getCause());
    }
}
