package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JVM holds a class loader's lock through a whole {@code Class.forName} lookup unless the loader is registered as
 * parallel-capable. MVEL looks classes up through the rule list's {@link ExactNameClassLoader} while compiling and
 * while running, so without the registration every thread using the rule list waits for the lookup in progress, and a
 * virtual thread waiting there stays pinned to its carrier.
 */
@DisplayName("class lookups through a rule list's class loader don't wait for each other")
class ParallelClassLookupTest {

    private static final String SLOW = "com.example.Slow";

    /** An application class loader whose lookup of {@link #SLOW} waits until {@code release} opens. */
    private static ClassLoader slowFor(CountDownLatch inside, CountDownLatch release) {
        return new ClassLoader(ParallelClassLookupTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(SLOW)) {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    /** Looks {@code name} up on a new thread; the result completes once the lookup finds no class, as expected. */
    private static CompletableFuture<Void> lookUp(Thread.Builder thread, Imports imports, String name) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        thread.start(() -> {
            try {
                Class.forName(name, false, imports.classLoader());
                result.completeExceptionally(new AssertionError("found a class named " + name));
            } catch (ClassNotFoundException expected) {
                result.complete(null);
            } catch (Throwable unexpected) {
                result.completeExceptionally(unexpected);
            }
        });
        return result;
    }

    private static void assertLookupsDontWait(Supplier<Thread.Builder> threads) throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Imports imports = new Imports(Set.of(), Set.of(), slowFor(inside, release));
        CompletableFuture<Void> slow = lookUp(threads.get(), imports, SLOW);
        try {
            assertTrue(inside.await(10, TimeUnit.SECONDS), "the slow lookup never started");
            CompletableFuture<Void> other = lookUp(threads.get(), imports, "applicant");
            try {
                other.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("a lookup waited for another thread's lookup through the same class loader");
            }
        } finally {
            release.countDown();
        }
        slow.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("on platform threads")
    void platformThreads() throws Exception {
        assertLookupsDontWait(Thread::ofPlatform);
    }

    @Test
    @DisplayName("on virtual threads")
    void virtualThreads() throws Exception {
        assertLookupsDontWait(Thread::ofVirtual);
    }
}
