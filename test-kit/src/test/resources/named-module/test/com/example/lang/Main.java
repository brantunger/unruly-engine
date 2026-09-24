package com.example.lang;

import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Prints what a language's own tests see when they're patched into its module, and the kit is on the class path. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // What a language's unit test calls first. The kit, in the unnamed module, builds the engine's contexts from
        // the core package that core exports only to the kit's named module.
        try {
            LanguageTestContexts.compile();
            System.out.println("compile(): ok");
        } catch (IllegalAccessError e) {
            System.out.println("compile(): " + e);
        }

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(
                LauncherDiscoveryRequestBuilder.request().selectors(selectClass(TinyContractTest.class)).build(),
                listener);
        TestExecutionSummary summary = listener.getSummary();
        System.out.println("found " + summary.getTestsFoundCount() + ", failed " + summary.getTotalFailureCount());
        summary.getFailures().forEach(failure -> System.out.println("FAILED "
                + failure.getTestIdentifier().getLegacyReportingName() + ": " + failure.getException()));
        // Whatever threads a check left running, the JVM ends here, so the test doesn't wait out its timeout.
        System.exit(0);
    }
}
