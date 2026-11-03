package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1280: the rule sets an engine still has to retire are an intrusive list, so adding, claiming, retiring and
 * settling them allocates nothing of the list's own: a {@code load()} or {@code close()} deep in a run's stack retires
 * with no allocation beyond what each rule set's own {@code retire()} makes.
 */
@DisplayName("#1280: the rule sets still to retire are claimed, retired and settled without allocating")
class RetireQueueTest {

    // Rounds of what a load() or close() does with the list: adds the rule sets, claims them and retires them, each
    // retired for good already, so retire() returns at once and the work left is the list's own. An indexed loop
    // over an array, so the rounds themselves allocate nothing, not even an iterator.
    private static int retireAll(RetireQueue queue, Object lifecycle, CopyPool[] pools, int rounds) {
        int retired = 0;
        for (int i = 0; i < rounds; i++) {
            long claim;
            synchronized (lifecycle) {
                claim = queue.newClaim();
                for (int p = 0; p < pools.length; p++) {
                    queue.add(pools[p]);
                }
                queue.claim(claim);
            }
            if (queue.retireClaimed(claim) == null) {
                retired++;
            }
        }
        return retired;
    }

    @Test
    @DisplayName("#1280: claiming, retiring and settling the rule sets still to retire allocates nothing of the list's"
            + " own")
    void retiresWithoutAllocating() {
        Object lifecycle = new Object();
        RetireQueue queue = new RetireQueue(lifecycle);
        CopyPool[] pools = {TestRuleSets.ruleSet(List.of(), Map.of()).build(),
            TestRuleSets.ruleSet(List.of(), Map.of()).build()};
        for (CopyPool pool : pools) {
            assertNull(pool.retire(), "retired with no fatal error");
            assertTrue(pool.retiredForGood(), "retired for good, so a later retire() returns at once");
        }
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int rounds = 100_000;
        // Warmed up first, so class loading and the first compilations aren't counted.
        retireAll(queue, lifecycle, pools, rounds);

        long before = threads.getCurrentThreadAllocatedBytes();
        int retired = retireAll(queue, lifecycle, pools, rounds);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertEquals(rounds, retired);
        // An object a round would be 16 bytes a round, megabytes for these rounds. The bound leaves room for what the
        // measurement itself allocates, and is under one byte a round.
        assertTrue(allocated < rounds,
                allocated + " bytes for " + rounds + " rounds of " + pools.length + " rule sets");
    }
}
