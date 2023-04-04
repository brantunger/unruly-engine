package com.unruly.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class RuleParser<I, O> {

    private static final String INPUT_KEYWORD = "input";
    private static final String OUTPUT_KEYWORD = "output";

    private final MvelParser mvelParser;


    public RuleParser(MvelParser mvelParser) {
        this.mvelParser = mvelParser;
    }

    /**
     * Parse the condition field within a {@link com.unruly.model.Rule}
     * @param expression The MVEL expression to evaluate
     * @param inputData The input data to run the condition against
     * @return A boolean value that the condition resolves to
     */
    public boolean parseCondition(String expression, I inputData) {
        Map<String, Object> input = new HashMap<>();
        input.put(INPUT_KEYWORD, inputData);
        return mvelParser.evaluateToBoolean(expression, input);
    }

    /**
     * Parse the action field within a {@link com.unruly.model.Rule}
     * @param expression The MVEL expression to evaluate
     * @param inputData The input data to run the action against
     * @param outputResult The output object to assign values to
     * @return The outputResult of parsing the action
     */
    public O parseAction(String expression, I inputData, O outputResult) {
        Map<String, Object> input = new HashMap<>();
        input.put(INPUT_KEYWORD, inputData);
        input.put(OUTPUT_KEYWORD, outputResult);
        mvelParser.evaluateExpression(expression, input);
        return outputResult;
    }
}
