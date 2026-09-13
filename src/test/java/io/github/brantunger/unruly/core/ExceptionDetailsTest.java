package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.CompileException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("exceptions keep their cause and name the null argument")
class ExceptionDetailsTest {

    private final StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

    @Test
    @DisplayName("a compile error keeps MVEL's exception as its cause")
    void compileErrorKeepsCause() {
        List<Rule> rules = List.of(Rule.builder().ruleName("a").condition("x >= ").action("output.put('k', 1)").build());

        RuleCompilationException ex = assertThrows(RuleCompilationException.class, () -> engine.setRuleList(rules));

        assertInstanceOf(CompileException.class, ex.getCause());
    }

    @Test
    @DisplayName("a rejected import keeps the failed class lookup as its cause")
    void rejectedImportKeepsCause() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.addImport("not a package!!"));

        assertInstanceOf(ClassNotFoundException.class, ex.getCause());
    }

    @Test
    @DisplayName("addImports(null) names the argument")
    void addImportsNullMessage() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> engine.addImports(null));

        assertEquals("packages must not be null", ex.getMessage());
    }
}
