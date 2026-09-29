package io.github.brantunger.unruly;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small helpers that tests in any package of this source set, and of mvel's and the test kit's, would otherwise each
 * write again: running code with a context class loader, waiting for another thread, and throwing or casting what
 * the compiler can't see the type of.
 */
public final class TestSupport {

    private TestSupport() {
    }

    /**
     * Runs {@code action} with {@code loader} as the current thread's context class loader, and puts the previous one
     * back afterwards, whatever {@code action} throws.
     *
     * @param loader The context class loader while {@code action} runs
     * @param action The code to run
     * @param <T>    The type of what {@code action} returns
     * @return What {@code action} returned
     */
    public static <T> T withContextClassLoader(ClassLoader loader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Runs {@code action} with {@code loader} as the current thread's context class loader, as
     * {@link #withContextClassLoader(ClassLoader, Supplier)} does, for code that returns nothing.
     *
     * @param loader The context class loader while {@code action} runs
     * @param action The code to run
     */
    public static void withContextClassLoader(ClassLoader loader, Runnable action) {
        withContextClassLoader(loader, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Waits at most 10 seconds for {@code latch} to reach zero, and fails the test if it doesn't. An interrupt fails
     * the test too, with the thread's interrupt status set again.
     *
     * @param latch The latch to wait for
     */
    public static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * Checks {@code condition} every 5 milliseconds until it holds, and fails the test if it doesn't within
     * {@code seconds}.
     *
     * @param condition The condition to wait for
     * @param seconds   How long to wait, in seconds
     * @param what      What the condition means, for the failure's message
     * @throws InterruptedException if the thread is interrupted while it waits
     */
    public static void await(BooleanSupplier condition, int seconds, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting until " + what);
            Thread.sleep(5);
        }
    }

    /**
     * Throws {@code failure}, if it isn't {@code null}, whatever its type, as {@link #sneakyThrow} does.
     *
     * @param failure What to throw, or {@code null} to return
     */
    public static void throwIfSet(Throwable failure) {
        if (failure != null) {
            TestSupport.<RuntimeException>sneakyThrow(failure);
        }
    }

    /**
     * Throws any throwable, a checked one or a {@link Throwable} that is neither an {@link Exception} nor an
     * {@link Error} too, from code the compiler thinks throws nothing, as a language or a listener compiled apart can.
     *
     * @param throwable What to throw
     * @param <T>       The type the compiler takes it to be, such as {@link RuntimeException}
     * @throws T always: {@code throwable}, whatever its type
     */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    /**
     * The output object of an engine built with {@code HashMap::new}, as the map it is.
     *
     * @param output The output object
     * @return {@code output}, cast
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object output) {
        return (Map<String, Object>) output;
    }
}
