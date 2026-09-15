package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;

import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedAtError;
import static io.github.brantunger.unruly.core.EngineLoggingTest.assertLoggedThenRethrown;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An expression language that fails in any way while a rule list is compiled fails {@code load()} the way MVEL
 * does: with a logged {@link RuleCompilationException}, or a fatal {@link Error} that is logged and then rethrown.
 */
@DisplayName("load reports a failing expression language like a rule that doesn't compile")
class CompileFailureTest {

    private static final Supplier<CompiledCondition> TRUE = () -> (context, session) -> true;
    private static final Supplier<CompiledAction> NO_OP = () -> (context, session) -> ActionResult.done();

    private static ExpressionLanguage language(String name, Supplier<ExpressionCompiler> newCompiler) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return newCompiler.get();
            }
        };
    }

    private static ExpressionLanguage language(Supplier<CompiledCondition> condition, Supplier<CompiledAction> action) {
        return language("x", () -> new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression expression) {
                return condition.get();
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                return action.get();
            }

            @Override
            public Session newSession() {
                return Session.none();
            }
        });
    }

    private static <T> Supplier<T> throwing(RuntimeException exception) {
        return () -> {
            throw exception;
        };
    }

    private static <T> Supplier<T> throwing(Error error) {
        return () -> {
            throw error;
        };
    }

    /** An engine with only {@code language}. */
    private static StatefulRulesEngine<Map<String, Object>> engine(ExpressionLanguage language) {
        return TestEngines.allMatches(HashMap::new, builder -> builder.language(language));
    }

    private static List<Rule> ruleIn(ExpressionLanguage language) {
        return List.of(Rule.builder().ruleName("r").language(language.name()).condition("c").action("a").build());
    }

    /** Builds an engine with {@code language} and loads one rule written in it. */
    private static void load(ExpressionLanguage language) {
        engine(language).load(ruleIn(language));
    }

    // #183

    @Test
    @DisplayName("a missing class inside a compiler's exception fails the rule list, naming the rule")
    void linkageErrorWhileCompilingIsWrapped() {
        NoClassDefFoundError missing = new NoClassDefFoundError("OptionalDep");
        ExpressionLanguage language = language(throwing(new IllegalStateException("[Error: OptionalDep]", missing)),
                NO_OP);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertEquals("Condition for rule 'r' failed to compile: [Error: OptionalDep]", ex.getMessage());
        assertEquals("r", ex.getRuleName());
        assertSame(missing, ex.getCause().getCause(), "the missing class is the cause");
    }

    // #184 item 1

    @Test
    @DisplayName("a language that fails to create a compiler fails the rule list, and the previous list stays loaded")
    void newCompilerThrows() {
        ExpressionLanguage language = language("x", throwing(new IllegalStateException("script engine not available")));
        StatefulRulesEngine<Map<String, Object>> engine = TestEngines.allMatches(HashMap::new, builder -> builder
                .language(new MvelExpressionLanguage()).language(language)
                .defaultLanguage(MvelExpressionLanguage.LANGUAGE_NAME));
        engine.load(List.of(Rule.builder().ruleName("old").condition("true").action("output.put('k', 1)")
                .build()));

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class,
                () -> engine.load(ruleIn(language)));

        assertEquals("The 'x' expression language failed to create a compiler: script engine not available",
                ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(Map.of("k", 1), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("a fatal Error inside the exception a language throws creating a compiler is logged, then rethrown")
    void newCompilerWrapsFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        ExpressionLanguage language = language("x", throwing(new IllegalStateException("wrapped", oom)));

        assertLoggedThenRethrown(oom, "The 'x' expression language failed to create a compiler: wrapped",
                () -> load(language));
    }

    @Test
    @DisplayName("a language that returns no compiler is reported as such, not as a language the engine doesn't have")
    void newCompilerReturnsNull() {
        ExpressionLanguage language = language("x", () -> null);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertEquals("The 'x' expression language returned no compiler", ex.getMessage());
    }

    @Test
    @DisplayName("an empty rule list fails when the default language can't create the compiler facts are checked with")
    void emptyListNewCompilerThrows() {
        StatefulRulesEngine<Map<String, Object>> engine = engine(language("default",
                throwing(new IllegalStateException("broken"))));

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class,
                () -> engine.load(List.of()));

        assertEquals("The 'default' expression language failed to create a compiler: broken", ex.getMessage());
    }

    // #184 item 2

    @Test
    @DisplayName("a StackOverflowError a compiler throws is wrapped and logged, like MVEL's")
    void compilerThrowsStackOverflowError() {
        StackOverflowError overflow = new StackOverflowError();
        ExpressionLanguage language = language(throwing(overflow), NO_OP);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertEquals("Condition for rule 'r' failed to compile: the expression is too long or too deeply nested to compile",
                ex.getMessage());
        assertSame(overflow, ex.getCause());
    }

    @Test
    @DisplayName("an AssertionError a compiler throws is wrapped and logged")
    void compilerThrowsAssertionError() {
        AssertionError assertion = new AssertionError("parser invariant");
        ExpressionLanguage language = language(TRUE, throwing(assertion));

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertEquals("Action for rule 'r' failed to compile: parser invariant", ex.getMessage());
        assertSame(assertion, ex.getCause());
    }

    @Test
    @DisplayName("a fatal Error that causes a language's rejection is logged, then rethrown, as at run()")
    void rejectionCausedByFatalError() {
        OutOfMemoryError oom = new OutOfMemoryError("simulated");
        InvalidExpressionException rejection = new InvalidExpressionException("contains an assignment");
        rejection.initCause(oom);
        ExpressionLanguage language = language(throwing(rejection), NO_OP);

        assertLoggedThenRethrown(oom, "Condition for rule 'r' contains an assignment", () -> load(language));
    }

    // #184 item 3

    @Test
    @DisplayName("a compiler that returns no compiled condition fails the rule list instead of every run")
    void nullCondition() {
        ExpressionLanguage language = language(() -> null, NO_OP);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertEquals("Condition for rule 'r' wasn't compiled: its expression language returned null", ex.getMessage());
    }

    @Test
    @DisplayName("a compiler that returns no compiled action fails the rule list instead of every run")
    void nullAction() {
        ExpressionLanguage language = language(TRUE, () -> null);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertEquals("Action for rule 'r' wasn't compiled: its expression language returned null", ex.getMessage());
    }

    // #184 item 4

    @Test
    @DisplayName("a rejection without a message gets a fixed reason instead of 'null'")
    void rejectionWithoutMessage() {
        ExpressionLanguage language = language(throwing(new InvalidExpressionException(null)), NO_OP);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertEquals("Condition for rule 'r' was rejected by its expression language", ex.getMessage());
    }

    @Test
    @DisplayName("a very long rejection message is shortened like every other failure message")
    void longRejectionShortened() {
        ExpressionLanguage language = language(throwing(new InvalidExpressionException("x".repeat(200_000))), NO_OP);

        RuleCompilationException ex = assertLoggedAtError(RuleCompilationException.class, () -> load(language));

        assertTrue(ex.getMessage().startsWith("Condition for rule 'r' xxx"), ex.getMessage());
        assertTrue(ex.getMessage().length() < Failures.MAX_DESCRIPTION_LENGTH + 100, ex.getMessage());
    }
}
