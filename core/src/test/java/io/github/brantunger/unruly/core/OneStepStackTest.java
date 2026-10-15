package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1220: the stack a rule set keeps its idle copies and its open compilers in, each of whose changes is one
 * compare-and-set, the last step of the call: last in first out, counted in one read, and unchanged by an add or a take
 * that fails before it, as one that runs out of stack does.
 */
@DisplayName("OneStepStack")
class OneStepStackTest {

    @AfterEach
    void clearFaults() {
        Faults.clear();
    }

    private static List<String> walk(Iterator<String> items) {
        List<String> walked = new ArrayList<>();
        items.forEachRemaining(walked::add);
        return walked;
    }

    @Test
    @DisplayName("items are taken last in first out, and an empty stack gives null")
    void lastInFirstOut() {
        OneStepStack<String> stack = new OneStepStack<>();
        stack.add("a");
        stack.add("b");
        assertTrue(stack.offer("c"));

        assertEquals("c", stack.poll());
        assertEquals("b", stack.poll());
        assertEquals("a", stack.poll());
        assertNull(stack.poll());
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("size, peek and the iterator read the stack from the top, without changing it")
    void sizePeekAndIterator() {
        OneStepStack<String> stack = new OneStepStack<>();
        assertEquals(0, stack.size());
        assertNull(stack.peek());
        Iterator<String> empty = stack.iterator();
        assertFalse(empty.hasNext());
        assertThrows(NoSuchElementException.class, empty::next);

        stack.add("a");
        stack.add("b");
        stack.add("c");

        assertEquals(3, stack.size());
        assertEquals("c", stack.peek());
        Iterator<String> items = stack.iterator();
        assertEquals("c", stack.poll());
        assertEquals(List.of("c", "b", "a"), walk(items), "as the stack was when the iterator was made");
        assertThrows(NoSuchElementException.class, items::next);
        assertEquals(List.of("b", "a"), walk(stack.iterator()));
        assertEquals(2, stack.size());
    }

    @Test
    @DisplayName("a stack made from a list gives its items in the list's order")
    void fromAList() {
        OneStepStack<String> stack = new OneStepStack<>(List.of("a", "b", "c"));

        assertEquals(3, stack.size());
        assertEquals("a", stack.poll());
        assertEquals("b", stack.poll());
        assertEquals("c", stack.poll());
        assertNull(stack.poll());
        assertEquals(0, new OneStepStack<String>(List.of()).size());
    }

    @Test
    @DisplayName("a null item is refused, and the stack left unchanged")
    void nullRefused() {
        OneStepStack<String> stack = new OneStepStack<>(List.of("a"));

        assertThrows(NullPointerException.class, () -> stack.offer(null));

        assertEquals(List.of("a"), walk(stack.iterator()));
    }

    @Test
    @DisplayName("an add that finds the top changed after it read it reads it again, and keeps both items")
    void addRetriesWhenTheTopChanged() {
        OneStepStack<String> stack = new OneStepStack<>(List.of("a"));
        // Another add has changed the top when this one compares and sets it, so the compare-and-set fails.
        Faults.watch(Faults.Step.STACK_TOP_READ, () -> stack.add("b"));

        stack.add("c");

        assertEquals(List.of("c", "b", "a"), walk(stack.iterator()));
        assertEquals(3, stack.size());
    }

    @Test
    @DisplayName("a take that finds the top changed after it read it reads it again, and never takes an item twice")
    void takeRetriesWhenTheTopChanged() {
        OneStepStack<String> stack = new OneStepStack<>(List.of("a", "b"));
        AtomicReference<String> other = new AtomicReference<>();
        Faults.watch(Faults.Step.STACK_TOP_READ, () -> other.set(stack.poll()));

        String taken = stack.poll();

        assertEquals("a", other.get(), "the take that came in between");
        assertEquals("b", taken);
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("an add that fails before it changes the stack leaves it unchanged, and the item with the caller")
    void failedAddLeavesTheStackUnchanged() {
        OneStepStack<String> stack = new OneStepStack<>(List.of("a"));
        StackOverflowError overflow = new StackOverflowError();
        Faults.inject(Faults.Step.STACK_CHANGING, 1, overflow);

        assertSame(overflow, assertThrows(StackOverflowError.class, () -> stack.add("b")));

        assertEquals(List.of("a"), walk(stack.iterator()));
        assertEquals(1, stack.size());
    }

    @Test
    @DisplayName("a take that fails before it changes the stack leaves the item on it, for the next take")
    void failedTakeLeavesTheItem() {
        OneStepStack<String> stack = new OneStepStack<>(List.of("a", "b"));
        StackOverflowError overflow = new StackOverflowError();
        Faults.inject(Faults.Step.STACK_CHANGING, 1, overflow);

        assertSame(overflow, assertThrows(StackOverflowError.class, stack::poll));

        assertEquals(2, stack.size());
        assertEquals("a", stack.poll(), "the item it failed to take");
    }
}
