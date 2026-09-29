package io.github.brantunger.unruly.core;

/**
 * Whether a failure the engine caught has been logged already, so a failure a nested {@code run()} or {@code load()}
 * reported, or a fatal {@link Error}, is logged once, where it happened, however many runs it passes through on its
 * way out. A {@code load()}, a {@code validate()} or a {@code close()} counts as a run here: a run a language starts
 * while it compiles, checks a declared fact's name, creates, warms up or closes a session, or closes a compiler is
 * nested in it.
 *
 * <p>
 * A failure a {@code run()} reports, when it was started on the same thread while another run is in progress, from a
 * condition, an action, the output supplier, a listener callback or a language, is logged by that nested run, which
 * throws a {@link ReportedFailure} that the code around it finds in the cause chain of what it caught (see
 * {@link Failures#nestedRunFailure}). A nested run that rejects its facts, and a nested {@code load()} that fails,
 * throw the exception their callers expect instead, an {@link IllegalArgumentException} or a
 * {@link io.github.brantunger.unruly.api.exception.RuleCompilationException}, so nothing on it says it was logged: the
 * place that logs one records it here, and the code around it finds it in the cause chain all the same. So does a
 * fatal {@link Error}, which is rethrown unchanged (see {@link Failures#fatalError}): the first place that logs one
 * records it here, and every other place on the same thread that catches the same instance leaves it out. The record
 * is for the thread, whichever engine runs there, and holds the very instances that were logged: an equal one is
 * still news.
 * </p>
 *
 * <p>
 * The record is per thread: a nested run on another thread, such as one an action hands to an executor, logs what it
 * throws on that thread, and the run that waits for it logs it again. It's forgotten when the outermost run on the
 * thread ends, because the JVM throws the {@link OutOfMemoryError} it keeps ready for when it has no memory left again
 * and again, and a later run must log it again, as it must an exception kept from an earlier run and thrown again.
 * Only a nested run or {@code load()} records a failure it logged, other than a fatal error: what the outermost one
 * logs goes to its caller, and no code of the engine's catches it on the way, so the outermost records nothing and
 * creates nothing to record it in. The record holds the last {@value #MAX_LOGGED} of them, and apart from them the last
 * {@value #MAX_LOGGED} fatal errors any run, {@code load()}, {@code validate()} or {@code close()} on the thread
 * logged, so a run whose nested runs fail again and again keeps no more memory. One logged before those is logged a
 * second time if code keeps it and throws it on to the code around it, as it was before the engine recorded any; the
 * bound never leaves a failure out.
 * Nothing here logs: the caller logs when it's told the failure isn't logged yet.
 * </p>
 *
 * <p>
 * The same instance is taken for the one logged until then, however it got there: a failure or a fatal error that
 * code catches from a nested run and throws again later in the same outermost run on the thread, such as from another
 * rule's action, isn't logged a second time. The engine sees nothing between the two throws that would tell it apart
 * from the failure on its way out. So a language that throws one cached exception instance every time it rejects a
 * fact's name has it logged only the first time a nested run rejects a name in an outermost run, even when that
 * rejection was handled and a later one, the same instance, is what fails the run; the next outermost run logs it
 * again.
 * </p>
 */
final class LoggedFailures {

    /**
     * How many of the failures nested runs and loads logged a thread remembers, and how many of the fatal errors runs
     * logged; see {@link #loggedByRun} and {@link #unloggedFatal}.
     */
    static final int MAX_LOGGED = 32;

    /** How deep the outermost run on a thread is, which records nothing it logs. */
    private static final int OUTERMOST = 1;

    // The runs, loads and validations in progress on this thread, whatever engine they are on, and the fatal Errors and
    // the failures they logged. Removed when the outermost ends, so a pooled thread keeps nothing, and a failure
    // logged by one run isn't taken for logged by a later one. A plain ThreadLocal, not withInitial(), like RuleSet's
    // count of runs on a thread.
    private static final ThreadLocal<Runs> RUNS = new ThreadLocal<>();

    private LoggedFailures() {
    }

    /**
     * A failure a nested {@code run()} or {@code load()} logged and threw as is, not as a {@link ReportedFailure}.
     *
     * @param failure The very exception that was logged
     * @param byLoad  {@code true} if a {@code load()} logged it, {@code false} if a {@code run()} did
     */
    record Logged(Throwable failure, boolean byLoad) {
    }

    /**
     * What is in progress on one thread, the last {@value #MAX_LOGGED} fatal {@link Error}s logged while it was, in a
     * ring created with it, before any fails, so recording an {@link OutOfMemoryError} allocates nothing, and the last
     * {@value #MAX_LOGGED} failures nested runs and loads logged, in a ring created with the first of them.
     */
    private static final class Runs {
        private int depth;
        private final Error[] loggedFatal = new Error[MAX_LOGGED];
        private int nextFatal;
        private Logged[] logged;
        private int next;
    }

    /**
     * Counts a run, a {@code load()}, a {@code validate()} or a {@code close()} starting on this thread, until
     * {@link #leave()}.
     */
    static void enter() {
        Runs runs = RUNS.get();
        if (runs == null) {
            runs = new Runs();
            RUNS.set(runs);
        }
        runs.depth++;
    }

