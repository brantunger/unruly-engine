package com.example.withtestkit;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class Main {

    /** The kit's checks. */
    private static final int CHECKS = 30;

    /**
     * The checks MvelContractTest skips. MVEL has no built-in object or global of its own for sharedStateStaysLocal to
     * change. Its actions can still change a class's static state, named by the class's full name, since MVEL runs
     * them without a sandbox, and that check doesn't cover it. MVEL reserves output, the name its actions bind the
     * output object to, so the engine rejects a fact by that name itself, and unreservedOutputReadAsFact has nothing to
     * check.
     */
    private static final Set<String> SKIPPED = Set.of("sharedStateStaysLocal", "unreservedOutputReadAsFact");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // The contexts are the engine's records, in the core package that the core module exports only to the kit.
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of("x", 2));
        ExpressionCompiler compiler = new MvelExpressionLanguage().newCompiler(LanguageTestContexts.compile());
        CompiledCondition condition = compiler.compileCondition(new Expression("r", ExpressionKind.CONDITION, "x > 1"));
        check(Boolean.TRUE.equals(condition.evaluate(evaluation, compiler.newSession())),
                "the condition didn't evaluate to true");

        Module core = evaluation.getClass().getModule();
        String corePackage = "io.github.brantunger.unruly.core";
        check("io.github.brantunger.unruly.core".equals(core.getName()), "the context is in the module " + core.getName());
        check(core.isExported(corePackage, LanguageTestContexts.class.getModule()), "core isn't exported to the kit");
        check(!core.isExported(corePackage, Main.class.getModule()), "core is exported to the application");

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Set<String> skipped = new TreeSet<>();
        TestExecutionListener skips = new TestExecutionListener() {
            @Override
            public void executionSkipped(TestIdentifier test, String reason) {
                skipped.add(name(test));
            }

            @Override
            public void executionFinished(TestIdentifier test, TestExecutionResult result) {
                if (test.isTest() && result.getStatus() == TestExecutionResult.Status.ABORTED) {
                    skipped.add(name(test));
                }
            }
        };
        LauncherFactory.create().execute(
                LauncherDiscoveryRequestBuilder.request().selectors(selectClass(MvelContractTest.class)).build(),
                listener, skips);
        TestExecutionSummary summary = listener.getSummary();
        // Each check MVEL can't run is named, so that a check that starts being skipped fails here.
        check(summary.getTestsFoundCount() == CHECKS && summary.getTotalFailureCount() == 0
                        && summary.getTestsSucceededCount() == CHECKS - SKIPPED.size() && skipped.equals(SKIPPED),
                "the contract test found " + summary.getTestsFoundCount() + " tests, "
                        + summary.getTestsSucceededCount() + " passed, and skipped " + skipped + ": "
                        + summary.getFailures().stream()
                        .map(failure -> failure.getException().toString()).toList());
        System.out.println("Module path with the test kit: " + summary.getTestsSucceededCount()
                + " contract checks passed, and " + List.copyOf(skipped) + " skipped");
    }

    private static String name(TestIdentifier test) {
        return test.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast)
                .map(MethodSource::getMethodName).orElse(test.getDisplayName());
    }

    private static void check(boolean passed, String failure) {
        if (!passed) {
            throw new IllegalStateException(failure);
        }
    }
}
