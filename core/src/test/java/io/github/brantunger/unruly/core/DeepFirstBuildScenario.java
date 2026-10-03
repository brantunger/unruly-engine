package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Run by {@link DeepFirstBuildTest} in a new JVM, so the build it makes deep in a stack is the JVM's first. On a thread
 * with a small stack, it finds where the stack ends, then builds an engine at each depth from the end up, a frame at a
 * time, until a build doesn't overflow, and prints what the builds threw. The test keeps the recursion that finds the
 * end from being compiled, so its frames are the same size each time. {@value #MODE} chooses what it builds.
 *
 * <ul>
 *     <li>{@code settings}: an engine whose default language isn't one of its languages, so a build that gets
 *     through the check of its stack's room throws {@link IllegalStateException}. It prints {@link #CHECKS_FAILED} and
 *     how many builds the check failed, {@link #LAST_OVERFLOW} and where the last build that overflowed overflowed,
 *     {@link #CHECK} if in the check, then {@link #DEEP} and what the first build that didn't overflow threw, then
 *     {@link #SHALLOW} and what the same build throws at the top of the main thread's stack.</li>
 *     <li>{@code prepare}: once an engine has been built at the top of the stack, an engine with a language of a class
 *     no engine has prepared. It prints {@link #CHECKS_FAILED} and how many builds the check failed,
 *     {@link #PREPARED_WHEN_CHECK_FAILED} and how many times those builds prepared the language, and {@link #PREPARED}
 *     and how many times all of them did, the one that got through included.</li>
 * </ul>
 */
final class DeepFirstBuildScenario {

    /** The system property that chooses what the scenario builds. */
    static final String MODE = "deep.build";
    static final String LAST_OVERFLOW = "SCENARIO last overflow: ";
    static final String CHECK = "the check";
    static final String DEEP = "SCENARIO deep: ";
    static final String SHALLOW = "SCENARIO shallow: ";
    static final String CHECKS_FAILED = "SCENARIO checks failed: ";
    static final String PREPARED_WHEN_CHECK_FAILED = "SCENARIO prepared when the check failed: ";
    static final String PREPARED = "SCENARIO prepared: ";

    /** Small, so the stack's end is near, but well above the smallest stack HotSpot allows on any platform. */
    private static final long STACK_BYTES = 512 * 1024;
    /** How many depths it builds at, at most. */
    private static final int MAX_BUILDS = 100_000;

    // Linked here, at the top of the stack, as the scenario's own lambdas are: an application's lambda linked deep
    // could leave one of the JDK's classes unusable, which the engine can't help.
    private static final Supplier<Object> OUTPUT = Object::new;

    // The deep thread's own state: only it reads or writes these, so its last frame needs no argument to find its
    // call.
    private static int deepest;
    private static int target;
    private static Runnable build;
    private static Throwable thrown;

    private DeepFirstBuildScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        if ("prepare".equals(System.getProperty(MODE))) {
            prepare();
        } else {
            settings();
        }
    }

    private static void settings() throws InterruptedException {
        ToyExpressionLanguage toy = new ToyExpressionLanguage();
        build = () -> RulesEngineBuilder.firstMatch(OUTPUT).language(toy).defaultLanguage("missing").build();
        String[] lastOverflow = {"none"};
        AtomicInteger checksFailed = new AtomicInteger();
        Throwable got = fromTheEnd(overflow -> {
            if (thrownByCheck(overflow)) {
                checksFailed.incrementAndGet();
                lastOverflow[0] = CHECK;
            } else {
                lastOverflow[0] = String.valueOf(overflow.getStackTrace().length > 0 ? overflow.getStackTrace()[0]
                        : null);
            }
        });
        System.out.println(CHECKS_FAILED + checksFailed);
        System.out.println(LAST_OVERFLOW + lastOverflow[0]);
        System.out.println(DEEP + got);
        try {
            build.run();
            System.out.println(SHALLOW + "nothing");
        } catch (RuntimeException | Error e) {
            System.out.println(SHALLOW + e);
        }
    }

    private static void prepare() throws InterruptedException {
        RulesEngineBuilder.firstMatch(OUTPUT).language(new LanguagePrepareTest.Preparing("warm-up")).build()
                .close();
        // A class of its own, which no engine has prepared a language of.
        LanguagePrepareTest.Preparing language = new LanguagePrepareTest.Preparing("fresh") {
        };
        AtomicInteger checksFailed = new AtomicInteger();
        AtomicInteger preparedWhenCheckFailed = new AtomicInteger();
        AtomicInteger before = new AtomicInteger();
        build = () -> {
            before.set(language.prepared());
            RulesEngineBuilder.firstMatch(OUTPUT).language(language).build().close();
        };
        fromTheEnd(overflow -> {
            if (thrownByCheck(overflow)) {
                checksFailed.incrementAndGet();
                preparedWhenCheckFailed.addAndGet(language.prepared() - before.get());
            }
        });
        System.out.println(CHECKS_FAILED + checksFailed);
        System.out.println(PREPARED_WHEN_CHECK_FAILED + preparedWhenCheckFailed);
        System.out.println(PREPARED + language.prepared());
    }

    /** What a build that overflowed is shown to. */
    private interface Overflows {
        void overflowed(StackOverflowError overflow);
    }

    /**
     * Builds at each depth from the end of a new thread's stack up, until a build doesn't overflow. The end is found
     * again before each build, with the same recursion, so it is where the JIT's compiling of it last left it.
     *
     * @return What the first build that didn't overflow threw, or {@code null} if it threw nothing
     */
    private static Throwable fromTheEnd(Overflows overflows) throws InterruptedException {
        Throwable[] got = new Throwable[1];
        Thread deep = new Thread(null, () -> {
            for (int above = 0; above < MAX_BUILDS; above++) {
                deepest = 0;
                target = -1;
                try {
                    descend(0, 1, 2, 3, 4);
                } catch (StackOverflowError expected) {
                    // The end of the stack is found.
                }
                target = deepest - above;
                thrown = null;
                try {
                    descend(0, 1, 2, 3, 4);
                } catch (StackOverflowError beforeTheTarget) {
                    // The recursion's own frames grew, as the JIT compiled it differently: the next depth is tried.
                    continue;
                }
                if (!(thrown instanceof StackOverflowError overflow)) {
                    got[0] = thrown;
                    return;
                }
                overflows.overflowed(overflow);
            }
        }, "deep", STACK_BYTES);
        deep.start();
        deep.join();
        return got[0];
    }

    private static boolean thrownByCheck(Throwable e) {
        StackTraceElement[] frames = e.getStackTrace();
        return frames.length > 0 && frames[0].getClassName().equals(StackHeadroom.class.getName());
    }

    // Recurses to the end of the stack, recording how deep it got, or to the target depth, where it builds.
    private static long descend(int depth, long a, long b, long c, long d) {
        if (depth > deepest) {
            deepest = depth;
        }
        if (depth == target) {
            try {
                build.run();
            } catch (Throwable t) {
                thrown = t;
            }
            return a;
        }
        return descend(depth + 1, b, c, d, a + 1) + a + b + c + d;
    }
}
