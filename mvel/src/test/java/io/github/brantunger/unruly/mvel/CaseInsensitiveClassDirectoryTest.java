package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The class loader here reproduces what a class directory on Windows or macOS does when MVEL asks whether the fact
 * name {@code applicant} is a class: it finds {@code Applicant.class}, and defining it throws a "wrong name"
 * {@code NoClassDefFoundError}. Faking it makes the test the same on every CI leg, including Linux, whose file
 * system is case-sensitive. No test loads a class from a real case-insensitive directory yet (#414).
 */
@DisplayName("a fact name that matches a class file in a different case compiles as the fact")
class CaseInsensitiveClassDirectoryTest {

    /** A class loader that throws {@code error} when asked for the class {@code applicant}. */
    private static ClassLoader loaderThrowing(NoClassDefFoundError error) {
        return new ClassLoader(CaseInsensitiveClassDirectoryTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("applicant")) {
                    throw error;
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static <T> T withContextClassLoader(ClassLoader loader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static final Rule PRIME_RATE = Rule.builder().ruleName("prime-rate")
            .condition("applicant.creditScore >= 750").action("output.put('rate', 4.5)").build();

    @Test
    @DisplayName("the README quick start's rule compiles and runs when 'applicant' finds Applicant.class")
    void wrongNameIsNotAClass() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
        ClassLoader caseInsensitive = loaderThrowing(new NoClassDefFoundError("applicant (wrong name: Applicant)"));

        Map<String, Object> output = withContextClassLoader(caseInsensitive, () -> {
            engine.load(List.of(PRIME_RATE));
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("applicant", Map.of("creditScore", 780));
            return engine.run(facts);
        });

        assertEquals(Map.of("rate", 4.5), output);
    }

    @Test
    @DisplayName("any other NoClassDefFoundError from the lookup fails the rule list, naming the rule")
    void otherLinkageErrorFailsTheLoad() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
        NoClassDefFoundError missingDependency = new NoClassDefFoundError("com/example/MissingDependency");

        RuleCompilationException thrown = assertThrows(RuleCompilationException.class,
                () -> withContextClassLoader(loaderThrowing(missingDependency), () -> {
                    engine.load(List.of(PRIME_RATE));
                    return null;
                }));

        assertEquals("prime-rate", thrown.getRuleName());
        assertTrue(Stream.iterate((Throwable) thrown, t -> t != null, Throwable::getCause)
                .anyMatch(t -> t == missingDependency), thrown.getMessage());
    }

    @Test
    @DisplayName("only the JVM's wrong-name message counts, and only on a NoClassDefFoundError")
    void wrongNameRecognized() {
        assertTrue(ExactNameClassLoader.isWrongName(new NoClassDefFoundError("applicant (wrong name: Applicant)")));
        assertFalse(ExactNameClassLoader.isWrongName(new NoClassDefFoundError("com/example/Missing")));
        assertFalse(ExactNameClassLoader.isWrongName(new NoClassDefFoundError()));
        assertFalse(ExactNameClassLoader.isWrongName(new LinkageError("applicant (wrong name: Applicant)")));
    }
}
