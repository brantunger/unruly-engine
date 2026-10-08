package io.github.brantunger.unruly.mvel;

import org.mvel2.ParserConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Run by {@link CallSitesTest} in a new JVM: asks a configuration for the class loader from one place until it throws,
 * as MVEL does on each round of a loop that never ends, deep in a stack, where a walk of the stack has no room (#1102).
 * The configuration is for MVEL's analysis, or with the system property {@value #RUN} set to {@code true}, for a run,
 * and allows {@value #CALLS} calls in all and {@value #CALLS_PER_SITE} from one place. It first asks until it throws
 * at the top of the stack, so the classes it uses, and the JDK's that walk the stack, are initialized, as preparing
 * MVEL does. Then, on a thread with a small stack, it finds where the stack ends, and asks again on a new
 * configuration at each depth from the end up, a frame at a time, until the limit for one place stops the calls. It
 * prints {@link #STOPS} and how many calls each configuration that wasn't cut short by an overflow made before it
 * threw, from the deepest, and {@link #THROWN} and the simple names of what they threw.
 */
final class DeepLoopLimitScenario {

    static final String RUN = "scenario.run";
    static final String STOPS = "SCENARIO stops: ";
    static final String THROWN = "SCENARIO thrown: ";
    static final long CALLS = 500;
    static final long CALLS_PER_SITE = 10;

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it asks at, at most. */
    private static final int MAX_DEPTHS = 100_000;
    private static final Imports IMPORTS = new Imports(Set.of(), Set.of(),
            DeepLoopLimitScenario.class.getClassLoader());

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static ParserConfiguration configuration;
    private static Error thrown;

    private DeepLoopLimitScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        boolean run = Boolean.getBoolean(RUN);
        configuration = limited(run);
        askedUntilStopped();
        List<Long> stops = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        Thread deep = new Thread(null, () -> {
            for (int above = 0; above < MAX_DEPTHS; above++) {
                deepest = 0;
                target = -1;
                try {
                    descend(0, 1, 2);
                } catch (StackOverflowError expected) {
                    // The end of the stack is found.
                }
                configuration = limited(run);
                target = deepest - above;
                thrown = null;
                try {
                    descend(0, 1, 2);
                } catch (StackOverflowError cutShort) {
                    // Too deep for the calls, or for a walk of the stack before #1102: the next depth is tried.
                    continue;
                }
                long calls = Imports.classLoaderCalls(configuration);
                stops.add(calls);
                names.add(thrown.getClass().getSimpleName());
                if (calls < CALLS) {
                    return;
                }
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        System.out.println(STOPS + stops);
        System.out.println(THROWN + names);
    }

    // A configuration for MVEL's analysis, or for a run, started, with the scenario's limits.
    private static ParserConfiguration limited(boolean run) {
        if (run) {
            ParserConfiguration limited = IMPORTS.newRunConfiguration(CALLS, CALLS_PER_SITE);
            Imports.startRun(limited);
            return limited;
        }
        ParserConfiguration limited = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(limited, CALLS, CALLS_PER_SITE);
        return limited;
    }

    // Asks for the class loader from one place until the configuration throws, and keeps what it threw.
    private static void askedUntilStopped() {
        while (true) {
            try {
                configuration.getClassLoader();
            } catch (Imports.AnalysisLoop | Imports.RunLoop e) {
                thrown = e;
                return;
            }
        }
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it asks.
    private static long descend(int depth, long a, long b) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            askedUntilStopped();
            return a;
        }
        return descend(depth + 1, b, a + 1) + a + b;
    }
}