    /** Uncounts what {@link #enter()} counted, forgetting what was logged once the outermost ends. */
    static void leave() {
        Runs runs = RUNS.get();
        runs.depth--;
        if (runs.depth == 0) {
            RUNS.remove();
        }
    }

    /**
     * Tells whether what was caught still has to be logged: not when it's a nested run's or load's failure, which
     * that run or load logged, nor when its fatal {@link Error} was logged already (see {@link #unloggedFatal}). A
     * fatal error it has is recorded as logged, so the caller must log it when this returns {@code true}.
     *
     * @param thrown What was caught
     * @return {@code true} if the caller logs it
     */
    static boolean unlogged(Throwable thrown) {
        Error fatal = Failures.fatalError(thrown);
        return fatal != null ? unloggedFatal(fatal) : Failures.nestedRunFailure(thrown) == null;
    }

    /**
     * Tells whether what was caught has been logged already, as {@link #unlogged} does, without recording anything, for
     * a caller that logs at WARN what it doesn't throw, such as a listener's exception or a failure to close a session:
     * a nested run's or load's failure (see {@link Failures#nestedRunFailure}), or a fatal {@link Error} a run in
     * progress on this thread logged, when nothing wrapped around it says something of its own (see
     * {@link Failures#newsAbove}). A fatal error isn't recorded here: logging one at WARN doesn't count as logging it
     * for a place that throws it on and logs it at ERROR. Every caller in the engine is inside a run, a
     * {@code load()}, a {@code validate()} or a {@code close()} on this thread; with none in progress, no fatal error
     * has been logged.
     *
     * @param thrown What was caught
     * @return {@code true} if the caller leaves it out of the log
     */
    static boolean logged(Throwable thrown) {
        Error fatal = Failures.fatalError(thrown);
        if (fatal == null) {
            return Failures.nestedRunFailure(thrown) != null;
        }
        Runs runs = RUNS.get();
        return runs != null && holdsFatal(runs, fatal) && Failures.newsAbove(thrown, fatal) == null;
    }

    /**
     * Tells whether a fatal {@link Error} still has to be logged, and records it as logged, so the caller must log it
     * when this returns {@code true}. It has been logged when a run in progress on this thread, of any engine, logged
     * this very instance, and it's one of the last {@value #MAX_LOGGED} fatal errors logged there; another logged
     * since, such as by a nested run, doesn't make it news. One logged before those is logged again, and recorded in
     * place of the oldest. Every caller is inside a run, a {@code load()} or a {@code validate()} on this thread, so
     * there is always one in progress.
     *
     * @param fatal The fatal error
     * @return {@code true} if the caller logs it
     */
    static boolean unloggedFatal(Error fatal) {
        Runs runs = RUNS.get();
        if (holdsFatal(runs, fatal)) {
            return false;
        }
        runs.loggedFatal[runs.nextFatal] = fatal;
        runs.nextFatal = (runs.nextFatal + 1) % MAX_LOGGED;
        return true;
    }

    /** Tells whether this very fatal error is among the last {@value #MAX_LOGGED} logged on the thread. */
    // The very same instance is what was logged; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean holdsFatal(Runs runs, Error fatal) {
        for (Error logged : runs.loggedFatal) {
            if (logged == fatal) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a failure a {@code run()} has just logged and throws as is, not as a {@link ReportedFailure}, such as a
     * fact it rejects, so the code around a nested run finds it logged (see {@link #find}). Every caller is inside a
     * run on this thread. Nothing is recorded for the outermost run on the thread.
     *
     * @param failure The exception that was logged
     * @param <T>     Its type
     * @return {@code failure}, for the caller to throw
     */
    static <T extends Throwable> T loggedByRun(T failure) {
        remember(failure, false);
        return failure;
    }

    /**
     * Records a failure a {@code load()} has just logged, or one made of failures it logged, and throws as the
     * {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} its caller expects, as
     * {@link #loggedByRun} does for a run.
     *
     * @param failure The exception that was logged
     * @param <T>     Its type
     * @return {@code failure}, for the caller to throw
     */
    static <T extends Throwable> T loggedByLoad(T failure) {
        remember(failure, true);
        return failure;
    }

    /** Records a failure a nested run or load logged, in place of the oldest once {@value #MAX_LOGGED} are. */
    private static void remember(Throwable failure, boolean byLoad) {
        Runs runs = RUNS.get();
        if (runs.depth == OUTERMOST) {
            return;
        }
        if (runs.logged == null) {
            runs.logged = new Logged[MAX_LOGGED];
        }
        runs.logged[runs.next] = new Logged(failure, byLoad);
        runs.next = (runs.next + 1) % MAX_LOGGED;
    }

    /**
     * Finds one exception among the failures nested runs and loads on this thread logged, as {@link Failures#below}
     * asks of each link of a cause chain.
     *
     * @param thrown One link of a cause chain
     * @return What was recorded when that very instance was logged, or {@code null} if it wasn't, was logged before
     *         the last {@value #MAX_LOGGED}, or was logged by an outermost run, or if no run is in progress on this
     *         thread
     */
    // The very same instance is what was logged; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    static Logged find(Throwable thrown) {
        Runs runs = RUNS.get();
        if (runs == null || runs.logged == null) {
            return null;
        }
        for (Logged logged : runs.logged) {
            if (logged != null && logged.failure() == thrown) {
                return logged;
            }
        }
        return null;
    }
}
