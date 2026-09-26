package io.github.brantunger.unruly.core;

/**
 * Whether a failure the engine caught has been logged already, so a failure a nested {@code run()} reported, or a
 * fatal {@link Error}, is logged once, where it happened, however many runs it passes through on its way out. A
 * {@code load()} or {@code validate()} counts as a run here: a run a language starts while it compiles, checks a
 * declared fact's name, or creates or warms up a session is nested in it.
 *
 * <p>
 * A failure a {@code run()} reports, when it was started on the same thread while another run is in progress, from a
 * condition, an action, the output supplier, a listener callback or a language, is logged by that nested run, which
 * throws a {@link ReportedFailure} that the code around it finds in the cause chain of what it caught (see
 * {@link Failures#nestedRunFailure}). A fatal {@link Error} is rethrown unchanged instead (see
 * {@link Failures#fatalError}), so nothing on it says it was logged: the first place that logs one records it here,
 * for the thread, whichever engine runs there, and every other place on the same thread that catches the same instance
 * before the outermost run on the thread ends leaves it out. The record is per thread: a nested run on another thread,
 * such as one an action hands to an executor, logs what it throws on that thread, and the run that waits for it logs it
 * again. It's forgotten when the outermost run on the thread ends, because the JVM throws the
 * {@link OutOfMemoryError} it keeps ready for when it has no memory left again and again, and a later run must log it
 * again. Nothing here logs: the caller logs when it's told the failure isn't logged yet.
 * </p>
 *
 * <p>
 * The same instance is taken for the one logged until then, however it got there: a fatal error that code catches
 * from a nested run and throws again later in the same outermost run on the thread isn't logged a second time. The
 * engine sees nothing between the two throws that would tell it apart from the error on its way out.
 * </p>
 */
final class LoggedFailures {

    // The runs, loads and validations in progress on this thread, whatever engine they are on, and the fatal Error one
    // of them logged. Removed when the outermost ends, so a pooled thread keeps nothing, and an error logged by one run
    // isn't taken for logged by a later one. A plain ThreadLocal, not withInitial(), like RuleSet's count of runs on a
    // thread.
    private static final ThreadLocal<Runs> RUNS = new ThreadLocal<>();

    private LoggedFailures() {
    }

    /** What is in progress on one thread, and the last fatal {@link Error} logged while it was. */
    private static final class Runs {
        private int depth;
        private Error loggedFatal;
    }

    /** Counts a run, a {@code load()} or a {@code validate()} starting on this thread, until {@link #leave()}. */
    static void enter() {
        Runs runs = RUNS.get();
        if (runs == null) {
            runs = new Runs();
            RUNS.set(runs);
        }
        runs.depth++;
    }

    /** Uncounts what {@link #enter()} counted, forgetting the fatal error logged once the outermost ends. */
    static void leave() {
        Runs runs = RUNS.get();
        runs.depth--;
        if (runs.depth == 0) {
            RUNS.remove();
        }
    }

    /**
     * Tells whether what was caught still has to be logged: not when it's a nested run's failure, which that run
     * logged, nor when its fatal {@link Error} was logged already (see {@link #unloggedFatal}). A fatal error it has
     * is recorded as logged, so the caller must log it when this returns {@code true}.
     *
     * @param thrown What was caught
     * @return {@code true} if the caller logs it
     */
    static boolean unlogged(Throwable thrown) {
        Error fatal = Failures.fatalError(thrown);
        return fatal != null ? unloggedFatal(fatal) : Failures.nestedRunFailure(thrown) == null;
    }

    /**
     * Tells whether a fatal {@link Error} still has to be logged, and records it as logged, so the caller must log it
     * when this returns {@code true}. It has been logged when a run in progress on this thread, of any engine, logged
     * this very instance. Every caller is inside a run, a {@code load()} or a {@code validate()} on this thread, so
     * there is always one in progress.
     *
     * @param fatal The fatal error
     * @return {@code true} if the caller logs it
     */
    // The very same instance is what was logged; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    static boolean unloggedFatal(Error fatal) {
        Runs runs = RUNS.get();
        if (runs.loggedFatal == fatal) {
            return false;
        }
        runs.loggedFatal = fatal;
        return true;
    }
}
