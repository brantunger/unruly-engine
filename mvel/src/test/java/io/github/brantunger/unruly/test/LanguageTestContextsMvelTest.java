package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// The one test of LanguageTestContexts that needs MVEL. The others are in test-kit/src/test.
@DisplayName("LanguageTestContexts creates the engine's contexts for MVEL's unit tests")
class LanguageTestContextsMvelTest {

    @Test
    @DisplayName("MVEL expressions can be tested with the contexts, including imports")
    void unitTestMvel() throws Exception {
        ExpressionCompiler compiler = new MvelExpressionLanguage().newCompiler(
                LanguageTestContexts.compile(Set.of("java.util"), Set.of(), getClass().getClassLoader()));
        CompiledCondition condition = compiler.compileCondition(new Expression("r", ExpressionKind.CONDITION, "x > 1"));
        CompiledAction action = compiler.compileAction(
                new Expression("r", ExpressionKind.ACTION, "output.put('seen', new ArrayList(x))"));
        Session session = compiler.newSession();
        Map<String, Object> output = new HashMap<>();

        assertEquals(true, condition.evaluate(LanguageTestContexts.evaluation(Map.of("x", 2)), session));
        action.execute(LanguageTestContexts.action(Map.of("x", List.of(1, 2)), output), session);
        assertEquals(Map.of("seen", List.of(1, 2)), output);
    }
}
