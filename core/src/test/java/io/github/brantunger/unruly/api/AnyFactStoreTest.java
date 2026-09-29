package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.nio.file.Path;
import java.util.List;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles against the old and new {@code RulesEngine}, so it can show what didn't work before.
 */
@DisplayName("run() accepts a FactStore of any type")
class AnyFactStoreTest {

    @Test
    @DisplayName("run() takes a FactStore<?>")
    void runTakesWildcard() throws NoSuchMethodException {
        ParameterizedType parameter = (ParameterizedType) RulesEngine.class.getMethod("run", FactStore.class)
                .getGenericParameterTypes()[0];

        assertInstanceOf(WildcardType.class, parameter.getActualTypeArguments()[0]);
    }

    @Test
    @DisplayName("code that passes a FactMap<Integer> to run() compiles")
    void typedFactMapCompiles(@TempDir Path classes) {
        String source = """
                package com.example.facts;

                import io.github.brantunger.unruly.api.FactMap;
                import io.github.brantunger.unruly.api.RulesEngine;
                import java.util.Map;

                class Scores {
                    Map<String, Object> run(RulesEngine<Map<String, Object>> engine) {
                        FactMap<Integer> facts = new FactMap<>();
                        facts.setValue("score", 780);
                        return engine.run(facts);
                    }
                }
                """;
        assertCompiles(
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                List.of(source("com/example/facts/Scores", source)));
    }
}
