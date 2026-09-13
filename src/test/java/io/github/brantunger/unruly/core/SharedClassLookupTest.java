package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.core.FactNameClassLookupTest.RecordingClassLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a rule list looks up whether a name is a class only once")
class SharedClassLookupTest {

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static FactStore<Object> amount(int value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("amount", value);
        return facts;
    }

    private static <T> T withContextClassLoader(ClassLoader loader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("a name used by several expressions, and by a copy compiled for a concurrent run, is looked up once")
    void notClassLookedUpOnce() {
        RecordingClassLoader loader = new RecordingClassLoader();
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.addImport("java.util");
        List<Object> innerOutputs = new CopyOnWriteArrayList<>();
        engine.registerListener(new RuleListener() {
            @Override
            public void beforeExecute(Rule rule, Object output) {
                // A run started while the outer run holds its copy of the rules compiles another copy.
                if (innerOutputs.isEmpty()) {
                    innerOutputs.add("started");
                    innerOutputs.set(0, engine.run(amount(2)));
                }
            }
        });

        Object output = withContextClassLoader(loader, () -> {
            engine.setRuleList(List.of(
                    rule("low", "amount > 1", "output.put('low', amount)"),
                    rule("high", "amount < 10", "output.put('high', amount)")));
            return engine.run(amount(5));
        });

        assertEquals(Map.of("low", 5, "high", 5), output);
        assertEquals(List.of(Map.of("low", 2, "high", 2)), innerOutputs);
        assertEquals(1, Collections.frequency(loader.loadedClasses, "java.util.amount"));
    }

    @Test
    @DisplayName("an inline import still finds a class that another rule's expressions found not to be one")
    void inlineImportsNotAnsweredFromCache() {
        StatefulRulesEngine<Map<String, Object>> engine = new StatefulRulesEngine<>(HashMap::new);
        engine.addImport("java.io");
        engine.setRuleList(List.of(
                rule("fact", "Date == 1", "output.put('fact', Date)"),
                rule("class", "true", "import java.util.Date; output.put('class', new Date(0))"),
                rule("package", "true", "import java.util.*; output.put('package', new Date(0))")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("Date", 1);

        assertEquals(Map.of("fact", 1, "class", new Date(0), "package", new Date(0)), engine.run(facts));
    }
}
