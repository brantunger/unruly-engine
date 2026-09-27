package io.github.brantunger.unruly.mvel;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.function.Supplier;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Drops the WARNING MVEL logs through {@code java.util.logging} when a method call or an indexed read fails in its
 * reflective optimizer, most often because it can't convert a fact to the method's parameter type (a String index for
 * {@code items.get(index)}), while a rule's expression runs.
 *
 * <p>
 * MVEL's reflective optimizer logs that exception with its stack trace before it throws it again, and the exception's
 * message can quote a fact value as it is, so a fact value with a line break could write a line of its own into the
 * application's log. The engine reports the same failure, escaped, as the rule's failure, with MVEL's exception as its
 * cause, so nothing is lost. Records logged while no rule's expression runs on the thread, such as those of an
 * application that uses MVEL itself, go to the filter the logger had before, and are logged as they were.
 * </p>
 *
 * <p>
 * Where several applications in one server each bring this module, each class loader has its own copy of this class,
 * and they can all share one logger. Each copy's filter asks the one installed before it, of another class loader,
 * while that class loader lives, so each application's rules are covered, and holds it only weakly, so an application
 * that is undeployed can be unloaded once a newer one has installed over it. The newest application's filter stays on
 * the logger, which the whole JVM shares, after that application is undeployed, and keeps its class loader from being
 * unloaded until another application installs over it. One case isn't covered: with three or more applications, when
 * one in the middle is undeployed while an earlier one still runs, the newest filter goes straight to the filter the
 * logger had before them all, and MVEL's WARNING is logged again for the earlier application's rules.
 * </p>
 */
final class MvelWarningFilter implements Filter, Supplier<Filter> {

    /** The logger of MVEL's reflective optimizer, which logs the failed conversion. */
    static final String LOGGER_NAME = "org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer";

    // Set while a rule's expression runs on this thread, and removed when the outermost one ends, so a pooled thread
    // keeps nothing. A plain ThreadLocal, like RuleSet's count of runs on a thread.
    private static final ThreadLocal<Boolean> RUNNING = new ThreadLocal<>();
    // Held because java.util.logging keeps a logger only weakly: once collected, the logger MVEL gets next would
    // be a new one, without the filter.
    static final Logger LOGGER = Logger.getLogger(LOGGER_NAME);
    // The filter installed on it, or null if it couldn't be. Held here too, because another class loader's copy of
    // this class installed later holds it only weakly: it lives as long as this class loader.
    static final MvelWarningFilter FILTER = install(LOGGER);

    // Another class loader's copy of this filter, installed before this one, or null if there was none.
    private final Reference<Filter> copy;
    // The filter the logger had before any of them, or null if it had none.
    private final Filter previous;

    /**
     * Creates a filter.
     *
     * @param copy     Another class loader's copy of this filter, to ask while it lives, or {@code null} if there is
     *                 none
     * @param previous The filter to ask while no rule's expression runs, when there is no copy or it is gone, or
     *                 {@code null} to log every record then
     */
    MvelWarningFilter(Reference<Filter> copy, Filter previous) {
        this.copy = copy;
        this.previous = previous;
    }

    /**
     * Installs the filter on MVEL's logger, the first time it's called, by initializing this class.
     */
    static void initialize() {
        // The static initializer does the work.
    }

    /**
     * Sets a filter on a logger that drops its records while a rule's expression runs on the logging thread, and
     * passes every other record to the filter the logger had.
     *
     * <p>
     * When the logger's filter is this class's own, installed again, the new filter takes the one it held instead.
     * When it's another class loader's copy of this class, such as another application's in the same server, the new
     * filter asks the copy, which drops the records of that application's rules, while that class loader lives, and
     * holds it only weakly: a strong reference would keep that class loader, and every class it loaded, from being
     * unloaded, one more on each redeployment. Once the copy is gone, the new filter asks the filter the copy held.
     * </p>
     *
     * @param logger The logger
     * @return The filter set, or {@code null} if it can't be set, as when a security manager or the logging framework
     *         forbids it: the logger's filter is then unchanged, and the engine still reports the failure, but MVEL's
     *         WARNING is logged too
     */
    static MvelWarningFilter install(Logger logger) {
        try {
            // The logger may be shared with other class loaders' copies of this class, installing theirs at once.
            synchronized (logger) {
                MvelWarningFilter filter = over(logger.getFilter());
                logger.setFilter(filter);
                return filter;
            }
        } catch (RuntimeException ignored) {
            // A rule still runs and fails as it would; only MVEL's own record isn't filtered.
            return null;
        }
    }

    /**
     * Creates the filter to install over a logger's filter.
     *
     * @param existing The logger's filter, or {@code null} if it has none
     * @return The filter
     */
    private static MvelWarningFilter over(Filter existing) {
        // Supplier is a java.base type, the same class in every class loader, so another class loader's copy of this
        // class can be read through it without reflection.
        if (existing instanceof Supplier<?> supplier
                && MvelWarningFilter.class.getName().equals(existing.getClass().getName())) {
            Object held = supplier.get();
            if (held == null || held instanceof Filter) {
                // This class's own filter, installed again, is replaced: it keeps the copy it asked, if any.
                Reference<Filter> copy = existing instanceof MvelWarningFilter own ? own.copy
                        : new WeakReference<>(existing);
                return new MvelWarningFilter(copy, (Filter) held);
            }
        }
        return new MvelWarningFilter(null, existing);
    }

    /**
     * Marks a rule's expression as running on this thread, until {@link #leave(boolean)}.
     *
     * @return Whether this is the outermost expression running on this thread, which is the one that removes the mark
     */
    static boolean enter() {
        if (RUNNING.get() != null) {
            return false;
        }
        RUNNING.set(Boolean.TRUE);
        return true;
    }

    /**
     * Removes the mark {@link #enter()} set, if it set it, so an expression run from inside another one, such as by an
     * action that runs another engine, leaves the outer one's mark in place.
     *
     * @param outermost What {@link #enter()} returned
     */
    static void leave(boolean outermost) {
        if (outermost) {
            RUNNING.remove();
        }
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        if (RUNNING.get() != null) {
            return false;
        }
        // The copy asks the filter it holds, which is this one's previous, once it has checked its own rules.
        Filter other = copy == null ? null : copy.get();
        Filter next = other != null ? other : previous;
        return next == null || next.isLoggable(record);
    }

    /**
     * Returns the filter the logger had before this one and any other class loader's copy of it, for a later
     * {@link #install(Logger)}, of this class loader's copy of this class or of another's, to keep in its place.
     *
     * @return The filter, or {@code null} if the logger had none
     */
    @Override
    public Filter get() {
        return previous;
    }
}
