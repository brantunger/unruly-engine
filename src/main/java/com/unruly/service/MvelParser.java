package com.unruly.service;

import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class MvelParser {

    /**
     * Evaluate the MVEL expression to a boolean value
     * @param expression The MVEL expression to evaluate
     * @param inputObjects The variables to be injected as a {@link Map}
     * @return Boolean value of the evaluated expression
     */
    public boolean evaluateToBoolean(String expression, Map<String, Object> inputObjects) {
        try {
            return MVEL.evalToBoolean(expression, inputObjects);
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression : {} Error: {}", expression, e.getMessage());
        }
        return false;
    }

    /**
     * Evaluate the MVEL expression on the given inputs
     * @param expression The MVEL expression to evaluate
     * @param inputObjects The variables to be injected as a {@link Map}
     */
    public void evaluateExpression(String expression, Map<String, Object> inputObjects) {
        try {
            MVEL.eval(expression, inputObjects);
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression : {} Error: {}", expression, e.getMessage());
        }
    }
}
