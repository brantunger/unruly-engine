package com.unruly.service;

public interface DslResolver {

    String getResolverKeyword();

    Object resolveValue(String keyword);
}
