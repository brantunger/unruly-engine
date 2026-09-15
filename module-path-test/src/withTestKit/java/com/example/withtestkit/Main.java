package com.example.withtestkit;

import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.util.Map;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // The contexts are the engine's records, in the core package that the core module exports only to the kit.
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of("x", 2));
        CompiledCondition condition = new MvelExpressionLanguage().newCompiler(LanguageTestContexts.compile())
                .compileCondition("x > 1");
        check(Boolean.TRUE.equals(condition.evaluate(evaluation)), "the condition didn't evaluate to true");

        Module core = evaluation.getClass().getModule();
        String corePackage = "io.github.brantunger.unruly.core";
        check("io.github.brantunger.unruly.core".equals(core.getName()), "the context is in the module " + core.getName());
        check(core.isExported(corePackage, LanguageTestContexts.class.getModule()), "core isn't exported to the kit");
        check(!core.isExported(corePackage, Main.class.getModule()), "core is exported to the application");

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(
                LauncherDiscoveryRequestBuilder.request().selectors(selectClass(MvelContractTest.class)).build(),
                listener);
        TestExecutionSummary summary = listener.getSummary();
        check(summary.getTestsFoundCount() > 0 && summary.getTestsSucceededCount() == summary.getTestsFoundCount(),
                "the contract test found " + summary.getTestsFoundCount() + " tests and " + summary.getTestsSucceededCount()
                        + " passed: " + summary.getFailures().stream().map(failure -> failure.getException().toString())
                        .toList());
        System.out.println("Module path with the test kit: " + summary.getTestsSucceededCount()
                + " contract checks passed");
    }

    private static void check(boolean passed, String failure) {
        if (!passed) {
            throw new IllegalStateException(failure);
        }
    }
}
