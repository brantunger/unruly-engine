package io.github.brantunger.unruly.core;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * The values a language keeps for one run, which
 * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScoped} and
 * {@link io.github.brantunger.unruly.api.language.EvaluationContext#runScopedClosing} give it. The run's evaluation
 * context and each of its action contexts share one scope, and a nested run has a scope of its own.
 *
 * <p>
 * {@link #get} and {@link #getClosing} decide in short sections synchronized on the scope, which never wait and never
 * run an init, so another thread can ask for a value too, such as through a context a language kept. A request for a
 * key whose init is running on its own thread fails, and so does a {@link #get}, or a {@link #getClosing} for a
 * {@code runScoped} key, while the init runs on another thread; a {@link #getClosing} on another thread waits for a
 * running closing init (below). The scope makes its map the first time a value is asked for, so a run whose
 * languages keep nothing allocates no map. Only the run's contexts refer to it, so it goes when the run returns. The
 * values kept with {@code runScoped} aren't closed; those kept with {@code runScopedClosing} are handed back by
 * {@link #end()}, for the run to close, and none can be made after that.
 * </p>
 *
 * <p>
 * Closing inits run one at a time: a thread takes the scope's turn before one runs, and gives it back after. The turn
 * is re-entrant, so an init can ask for another closing key. A closing request on another thread, and {@link #end()},
 * wait for the turn, so a value asked for on another thread while the run ends, such as through a context a language
 * kept, is either handed back to be closed or refused, never left open, unless {@link #end()} fails and isn't called
 * again (see {@link #end()}). A value whose init ends the run itself, on its own thread, is closed and refused.
 * </p>
 */
// Nothing that keeps a value or gives the turn back enters a monitor or calls a method, either of which can overflow
// the stack, as a language that catches StackOverflowError would go on from: a made value is never left unkept, a key
// never left being made, and the turn never left taken.
final class RunScope {

    // How long a thread waiting for the turn parks before it looks again, 10 ms: it polls, holding no monitor, so a
    // virtual thread never parks pinned to its carrier, as it would in Object.wait() on JDK 21 to 23.
    private static final long POLL_NANOS = 10_000_000L;
    // What closing holds once end() has handed the values back, so the run having ended takes no field of its own.
    // RunClasses initializes it with the engine's other classes, as runs use it.
    @SuppressWarnings("PMD.LooseCoupling")
    private static final ClosingValues ENDED = new ClosingValues();

    // Each key's slot, made the first time a value is asked for.
    private Map<Object, Slot> values;
    // The values kept with getClosing, made with the first: the class itself, whose array keeping one stores into.
    @SuppressWarnings("PMD.LooseCoupling")
    private ClosingValues closing;
    // The thread whose getClosing calls are running, and how many are: one thread's closing inits at a time. Taken
    // under the monitor, and given back by the thread that holds it with plain stores.
    private volatile Thread owner;
    private volatile int holds;
    // Set by end() before it waits for the turn: only the turn's owner can make another closing value meanwhile.
    private boolean ending;

    /** One key's entry: being made by {@code maker}, kept once {@code value} is set, or let go when neither is. */
    private static final class Slot {
        private final boolean closes;
        // Cleared once the init has returned or thrown, after value is set, so a reader that sees no maker sees the
        // value if one was kept.
        private volatile Thread maker;
        private volatile Object value;

        Slot(boolean closes, Thread maker) {
            this.closes = closes;
            this.maker = maker;
        }
    }

    /** The values kept with {@link #getClosing}, in the order they were made, which {@link #end()} hands back. */
    // A list over its array, not an ArrayList: keeping a value is an array store, with room made before the init runs.
    private static final class ClosingValues extends AbstractList<AutoCloseable> implements RandomAccess {
        // Objects, cast as they're read, so keeping a value casts nothing.
        private Object[] items = new Object[4];
        private int count;

        @Override
        public AutoCloseable get(int index) {
            Objects.checkIndex(index, count);
            return (AutoCloseable) items[index];
        }

        @Override
        public int size() {
            return count;
        }
    }

    /**
     * Returns the value kept under {@code key}, making it with {@code init} the first time. A supplier that throws, or
     * returns {@code null}, keeps nothing, so the next call for the key calls a supplier again. The supplier can ask
     * for other keys: the value is kept only once it returns. It can't ask for its own key, nor can another thread
     * while it runs.
     *
     * @param key  The key
     * @param init Makes the value
     * @param <T>  The value's type
     * @return The value
     * @throws NullPointerException  if {@code key}, {@code init} or what {@code init} returns is {@code null}
     * @throws IllegalStateException if the init of {@code key} is running, on this thread or another, or {@code key}
     *                               is kept with {@link #getClosing}
     */
    <T> T get(Object key, Supplier<? extends T> init) {
        return get(key, init, false);
    }

    /**
     * Returns the value kept under {@code key}, as {@link #get} does, and keeps it to be handed back by
     * {@link #end()} when the value is made. It takes the scope's turn first, waiting while another thread holds it,
     * so a key asked for on another thread while its init runs gets the value it made, or, if that init threw or
     * returned {@code null}, makes its own. If {@code init} ends the scope
     * and returns a value, nothing is kept under {@code key}: the value is closed, and refused as one asked for after
     * the scope ended is. What its {@code close()} throws is suppressed on the {@link IllegalStateException}, unless
     * that is or carries a fatal {@link Error} (see {@link Failures#fatalFirst}), which is thrown in its place,
     * carrying the {@link IllegalStateException}. The wait is uninterruptible: an interrupt meanwhile is set again once
     * it ends.
     *
     * @param key  The key
     * @param init Makes the value
     * @param <T>  The value's type
     * @return The value
     * @throws NullPointerException  if {@code key}, {@code init} or what {@code init} returns is {@code null}
     * @throws IllegalStateException if the init of {@code key} is running, {@code key} is kept with {@link #get}, or
     *                               {@link #end()} has been called, including by {@code init}, or, when this thread
     *                               doesn't hold the turn, is waiting for another thread to give it back
     */
    // With end(): the init runs holding the turn, so a value is kept, or refused, wholly before or after the run ends;
    // only the init itself can end it meanwhile, and its value is then closed and refused.
    <T extends AutoCloseable> T getClosing(Object key, Supplier<? extends T> init) {
        return get(key, init, true);
    }

    // Any Throwable: the turn is given back, and the slot kept or let go, however the init ends.
    @SuppressWarnings({"unchecked", "PMD.CompareObjectsWithEquals", "PMD.NullAssignment"})
    private <T> T get(Object key, Supplier<? extends T> init, boolean closes) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(init, "init must not be null");
        String method = closes ? "runScopedClosing" : "runScoped";
        Thread me = Thread.currentThread();
        boolean turn = false;
        Slot slot = null;
        Object value = null;
        boolean kept = false;
        try {
            boolean interrupted = false;
            try {
                for (;;) {
                    synchronized (this) {
                        if (closes) {
                            // Once end() has begun, only the thread it waits for can make another closing value.
                            if (closing == ENDED || ending && owner != me) {
                                throw endedFailure(method, key);
                            }
                            if (owner == null || owner == me) {
                                owner = me;
                                holds++;
                                turn = true;
                            }
                        }
                        if (turn || !closes) {
                            if (values == null) {
                                Faults.reached(Faults.Step.RUN_SCOPE_MAP_MAKING);
                                values = new HashMap<>();
                            }
                            Slot found = values.get(key);
                            if (found != null) {
                                // The maker before the value: a maker seen gone means its value, if kept, is seen too.
                                Thread maker = found.maker;
                                Object made = found.value;
                                if (made != null) {
                                    if (found.closes != closes) {
                                        throw new IllegalStateException(method + " was called for a key ("
                                                + key.getClass().getName() + ") that "
                                                + (closes ? "runScoped" : "runScopedClosing")
                                                + " keeps a value under");
                                    }
                                    return (T) made;
                                }
                                // Its own init asked for it, or another thread's is making it: a runScoped value, as
                                // only the turn's owner makes a closing one. Not computeIfAbsent, which would recurse.
                                if (maker != null) {
                                    throw new IllegalStateException(method + " was called for a key ("
                                            + key.getClass().getName() + ") while that key's init is running");
                                }
                                // Else its init threw or returned null, and this slot replaces it.
                            }
                            if (closes) {
                                makeRoom();
                            }
                            slot = new Slot(closes, me);
                            values.put(key, slot);
                            break;
                        }
                    }
                    interrupted |= pause();
                }
            } finally {
                if (interrupted) {
                    me.interrupt();
                }
            }
            value = init.get();
        } finally {
            // Only the turn's owner keeps a closing value, and only it can end the run meanwhile, so it reads closing
            // alone; the volatile stores to the slot and to owner publish the value to the thread that hands it back.
            if (slot != null) {
                if (value != null && (!closes || closing != ENDED)) {
                    if (closes) {
                        int at = closing.count;
                        closing.items[at] = value;
                        closing.count = at + 1;
                    }
                    slot.value = value;
                    kept = true;
                }
                slot.maker = null;
            }
            if (turn) {
                int left = holds - 1;
                holds = left;
                if (left == 0) {
                    owner = null;
                }
            }
        }
        if (value == null) {
            throw new NullPointerException("init must not return null");
        }
        if (!kept) {
            // Its init ended the run, on this thread: end() has handed back the values, so this one is closed.
            throw refused(method, key, (AutoCloseable) value);
        }
        return (T) value;
    }

    // Room for the value of every closing init running, all on the turn's owner's thread, this one included, so
    // keeping one is an array store. Each init made room for its own, so one more slot is ever needed.
    private void makeRoom() {
        if (closing == null) {
            closing = new ClosingValues();
        }
        if (closing.count + holds > closing.items.length) {
            closing.items = Arrays.copyOf(closing.items, closing.items.length * 2);
        }
    }

    // Parks a while, holding no monitor, and tells whether the thread was interrupted, clearing that so the next park
    // isn't cut short: the caller sets it again once it stops waiting.
    private boolean pause() {
        LockSupport.parkNanos(this, POLL_NANOS);
        return Thread.interrupted();
    }

    // The key's class, not its toString(), which a key needn't have and could leak what it holds.
    private static IllegalStateException endedFailure(String method, Object key) {
        return new IllegalStateException(method + " was called for a key (" + key.getClass().getName()
                + ") after the run ended, when its value would never be closed");
    }

    // Closes a value whose init ended the run, and returns the failure to throw. It's closed first, so building the
    // failure, which allocates and so can run out of stack or memory, never leaves it open. Any Throwable: what close()
    // threw is suppressed on the failure, but a fatal Error is thrown itself, carrying it; and if building the failure
    // fails, what that threw is thrown, unless close() threw a fatal Error, which is thrown alone, as keeping what
    // building threw on it would allocate too. The turn has been given back by then.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static IllegalStateException refused(String method, Object key, AutoCloseable value) {
        Throwable closing = null;
        try {
            value.close();
        } catch (Throwable t) {
            closing = t;
        }
        IllegalStateException refused;
        try {
            Faults.at(Faults.Step.RUN_VALUE_REFUSING);
            refused = endedFailure(method, key);
        } catch (Throwable t) {
            // Only instanceof checks, which can't fail as building did: the fatal Error close() threw itself wins.
            if (closing instanceof VirtualMachineError fatal && !(fatal instanceof StackOverflowError)) {
                throw fatal;
            }
            throw t;
        }
        if (closing != null) {
            // The refusal, carrying closing, or the fatal Error in it, carrying the refusal but not closing, which is
            // or reaches it.
            Throwable thrown = Failures.fatalFirst(refused, closing);
            if (thrown instanceof Error fatal) {
                throw fatal;
            }
        }
        return refused;
    }

    /**
     * Ends the run's scope: hands back the values kept with {@link #getClosing}, for the caller to close, and makes
     * {@link #getClosing} fail from now on. {@link #get} still works. While another thread holds the scope's turn,
     * such as one running an init in {@link #getClosing}, it waits for it, and refuses a closing value to any other
     * thread meanwhile; that init's value is then handed back. The wait is uninterruptible: an interrupt meanwhile is
     * set again once it ends. Called on the thread that holds the turn, from an init, it doesn't wait. It allocates
     * nothing, so it can end a run that has run out of memory. A call that throws, as waiting can when the stack or the
     * heap runs out, hands nothing back, and leaves the values to the next call. Nothing can be added to the list it
     * returns.
     *
     * @return The values to close, in the order they were made: the caller closes them last first, so a value made
     *         from another is closed before it. Empty if none were kept, or a call before this one has handed them
     *         back.
     */
    // Any Throwable: the interrupt is set again however the wait ends, as a virtual thread's park can fail for memory.
    // The values are taken only after that, so a call that fails, waiting or setting the interrupt again, leaves them
    // for the next. Once ending is set and the turn is free, or this thread's, no other thread can take the turn, nor
    // so make or keep a closing value; but another can end the scope too, so they're taken, and closing marked ended,
    // in one section synchronized on the scope, and only one call hands them back.
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.LooseCoupling"})
    List<AutoCloseable> end() {
        Thread me = Thread.currentThread();
        boolean interrupted = false;
        try {
            for (;;) {
                synchronized (this) {
                    ending = true;
                    if (owner == null || owner == me) {
                        break;
                    }
                }
                Faults.at(Faults.Step.RUN_SCOPE_END_WAITING);
                interrupted |= pause();
            }
        } finally {
            if (interrupted) {
                Faults.at(Faults.Step.RUN_SCOPE_END_INTERRUPTING);
                me.interrupt();
            }
        }
        ClosingValues handedBack;
        synchronized (this) {
            handedBack = closing;
            Faults.reached(Faults.Step.RUN_SCOPE_VALUES_TAKING);
            closing = ENDED;
        }
        return handedBack == null || handedBack == ENDED ? List.of() : handedBack;
    }

    /**
     * Tells whether a value was ever asked for, and so whether the scope has made its map.
     *
     * @return {@code true} once {@link #get} or {@link #getClosing} has been called
     */
    boolean allocated() {
        synchronized (this) {
            return values != null;
        }
    }
}
