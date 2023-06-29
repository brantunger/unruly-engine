package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RuleParser is an MVEL based rule parser implementation. The Parser parses and evaluates conditional expressions and
 * action expressions. When the condition expression evaluates to <strong>true</strong> the action expression can be
 * executed by the {@link RulesEngine}.
 *
 * @param <O> The output object type to instantiate
 */
@Slf4j
public class RuleParser<O> implements Parser<O> {

    private static final String OUTPUT_KEYWORD = "output";


    /**
     * Parse the condition field within a {@link Rule} from a pre-compiled expression.
     *
     * @param expression The serialized MVEL expression to evaluate
     * @param facts      The input object store representing facts
     * @return A boolean value that the condition resolves to
     */
    @Override
    public boolean parseCondition(Serializable expression, FactStore<Object> facts) {
        Map<String, Object> entryMap = facts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getValue()));
        try {
            return (Boolean) MVEL.executeExpression(expression, entryMap);
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression Error: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Parse the action field within a {@link Rule} from a pre-compiled rule
     *
     * @param compiledExpression The serialized MVEL expression to evaluate
     * @param outputResult       The output object to assign values to
     * @return The outputResult of parsing the action
     */
    @Override
    public O parseAction(Serializable compiledExpression, O outputResult) {
        Map<String, Object> input = new HashMap<>();
        input.put(OUTPUT_KEYWORD, outputResult);
        try {
            MVEL.executeExpression(compiledExpression, input);
            return outputResult;
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression Error: {}", e.getMessage());
            throw e;
        }
    }
}
