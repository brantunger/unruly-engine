package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
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

    private final RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.stateful(HashMap::new);

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    /** Runs {@code action}, which must throw {@code type}, without printing the engine's ERROR log. */
    private static <T extends Throwable> T thrown(Class<T> type, Executable action) {
        AtomicReference<T> thrown = new AtomicReference<>();
        logsOf(() -> thrown.set(assertThrows(type, action)));
        return thrown.get();
    }

    /** A language whose compiled condition fails to copy, so the first run fails while copying the rule. */
    private static ExpressionLanguage failingCopyLanguage() {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "no-copy";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(String source) {
                        return new CompiledCondition() {
                            @Override
                            public Object evaluate(io.github.brantunger.unruly.api.language.EvaluationContext c) {
                                return true;
                            }

                            @Override
                            public CompiledCondition copy() {
                                throw new IllegalStateException("can't copy");
                            }
                        };
                    }

                    @Override
                    public CompiledAction compileAction(String source) {
                        return actionContext -> {
                        };
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("a failing condition, a failing action and a non-boolean condition name their rule")
    void runFailures() {
        engine.setRuleList(List.of(rule("condition", "missing > 1", "output.put('a', 1)")));
        assertEquals("condition", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());

        engine.setRuleList(List.of(rule("action", "true", "missing.call()")));
        assertEquals("action", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());

        engine.setRuleList(List.of(rule("text", "'text'", "output.put('a', 1)")));
        assertEquals("text", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());

        engine.setRuleList(List.of(rule("null", "null", "output.put('a', 1)")));
        assertEquals("null", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());
    }

    @Test
    @DisplayName("a rule that fails to compile, has a blank expression, an unregistered language or a duplicate name is named")
    void compilationFailures() {
        assertEquals("syntax", thrown(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("syntax", "applicant.creditScore >=", "x")))).getRuleName());
        assertEquals("blank-condition", thrown(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("blank-condition", " ", "x")))).getRuleName());
        assertEquals("blank-action", thrown(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("blank-action", "true", "")))).getRuleName());
        assertEquals("elsewhere", thrown(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(Rule.builder().ruleName("elsewhere").language("nope")
                        .condition("true").action("x").build()))).getRuleName());
        assertEquals("twice", thrown(RuleCompilationException.class,
                () -> engine.setRuleList(List.of(rule("twice", "true", "x"), rule("twice", "true", "x"))))
                .getRuleName());
    }

    @Test
    @DisplayName("a compiled expression that fails to copy names its rule")
    void copyFailure() {
        engine.registerLanguage(failingCopyLanguage());
        engine.setRuleList(List.of(Rule.builder().ruleName("copied").language("no-copy").condition("c").action("a")
                .build()));

        assertEquals("copied", thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());
    }

    @Test
    @DisplayName("the exception onError receives for a fatal Error from a before* callback names the rule")
    void listenerFatalError() {
        AtomicReference<RuleExecutionException> reported = new AtomicReference<>();
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                throw new OutOfMemoryError("simulated");
            }

            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                reported.set(error);
            }
        });
        engine.setRuleList(List.of(rule("heard", "true", "x")));

        thrown(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        assertEquals("heard", reported.get().getRuleName());
    }

    @Test
    @DisplayName("the name isn't escaped: a name with a line break is returned as it was given")
    void nameNotEscaped() {
        engine.setRuleList(List.of(rule("two\nlines", "missing > 1", "x")));

        RuleExecutionException ex = thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("two\nlines", ex.getRuleName());
        assertTrue(ex.getMessage().contains("rule 'two\\nlines'"), ex.getMessage());
    }

    @Test
    @DisplayName("an unnamed rule, and failures that aren't about one rule, have no rule name")
    void noRuleName() {
        engine.setRuleList(List.of(Rule.builder().condition("missing > 1").action("x").build()));
        assertNull(thrown(RuleExecutionException.class, () -> engine.run(new FactMap<>())).getRuleName());

        assertNull(thrown(RuleCompilationException.class, () -> engine.setRuleList(Arrays.asList((Rule) null)))
                .getRuleName());

        RulesEngine<Map<String, Object>> failingOutput = RulesEngineBuilder.stateful(() -> {
            throw new IllegalStateException("no output");
        });
        failingOutput.setRuleList(List.of(rule("r", "true", "x")));
        assertNull(thrown(RuleExecutionException.class, () -> failingOutput.run(new FactMap<>())).getRuleName());

        engine.registerLanguage(new ExpressionLanguage() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                throw new IllegalStateException("no compiler");
            }
        });
        assertNull(thrown(RuleCompilationException.class, () -> engine.setRuleList(List.of(Rule.builder()
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
