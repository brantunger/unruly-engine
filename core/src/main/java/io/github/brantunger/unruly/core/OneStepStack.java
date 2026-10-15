package io.github.brantunger.unruly.core;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A thread-safe stack, last in first out, that a rule set keeps its idle copies and its open compilers in, each of
 * whose changes is one compare-and-set, the last step of the call that makes it. Adding or taking an item that fails,
 * as one that runs out of stack deep in another run's stack can, fails before the stack changes, and leaves the item
 * in the caller's hands; once the stack has changed, nothing is left to fail but returning. The JDK's
 * {@code ConcurrentLinkedQueue}, which a rule set used before, takes an item in one compare-and-set and moves its head
 * in another, so a JVM that links that second call site deep in a stack could overflow there, once the item was taken
 * and before the caller had it, and lose a copy or a compiler with it open (#1217, #1220). Its offer changed too, as
 * it links the item before it moves its tail in a second compare-and-set, so an overflow at the tail move left a copy
 * in the queue while the caller, who saw the failure, closed it, and it was later lent closed or closed again.
 *
 * <p>
 * Each item is held in a node of its own, made new for each addition and never changed, which records the size of the
 * stack it tops, so counting the items is one read, and a node taken and added again is never mistaken for the one
 * read before. An iterator walks the items from the top as they were when it started, and can't remove them.
 * </p>
 *
 * @param <E> The type of the items, which can't be {@code null}
 */
final class OneStepStack<E> extends AbstractQueue<E> {

    // Changes top. A field updater, as RuleSet's RETIRING, whose compare-and-set checks the stack and the node it's
    // given before it changes anything, so it can't fail once the stack has changed. Raw, as newUpdater makes it, and
    // of this class, not of Queue, as it changes this class's own field, whatever PMD says.
    @SuppressWarnings({"rawtypes", "PMD.LooseCoupling"})
    private static final AtomicReferenceFieldUpdater<OneStepStack, Node> TOP =
            AtomicReferenceFieldUpdater.newUpdater(OneStepStack.class, Node.class, "top");

    // The node of the item taken next, or null when the stack is empty. Changed only through TOP once it's made.
    private volatile Node<E> top;

    /** Creates an empty stack. */
    OneStepStack() {
    }

    /**
     * Creates a stack of {@code items}, from which they are taken in the list's order: the first is on top.
     *
     * @param items The items, none {@code null}
     */
    OneStepStack(List<? extends E> items) {
        // Plain writes from the last item up: no other thread can see the stack yet.
        Node<E> node = null;
        for (int index = items.size() - 1; index >= 0; index--) {
            node = new Node<>(Objects.requireNonNull(items.get(index)), node);
        }
        top = node;
    }

    /**
     * Adds an item on top of the stack.
     *
     * @param item The item
     * @return {@code true}
     * @throws NullPointerException if {@code item} is {@code null}, before the stack changes
     */
    @Override
    public boolean offer(E item) {
        Objects.requireNonNull(item);
        Faults.at(Faults.Step.STACK_CHANGING);
        Node<E> was;
        Node<E> node;
        do {
            was = top;
            node = new Node<>(item, was);
            Faults.reached(Faults.Step.STACK_TOP_READ);
        } while (!TOP.compareAndSet(this, was, node));
        return true;
    }

    /**
     * Takes the item on top of the stack.
     *
     * @return The item, or {@code null} if the stack is empty
     */
    @Override
    public E poll() {
        Faults.at(Faults.Step.STACK_CHANGING);
        Node<E> was;
        do {
            was = top;
            if (was == null) {
                return null;
            }
            Faults.reached(Faults.Step.STACK_TOP_READ);
        } while (!TOP.compareAndSet(this, was, was.next));
        return was.item;
    }

    /**
     * Returns the item on top of the stack, without taking it.
     *
     * @return The item, or {@code null} if the stack is empty
     */
    @Override
    public E peek() {
        Node<E> was = top;
        return was == null ? null : was.item;
    }

    /**
     * Returns how many items the stack holds, in one read.
     *
     * @return The number of items
     */
    @Override
    public int size() {
        Node<E> was = top;
        return was == null ? 0 : was.size;
    }

    /**
     * Returns an iterator over the items from the top, as they were when it was made, which can't remove them.
     *
     * @return The iterator
     */
    @Override
    public Iterator<E> iterator() {
        return new Walk<>(top);
    }

    /**
     * One item and the nodes below it, never changed once made.
     *
     * @param <E> The type of the item
     */
    static final class Node<E> {
        private final E item;
        private final Node<E> next;
        // The number of items in the stack this node tops.
        private final int size;

        Node(E item, Node<E> next) {
            this.item = item;
            this.next = next;
            this.size = next == null ? 1 : next.size + 1;
        }
    }

    /**
     * Walks the nodes from the one the stack had on top when it was made.
     *
     * @param <E> The type of the items
     */
    static final class Walk<E> implements Iterator<E> {
        private Node<E> node;

        Walk(Node<E> top) {
            this.node = top;
        }

        @Override
        public boolean hasNext() {
            return node != null;
        }

        @Override
        public E next() {
            Node<E> was = node;
            if (was == null) {
                throw new NoSuchElementException();
            }
            node = was.next;
            return was.item;
        }
    }
}
