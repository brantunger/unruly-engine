package com.unruly.service;

import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.mvel2.MVELRuntime;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class MvelParser {

    public boolean parseMvelExpression(String expression, Map<String, Object> inputObjects) {
        try {
            return MVEL.evalToBoolean(expression, inputObjects);
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression : {} Error: {}", expression, e.getMessage());
        }
        return false;
    }

    public void evalMvelExpression(String expression, Map<String, Object> inputObjects) {
        try {
            MVEL.eval(expression, inputObjects);
        } catch (Exception e) {
            log.error("Can not evaluate MVEL Expression : {} Error: {}", expression, e.getMessage());
        }
    }
}
