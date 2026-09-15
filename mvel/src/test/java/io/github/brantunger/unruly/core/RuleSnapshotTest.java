package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("rules are copied at setRuleList()")
class RuleSnapshotTest {

    private final List<String> evaluated = new CopyOnWriteArrayList<>();

    private final RuleListener recorder = new RuleListener() {
        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            evaluated.add(rule.getRuleName() + " | " + rule.getCondition() + " | " + rule.getPriority()
                    + " | " + rule.getDescription() + " -> " + matchResult);
        }
    };

    @Test
    @DisplayName("editing a rule after setRuleList() changes neither execution nor what listeners see")
    void editsAfterSetRuleListAreIgnored() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.registerListener(recorder);
        Rule rule = Rule.builder()
                .ruleName("original")
                .condition("true")
                .action("output.put('fired', 'original')")
                .priority(5)
                .description("before")
                .build();
        engine.setRuleList(List.of(rule));

        rule.setRuleName("renamed");
        rule.setCondition("false");
        rule.setAction("output.put('fired', 'edited')");
        rule.setPriority(1);
        rule.setDescription("after");

        Map<String, Object> result = engine.run(new FactMap<>());

        assertEquals("original", result.get("fired"));
        assertEquals(List.of("original | true | 5 | before -> true"), evaluated);
    }

    @Test
    @DisplayName("error messages name the rule as it was when set")
    void errorMessagesUseSnapshotName() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        Rule rule = Rule.builder().ruleName("original").condition("missing > 1").action("output.put('k', 1)").build();
        engine.setRuleList(List.of(rule));

        rule.setRuleName("renamed");

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        assertTrue(ex.getMessage().contains("'original'"));
        assertFalse(ex.getMessage().contains("renamed"));
    }

    @Test
    @DisplayName("calling setRuleList() again picks up the edits")
    void setRuleListAgainPicksUpEdits() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.registerListener(recorder);
        Rule rule = Rule.builder().ruleName("original").condition("true").action("output.put('k', 1)").build();
        engine.setRuleList(List.of(rule));

        rule.setRuleName("renamed");
        rule.setCondition("false");
        engine.setRuleList(List.of(rule));

        assertNull(engine.run(new FactMap<>()));
        assertEquals(List.of("renamed | false | null | null -> false"), evaluated);
    }
}
