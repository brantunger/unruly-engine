package com.unruly.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class RuleParser<I, O> {

    private static final String INPUT_KEYWORD = "input";
    private static final String OUTPUT_KEYWORD = "output";

    private final DslParser dslParser;
    private final MvelParser mvelParser;


    public RuleParser(DslParser dslParser, MvelParser mvelParser) {
        this.dslParser = dslParser;
        this.mvelParser = mvelParser;
    }

    /**
     * Parsing in given priority/steps.
     * <p>
     * Step 1. Resolve domain specific keywords first: $(rulenamespace.keyword)
     * Step 2. Resolve MVEL expression.
     *
     * @param expression
     * @param inputData
     */
    public boolean parseCondition(String expression, I inputData) {
        String resolvedDslExpression = dslParser.resolveDomainSpecificKeywords(expression);
        Map<String, Object> input = new HashMap<>();
        input.put(INPUT_KEYWORD, inputData);
        return mvelParser.parseMvelExpression(resolvedDslExpression, input);
    }

    /**
     * Parsing in given priority/steps.
     * <p>
     * Step 1. Resolve domain specific keywords: $(rulenamespace.keyword)
     * Step 2. Resolve MVEL expression.
     *
     * @param expression
     * @param inputData
     * @param outputResult
     * @return
     */
    public O parseAction(String expression, I inputData, O outputResult) {
        String resolvedDslExpression = dslParser.resolveDomainSpecificKeywords(expression);
        Map<String, Object> input = new HashMap<>();
        input.put(INPUT_KEYWORD, inputData);
        input.put(OUTPUT_KEYWORD, outputResult);
        mvelParser.evalMvelExpression(resolvedDslExpression, input);
        return outputResult;
    }
}
