package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.runCheck;
import static io.github.brantunger.unruly.api.language.ContractKitChecksTest.withSessions;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A language's contract test can configure the engines the kit builds, and the context its one check outside an
 * engine compiles with, so that a language that needs declared facts, imports or options to compile can be run through
 * the kit at all (#658).
 */
@DisplayName("a language's contract test configures the engines the contract test kit builds")
class ContractKitConfigureTest {

    /** The option {@link #configured} sets on every engine, and the context it compiles with. */
    private static final Map<String, String> CONFIGURED = Map.of("configured", "yes");

    /** The facts the kit's checks read, as a typed language declares them. */
    private static final Map<String, Class<?>> CHECKS_FACTS = Map.of("x", Object.class, "y", Object.class,
            "applicant", Object.class);

    /** The checks the kit has, found as JUnit finds them. */
    private static List<Method> checks() {
        return Arrays.stream(ExpressionLanguageContractTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class)).toList();
    }

    /** A compile context with {@code options} and {@code declaredFacts}, and nothing else. */
    private static CompileContext context(Map<String, String> options, Map<String, Class<?>> declaredFacts) {
        return LanguageTestContexts.compile(Set.of(), Set.of(), ContractKitConfigureTest.class.getClassLoader(),
                Object.class, options, declaredFacts, false);
    }

    /**
     * Wraps a language so that it counts the compilers it creates, and records in {@code unconfigured} each one whose
     * context doesn't have the {@link #CONFIGURED} option.
     */
    private static ExpressionLanguage watchingContexts(ExpressionLanguage language, AtomicInteger compilers,
                                                       List<CompileContext> unconfigured) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                compilers.incrementAndGet();
                if (!CONFIGURED.equals(context.options())) {
                    unconfigured.add(context);
                }
                return language.newCompiler(context);
            }
        };
    }

    /**
     * Wraps a language so that it compiles an expression only when each of the checks' facts it names was declared,
     * as a statically typed language that needs each fact's type does.
     */
    private static ExpressionLanguage typed(ExpressionLanguage language) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        typeCheck(expression);
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        typeCheck(expression);
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }

                    private void typeCheck(Expression expression) {
                        for (String fact : CHECKS_FACTS.keySet()) {
                            if (Pattern.compile("\\b" + fact + "\\b").matcher(expression.text()).find()
                                    && !context.declaredFacts().containsKey(fact)) {
                                throw new IllegalArgumentException("fact '" + fact + "' has no declared type");
                            }
                        }
                    }
                };
            }
        };
    }

    /** Wraps a language so that it counts the conditions it evaluates. */
    private static ExpressionLanguage countingEvaluations(ExpressionLanguage language, AtomicInteger evaluated) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        return (evaluation, session) -> {
                            evaluated.incrementAndGet();
                            return condition.evaluate(evaluation, session);
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }
                };
            }
        };
    }

    /** A contract test for {@code language} whose engines, and the context it compiles with, have the option. */
    private static ExpressionLanguageContractTest configured(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                CONFIGURED.forEach((key, value) -> builder.option(language.name(), key, value));
            }

            @Override
            protected CompileContext compileContext() {
                return context(CONFIGURED, Map.of());
            }
        };
    }

    /** A contract test for {@code language} whose engines declare the checks' facts, and whose context declares x. */
    private static ExpressionLanguageContractTest declaring(ExpressionLanguage language) {
        return new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return language;
            }

            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.facts(CHECKS_FACTS);
            }

            @Override
            protected CompileContext compileContext() {
                return context(Map.of(), Map.of("x", Object.class));
            }
        };
    }

    /** Runs every one of the kit's checks on the contract test, and returns the name of each that failed. */
    private static List<String> failedChecks(ExpressionLanguageContractTest test) {
        List<String> failed = new ArrayList<>();
        for (Method check : checks()) {
            try {
                runCheck(test, check.getName());
            } catch (TestAbortedException e) {
                // Skipped by one of the toy's hooks, as it is in the toy's own contract test.
            } catch (Throwable e) {
                failed.add(check.getName() + ": " + e);
            }
        }
        return failed;
    }

    @Test
    @DisplayName("configure() is applied to every engine every check builds, and compileContext() is what the check"
            + " outside an engine compiles with")
    void everyEngineConfigured() {
        List<String> unconfigured = new ArrayList<>();
        List<String> createdNothing = new ArrayList<>();
        List<Method> checks = checks();
        for (Method check : checks) {
            AtomicInteger compilers = new AtomicInteger();
            List<CompileContext> contexts = new CopyOnWriteArrayList<>();
            boolean skipped = false;
            try {
                runCheck(configured(watchingContexts(new ToyExpressionLanguage(), compilers, contexts)),
                        check.getName());
            } catch (TestAbortedException e) {
                skipped = true;
            } catch (Throwable e) {
                // The check's verdict isn't what this is about.
            }
            if (!contexts.isEmpty()) {
                unconfigured.add(check.getName() + ": " + contexts.size() + " of " + compilers + " compilers");
            }
            if (!skipped && compilers.get() == 0) {
                createdNothing.add(check.getName());
            }
        }

        assertEquals(18, checks.size(), "the kit's checks");
        assertEquals(List.of(), unconfigured, "checks that compiled without configure() or compileContext()");
        assertEquals(List.of(), createdNothing, "checks that ran and created no compiler");
    }

    @Test
    @DisplayName("a language that compiles only against declared facts fails the kit's checks, and passes them once"
            + " its contract test declares the facts")
    void typedLanguagePassesOnceConfigured() {
        ExpressionLanguageContractTest unconfigured = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return typed(new ToyExpressionLanguage());
            }
        };

        // Without the declarations, it fails every check that compiles an expression that names a fact.
        List<String> failedUnconfigured = failedChecks(unconfigured);
        assertTrue(failedUnconfigured.size() >= 10, () -> String.join("\n", failedUnconfigured));
        assertTrue(failedUnconfigured.stream().anyMatch(failed -> failed.startsWith("evaluateAgreesWithDetail: ")
                && failed.contains("fact 'x' has no declared type")), () -> String.join("\n", failedUnconfigured));

        assertEquals(List.of(), failedChecks(declaring(typed(new ToyExpressionLanguage()))));
    }

    @Test
    @DisplayName("a configure() that sets copiesAtLoad doesn't change the copies the checks that need them make when"
            + " the rules load")
    void copiesAtLoadKept() throws Throwable {
        Map<String, Integer> made = new LinkedHashMap<>();
        for (String check : List.of("copiesAtLoad", "sessionsClosed")) {
            // Counted on the check's own thread, where the rules load: runs on other threads make copies of their own.
            Thread checkThread = Thread.currentThread();
            AtomicInteger sessions = new AtomicInteger();
            ExpressionLanguage language = withSessions(new ToyExpressionLanguage(), () -> {
                if (Thread.currentThread() == checkThread) {
                    sessions.incrementAndGet();
                }
                return new Session() {
                };
            });

            runCheck(new ToyExpressionLanguageContractTest() {
                @Override
                protected ExpressionLanguage language() {
                    return language;
                }

                @Override
                protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                    builder.copiesAtLoad(1);
                }
            }, check);
            made.put(check, sessions.get());
        }

        assertEquals(Map.of("copiesAtLoad", 2, "sessionsClosed", 2), made, "sessions made when the rules loaded");
    }

    @Test
    @DisplayName("a configure() that requires the declared facts fails the checks before the language is asked, as"
            + " its documentation says")
    void requiredFactsFailTheChecks() {
        AtomicInteger evaluated = new AtomicInteger();
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return countingEvaluations(new ToyExpressionLanguage(), evaluated);
            }

            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.facts(CHECKS_FACTS).requireDeclaredFacts();
            }
        };

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> runCheck(test, "conditionReadsFacts"));

        assertTrue(failure.getMessage().endsWith(" was declared, but the run didn't supply it, and this engine was"
                + " built with requireDeclaredFacts()"), failure.getMessage());
        assertEquals(0, evaluated.get(), "conditions evaluated");
    }

    @Test
    @DisplayName("a configure() that declares the fact name the language can't refer to fails the checks' loads, and"
            + " decides a check that expects a load to fail for a reason unrelated to the language")
    void unusableNameDeclaredFailsTheLoads() {
        ExpressionLanguageContractTest test = new ToyExpressionLanguageContractTest() {
            @Override
            protected ExpressionLanguage language() {
                return rejectingDashes(new ToyExpressionLanguage());
            }

            @Override
            protected String unusableFactName() {
                return "x-y";
            }

            @Override
            protected void configure(RulesEngineBuilder<Map<String, Object>> builder) {
                builder.fact(unusableFactName(), Integer.class);
            }
        };

        RuleCompilationException failure = assertThrows(RuleCompilationException.class,
                () -> runCheck(test, "conditionReadsFacts"));

        assertEquals("Declared fact 'x-y' can't be used: a fact name can't contain '-': x-y", failure.getMessage());
        // It passes only because the toy rejects the assignment when it compiles the rule, so the rule's failure
        // comes first in the load's failure, and names the rule and its condition.
        assertDoesNotThrow(() -> runCheck(test, "conditionAssignmentRejected"));
    }

    /** Wraps a language so that it refuses a fact name with a {@code -} in it. */
    private static ExpressionLanguage rejectingDashes(ExpressionLanguage language) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return compiler.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return compiler.compileAction(expression);
                    }

                    @Override
                    public Session newSession() {
                        return compiler.newSession();
                    }

                    @Override
                    public void checkFactName(String name) {
                        if (name.contains("-")) {
                            throw new IllegalArgumentException("a fact name can't contain '-': " + name);
                        }
                    }
                };
            }
        };
    }
}
