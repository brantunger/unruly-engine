package com.unruly.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DslKeywordResolver {

    private final Map<String, DslResolver> dslKeywordResolverList;

    public DslKeywordResolver(List<DslResolver> resolverList) {
        dslKeywordResolverList = resolverList.stream()
                .collect(Collectors.toMap(DslResolver::getResolverKeyword, Function.identity()));
    }

    public Optional<DslResolver> getResolver(String keyword) {
        return Optional.ofNullable(dslKeywordResolverList.get(keyword));
    }
}
