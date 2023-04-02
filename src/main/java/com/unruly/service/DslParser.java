package com.unruly.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DslParser {

    private final DslKeywordResolver keywordResolver;
    private final DslPatternUtil dslPatternUtil;

    public DslParser(DslKeywordResolver keywordResolver, DslPatternUtil dslPatternUtil) {
        this.keywordResolver = keywordResolver;
        this.dslPatternUtil = dslPatternUtil;
    }

    public String resolveDomainSpecificKeywords(String expression) {
        Map<String, Object> dslKeywordToResolverValueMap = executeDSLResolver(expression);
        return replaceKeywordsWithValue(expression, dslKeywordToResolverValueMap);
    }

    private Map<String, Object> executeDSLResolver(String expression) {
        List<String> listOfDslKeyword = dslPatternUtil.getListOfDslKeywords(expression);
        Map<String, Object> dslKeywordToResolverValueMap = new HashMap<>();
        listOfDslKeyword.forEach(dslKeyword -> {
                    String extractedDslKeyword = dslPatternUtil.extractKeyword(dslKeyword);
                    String keyResolver = dslPatternUtil.getKeywordResolver(extractedDslKeyword);
                    String keywordValue = dslPatternUtil.getKeywordValue(extractedDslKeyword);
                    DslResolver resolver = keywordResolver.getResolver(keyResolver).get();
                    Object resolveValue = resolver.resolveValue(keywordValue);
                    dslKeywordToResolverValueMap.put(dslKeyword, resolveValue);
                }
        );
        return dslKeywordToResolverValueMap;
    }

    private String replaceKeywordsWithValue(String expression, Map<String, Object> dslKeywordToResolverValueMap) {
        List<String> keyList = dslKeywordToResolverValueMap.keySet().stream().toList();
        for (String key : keyList) {
            String dslResolveValue = dslKeywordToResolverValueMap.get(key).toString();
            expression = expression.replace(key, dslResolveValue);
        }
        return expression;
    }
}
