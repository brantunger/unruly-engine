package com.unruly.service;

import com.unruly.model.FactStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RuleParser<O> {

    private static final String OUTPUT_KEYWORD = "output";

    private final MvelParser mvelParser;


    public RuleParser(MvelParser mvelParser) {
        this.mvelParser = mvelParser;
    }

    /**
     * Parse the condition field within a {@link com.unruly.model.Rule}
     *
     * @param expression The MVEL expression to evaluate
     * @return A boolean value that the condition resolves to
     */
    public boolean parseCondition(String expression, FactStore<Object> facts) {
        Map<String, Object> entryMap = facts.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getValue()));
        return mvelParser.evaluateToBoolean(expression, entryMap);
    }

    /**
     * Parse the action field within a {@link com.unruly.model.Rule}
     *
     * @param expression   The MVEL expression to evaluate
     * @param outputResult The output object to assign values to
     * @return The outputResult of parsing the action
     */
    public O parseAction(String expression, O outputResult) {
        Map<String, Object> input = new HashMap<>();
        input.put(OUTPUT_KEYWORD, outputResult);
        mvelParser.evaluateExpression(expression, input);
        return outputResult;
    }
}
