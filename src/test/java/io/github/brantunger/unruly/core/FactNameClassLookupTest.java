package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("fact names are checked against imported classes with the rule list's class loader")
class FactNameClassLookupTest {

    private static final String CORE_PACKAGE = "io.github.brantunger.unruly.core";

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
    private static Object runOnThread(ClassLoader loader, StatelessRulesEngine<Map<String, Object>> engine,
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

    /** Records the classes it is asked to load. */
    static final class RecordingClassLoader extends ClassLoader {

        final List<String> loadedClasses = new CopyOnWriteArrayList<>();
        final List<String> resources = new CopyOnWriteArrayList<>();

        RecordingClassLoader() {
            super(RecordingClassLoader.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            loadedClasses.add(name);
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            resources.add(name);
            return super.getResource(name);
        }
    }

    @Test
    @DisplayName("a fact name that isn't a class is never loaded as one, so nothing stays in the class loader")
    void nonClassNameNeverLoaded() {
        RecordingClassLoader loader = new RecordingClassLoader();
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        Object output = withContextClassLoader(loader, () -> {
            engine.addImport("java.util");
            engine.setRuleList(List.of(rule("true")));
            return engine.run(fact("line_1", 1));
        });

        assertEquals(Map.of("hit", true), output);
        assertEquals(List.of(), loader.loadedClasses.stream().filter(name -> name.endsWith("line_1")).toList());
        assertTrue(loader.resources.contains("java/util/line_1.class"));
    }

    @Test
    @DisplayName("a class name is rejected on a thread whose context class loader can't see the class")
    void classNameRejectedOnAnyThread() throws InterruptedException {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        engine.addImport(CORE_PACKAGE);
        engine.setRuleList(List.of(rule("true")));

        Object result = runOnThread(classPathHidden(), engine, fact("Imports", 1));

        assertInstanceOf(IllegalArgumentException.class, result);
    }

    @Test
    @DisplayName("a fact rules can read isn't rejected because the running thread's class loader sees a class")
    void readableFactAcceptedOnAnyThread() throws InterruptedException {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);
        withContextClassLoader(classPathHidden(), () -> {
            engine.addImport(CORE_PACKAGE);
            engine.setRuleList(List.of(rule("Imports == 1")));
            return null;
        });

        Object result = runOnThread(FactNameClassLookupTest.class.getClassLoader(), engine, fact("Imports", 1));

        assertEquals(Map.of("hit", true), result);
    }

    @Test
    @DisplayName("a thread without a context class loader uses the library's own class loader")
    void noContextClassLoader() {
        StatelessRulesEngine<Map<String, Object>> engine = new StatelessRulesEngine<>(HashMap::new);

        Object output = withContextClassLoader(null, () -> {
            engine.addImport("java.util.Map.Entry").addImport("java.util");
            engine.setRuleList(List.of(rule("Objects.nonNull(Entry)")));
            assertThrows(IllegalArgumentException.class, () -> engine.run(fact("Date", 1)));
            return engine.run(fact("claim", 1));
        });

        assertEquals(Map.of("hit", true), output);
    }
}
