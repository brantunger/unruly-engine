package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("fact names are checked against imported classes with the rule list's class loader")
class FactNameClassLookupTest {

    /** A class the rule list's class loader can see, in a package the tests import: this test. */
    private static final String IMPORTED_PACKAGE = FactNameClassLookupTest.class.getPackageName();
    private static final String CLASS_NAME = FactNameClassLookupTest.class.getSimpleName();

    private static Rule rule(String condition) {
        return Rule.builder().ruleName("r").condition(condition).action("output.put('hit', true)").build();
    }

    private static FactStore<Object> fact(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
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

    /** Runs {@code run()} on a new thread whose context class loader is {@code loader}. */
    private static Object runOnThread(ClassLoader loader, RulesEngine<Map<String, Object>> engine,
                                      FactStore<Object> facts) throws InterruptedException {
        AtomicReference<Object> result = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                result.set(engine.run(facts));
            } catch (RuntimeException e) {
                result.set(e);
            }
        });
        thread.setContextClassLoader(loader);
        thread.start();
        thread.join();
        return result.get();
    }

    /** A class loader that can't see the class path, so it doesn't know this library's classes. */
    private static ClassLoader classPathHidden() {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
        };
    }

    @Test
    @DisplayName("a fact name that isn't a class is never loaded as one, so nothing stays in the class loader")
    void nonClassNameNeverLoaded() {
        RecordingClassLoader loader = new RecordingClassLoader();

        Object output = withContextClassLoader(loader, () -> {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .imports("java.util").build();
            engine.load(List.of(rule("true")));
            return engine.run(fact("line_1", 1));
        });

        assertEquals(Map.of("hit", true), output);
        assertEquals(List.of(), loader.loadedClasses.stream().filter(name -> name.endsWith("line_1")).toList());
        assertTrue(loader.resources.contains("java/util/line_1.class"));
    }

    @Test
    @DisplayName("a class name is rejected on a thread whose context class loader can't see the class")
    void classNameRejectedOnAnyThread() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports(IMPORTED_PACKAGE).build();
        engine.load(List.of(rule("true")));

        Object result = runOnThread(classPathHidden(), engine, fact(CLASS_NAME, 1));

        assertInstanceOf(IllegalArgumentException.class, result);
    }

    @Test
    @DisplayName("a fact rules can read isn't rejected because the running thread's class loader sees a class")
    void readableFactAcceptedOnAnyThread() throws InterruptedException {
        RulesEngine<Map<String, Object>> engine = withContextClassLoader(classPathHidden(), () -> {
            RulesEngine<Map<String, Object>> built = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .imports(IMPORTED_PACKAGE).build();
            built.load(List.of(rule(CLASS_NAME + " == 1")));
            return built;
        });

        Object result = runOnThread(FactNameClassLookupTest.class.getClassLoader(), engine, fact(CLASS_NAME, 1));

        assertEquals(Map.of("hit", true), result);
    }

    @Test
    @DisplayName("a thread without a context class loader uses the library's own class loader")
    void noContextClassLoader() {
        Object output = withContextClassLoader(null, () -> {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                    .imports("java.util.Map.Entry", "java.util").build();
            engine.load(List.of(rule("Objects.nonNull(Entry)")));
            assertThrows(IllegalArgumentException.class, () -> engine.run(fact("Date", 1)));
            return engine.run(fact("claim", 1));
        });

        assertEquals(Map.of("hit", true), output);
    }
}
