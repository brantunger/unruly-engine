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
 * records it here, and every other place on the same thread that catches the same instance leaves it out, unless code
 * wrapped it in an exception that says something of its own: that place logs that, with the error as a note (see
 * {@link #loggedAt}), and rethrows the error all the same. The record is for the thread, whichever
 * engine runs there, and holds the very instances that were logged: an equal one is still news.
 * </p>
 *
 * <p>
 * The record is per thread: a run on another thread, such as one an action hands to an executor, isn't nested. It
 * logs what it throws on that thread, and the run that waits for it logs it again unless it's that run's
 * {@link ReportedFailure}, which {@link Failures#nestedRunFailure} finds on any thread: a fatal {@link Error}, or an
 * exception thrown as is, such as for facts that run rejected, is logged twice. It's forgotten when the outermost run
 * on the thread ends, because the JVM throws the {@link OutOfMemoryError} it keeps ready for when it has no memory
 * left again and again, and a later run must log it again, as it must an exception kept from an earlier run and
 * thrown again.
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
 *
 * <p>
 * The record also holds the last {@value #MAX_LOGGED} exceptions the engine built around a failure, that aren't a
 * {@link ReportedFailure}, such as the {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} of a
 * rule whose language failed with a nested run's failure, so the code around one knows it for the engine's own words,
 * which add nothing to a nested failure, however its text reads (see {@link #builtByEngine}). It holds them for the
 * outermost run too, whose own code reads them, and forgets them with the rest.
 * </p>
 */
final class LoggedFailures {

    /**
     * How many of the failures nested runs and loads logged a thread remembers, how many of the fatal errors runs
     * logged, and how many of the exceptions the engine built around a failure; see {@link #loggedByRun},
     * {@link #unloggedFatal} and {@link #builtByEngine}.
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
     * What is in progress on one thread, the last {@value #MAX_LOGGED} fatal {@link Error}s logged while it was, with
     * how deep the run that logged each was and which of the runs it was nested in were a {@code load()} or a
     * {@code validate()}, in rings created with it, before any fails, so recording an {@link OutOfMemoryError}
     * allocates nothing, the last {@value #MAX_LOGGED} failures nested runs and loads logged, in a ring created
     * with the first of them, and the last {@value #MAX_LOGGED} exceptions the engine built around a failure, in a
     * ring created with the first of those.
     */
    private static final class Runs {
        private int depth;
        // Bit n is set while the run in progress n deep is a load() or a validate(); one deeper than a long has bits
        // for counts as a run.
        private long loads;
        private final Error[] loggedFatal = new Error[MAX_LOGGED];
        private final int[] fatalDepth = new int[MAX_LOGGED];
        private final long[] fatalLoads = new long[MAX_LOGGED];
        private int nextFatal;
        private Logged[] logged;
        private int next;
        private Throwable[] built;
        private int nextBuilt;
    }

    /**
     * Counts a run, a {@code load()}, a {@code validate()} or a {@code close()} starting on this thread, until
     * {@link #leave()}.
     */
    static void enter() {
        Faults.at(Faults.Step.RUN_COUNTED);
        Runs runs = RUNS.get();
        if (runs == null) {
            runs = new Runs();
            RUNS.set(runs);
        }
        runs.depth++;
    }

    /**
     * Counts a {@code load()} or a {@code validate()} starting on this thread, as {@link #enter()} counts a run, so a
     * fatal {@link Error} logged below it is told apart from one logged below a run (see {@link #loggedAt}), until
     * {@link #leave()}.
     */
    static void enterLoad() {
        enter();
        Runs runs = RUNS.get();
        runs.loads |= bit(runs.depth);
    }

    /**
     * Uncounts what {@link #enter()} or {@link #enterLoad()} counted, forgetting what was logged once the outermost
     * ends.
     */
    static void leave() {
        Runs runs = RUNS.get();
        runs.loads &= ~bit(runs.depth);
        runs.depth--;
        if (runs.depth == 0) {
            RUNS.remove();
        }
        Faults.at(Faults.Step.RUN_UNCOUNTED);
    }

    /** The bit of {@link Runs#loads} for a depth, or none for one deeper than a {@code long} has bits for. */
    private static long bit(int depth) {
        return depth < Long.SIZE ? 1L << depth : 0L;
    }

    /**
     * Tells whether what was caught still has to be logged: not when it's a nested run's or load's failure, which
     * that run or load logged, nor when its fatal {@link Error} was logged already (see {@link #unloggedFatal}),
     * unless an exception wrapped around that error says something of its own (see {@link Failures#wrapsLoggedFatal}):
     * the caller logs that, with the error as a note (see {@link Failures#describe}), and still rethrows the error. A
     * fatal error it has is recorded as logged, so the caller must log it when this returns {@code true}.
     *
     * @param thrown What was caught
     * @return {@code true} if the caller logs it
     */
    static boolean unlogged(Throwable thrown) {
        Error fatal = Failures.fatalError(thrown);
        if (fatal == null) {
            return Failures.nestedRunFailure(thrown) == null;
        }
        return unloggedFatal(fatal) || Failures.newsAbove(thrown, fatal) != null;
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
        runs.fatalDepth[runs.nextFatal] = runs.depth;
        runs.fatalLoads[runs.nextFatal] = runs.loads;
        runs.nextFatal = (runs.nextFatal + 1) % MAX_LOGGED;
        return true;
    }

    /** Tells whether this very fatal error is among the last {@value #MAX_LOGGED} logged on the thread. */
    private static boolean holdsFatal(Runs runs, Error fatal) {
        return indexOf(runs, fatal) >= 0;
    }

    /** Finds where this very fatal error is among the last {@value #MAX_LOGGED} logged on the thread, or -1. */
    // The very same instance is what was logged; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static int indexOf(Runs runs, Error fatal) {
        for (int i = 0; i < MAX_LOGGED; i++) {
            if (runs.loggedFatal[i] == fatal) {
                return i;
            }
        }
        return -1;
    }

    /** Where a fatal {@link Error} that was logged already was logged, as the innermost run in progress sees it. */
    enum LoggedAt {
        /** Below a {@code run()} the innermost run in progress started. */
        NESTED_RUN,
        /** Below a {@code load()} or a {@code validate()} the innermost run in progress started. */
        NESTED_LOAD,
        /**
         * By the innermost run in progress, or one around it, or a nested run that has ended at the same depth, such
         * as one before it that logged the same {@link OutOfMemoryError} the JVM throws again and again.
         */
        NOT_BELOW
    }

    /**
     * Tells where a fatal {@link Error} that was logged already on this thread was logged, for the code that wraps it
     * in an exception of its own (see {@link Failures#describe}): below what the innermost run in progress started, a
     * {@code run()} or a {@code load()}, named for what it started, however deep below that it was logged, or not
     * below it. A level's own fatal error, such as the one in the failure a listener is told of, is never nested.
     *
     * @param fatal The fatal error
     * @return Where that very instance was logged, or {@code null} if it wasn't, it was logged before the last
     *         {@value #MAX_LOGGED}, or no run is in progress on this thread
     */
    static LoggedAt loggedAt(Error fatal) {
        Runs runs = RUNS.get();
        int at = runs == null ? -1 : indexOf(runs, fatal);
        if (at < 0) {
            return null;
        }
        if (runs.fatalDepth[at] <= runs.depth) {
            return LoggedAt.NOT_BELOW;
        }
        return (runs.fatalLoads[at] & bit(runs.depth + 1)) != 0 ? LoggedAt.NESTED_LOAD : LoggedAt.NESTED_RUN;
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

    /**
     * Records an exception the engine has just built around a failure, that isn't a {@link ReportedFailure}, such as
     * the {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} of an expression that failed to
     * compile, or the {@link IllegalArgumentException} of a language that failed to check a fact name, so the code
     * around it takes it for adding nothing to a nested failure it holds, as a {@link ReportedFailure} adds nothing,
     * however its text reads (see {@link #isEngineWrapper}). Its message has the nested failure's text in it, and an
     * application's own exception with that text and words around it is news. Every caller is inside a run, a
     * {@code load()} or a {@code validate()} on this thread; the outermost records it too, because its own code reads
     * it. The last {@value #MAX_LOGGED} are kept, in place of the oldest, and only while the outermost run, load,
     * validation or close that built it is in progress: one a top-level {@code load()} or {@code validate()} threw or
     * returned, and code throws on later from inside a new run, is taken for news, as an application's exception is.
     *
     * @param wrapper The exception the engine built
     * @param <T>     Its type
     * @return {@code wrapper}, for the caller to throw or return
     */
    static <T extends Throwable> T builtByEngine(T wrapper) {
        Runs runs = RUNS.get();
        if (runs.built == null) {
            runs.built = new Throwable[MAX_LOGGED];
        }
        runs.built[runs.nextBuilt] = wrapper;
        runs.nextBuilt = (runs.nextBuilt + 1) % MAX_LOGGED;
        return wrapper;
    }

    /**
     * Tells whether one link of a cause chain is an exception the engine built around a failure on this thread (see
     * {@link #builtByEngine}), as {@link Failures#below} asks of each link above a nested failure.
     *
     * @param link One link of a cause chain
     * @return {@code true} if the engine recorded that very instance, and it's one of the last {@value #MAX_LOGGED};
     *         {@code false} if not, or if no run is in progress on this thread
     */
    // The very same instance is what the engine built; an equal one is an application's.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    static boolean isEngineWrapper(Throwable link) {
        Runs runs = RUNS.get();
        if (runs == null || runs.built == null) {
            return false;
        }
        for (Throwable built : runs.built) {
            if (built == link) {
                return true;
            }
        }
        return false;
    }
}
