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
     * Parsing in given priority/steps.
     * @param expression
     * @param inputData
     */
    public boolean parseCondition(String expression, I inputData) {
        Map<String, Object> input = new HashMap<>();
        input.put(INPUT_KEYWORD, inputData);
        return mvelParser.parseMvelExpression(expression, input);
    }

    /**
     * Parsing in given priority/steps.
     * @param expression
     * @param inputData
     * @param outputResult
     * @return
     */
    public O parseAction(String expression, I inputData, O outputResult) {
        Map<String, Object> input = new HashMap<>();
        input.put(INPUT_KEYWORD, inputData);
        input.put(OUTPUT_KEYWORD, outputResult);
        mvelParser.evalMvelExpression(expression, input);
        return outputResult;
    }
}
