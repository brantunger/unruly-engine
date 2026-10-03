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
 * Only a nested run or {@code load()} records a failure it logged and threw as is: what the outermost one logs goes to
 * its caller, and no code of the engine's catches it on the way, so the outermost records none and creates nothing to
 * record one in. Every run records a {@link ReportedFailure} it built, the outermost too, so where it was logged is
 * known (see {@link #loggedBelow(ReportedFailure)}). The record holds the last {@value #MAX_LOGGED} of each, and apart
 * from them the last {@value #MAX_LOGGED} fatal errors any run, {@code load()}, {@code validate()} or {@code close()}
 * on the thread logged, so a run whose nested runs fail again and again keeps no more memory. One logged before those
 * is logged a second time if code keeps it and throws it on to the code around it, as it was before the engine
 * recorded any; the bound never leaves a failure out.
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
 * Where each failure was logged is recorded too, as how deep the run that logged it was and when, for the code that
 * wraps one in an exception that says something of its own, which names it as a note (see {@link Failures#describe}).
 * The time is marked each time a run hands control to code it doesn't own, a call-out: a condition, an action, the
 * output supplier or writer, a listener callback, the clock, a fact store's {@code asMap()} or a fact's
 * {@code getValue()} as the facts are read, and a language preparing itself at its first use, creating its compiler,
 * compiling, checking a fact name, creating, warming up or closing a session or closing its compiler, and a value a
 * language kept for the run being closed (see {@link #callOut()}). Each is a call-out of its own, a rule's condition
 * and its action too. A failure logged by a run the call-out in progress in the innermost run started, however deep, is
 * a nested run's, and one logged by a run that had ended before that call-out started, however deep, such as one an
 * earlier sibling, an earlier rule, the same rule's condition or a listener started, is noted as logged already, as a
 * fatal error is (see {@link #loggedAt}). Call-outs are told apart in runs fewer than {@value #MARKED_DEPTHS} deep; in
 * a deeper run, a failure logged by any run it started is a nested run's, and only one logged by a run that had ended
 * before it started is logged already. So is a {@link ReportedFailure} built on the thread in an earlier outermost run,
 * as only such a failure says which outermost run built it. One built on another thread is a nested run's, as one an
 * action hands to an executor and waits for is. Code that throws such a failure on with no words of its own has it read
 * as the failure, logged already, the same way, not as a nested run's.
 * </p>
 *
 * <p>
 * The record also holds the last {@value #MAX_LOGGED} exceptions the engine built around a failure, that aren't a
 * {@link ReportedFailure}, such as the {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} of a
 * rule whose language failed with a nested run's failure, or the {@link IllegalStateException} of
 * {@link io.github.brantunger.unruly.api.language.FactProperties} around a nested run's failure that a fact's accessor
 * threw, so the code around one knows it for the engine's own words, which add nothing to a nested failure, however
 * its text reads (see {@link #builtByEngine}). It holds them for the outermost run too, whose own code reads them, and
 * forgets them with the rest.
 * </p>
 */
final class LoggedFailures {

    /**
     * How many of the failures nested runs and loads logged a thread remembers, how many of the fatal errors runs
     * logged, and how many of the exceptions the engine built around a failure; see {@link #loggedByRun},
     * {@link #unloggedFatal} and {@link #builtByEngine}.
     */
    static final int MAX_LOGGED = 32;

    /**
     * The depth from which a run's call-outs are no longer told apart (see {@link #callOut()}): a run fewer than this
     * deep tells them apart, as only a run fewer than this deep has a bit that says whether it's a {@code load()}
     * (see {@link Runs}); one this deep or deeper tells a failure below it by depth alone.
     */
    static final int MARKED_DEPTHS = Long.SIZE;

    /** Where in {@link Runs#stamps} what each run around the innermost had marked starts. */
    private static final int OUTER_CALL_STARTS = MAX_LOGGED;

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
     * A failure a nested {@code run()} or {@code load()} logged and threw as is, not as a {@link ReportedFailure}, or a
     * {@link ReportedFailure} a run on this thread built, with how deep the run that logged it was, or, if a shallower
     * run has started since, how deep the shallowest such run was, and when it was logged, as for a fatal
     * {@link Error} (see {@link #loggedAt}).
     */
    static final class Logged {
        private final Throwable thrown;
        private final boolean loggedByLoad;
        private int depth;
        private final long call;

        private Logged(Throwable thrown, boolean loggedByLoad, int depth, long call) {
            this.thrown = thrown;
            this.loggedByLoad = loggedByLoad;
            this.depth = depth;
            this.call = call;
        }

        /**
         * Returns the failure.
         *
         * @return The very exception that was logged
         */
        Throwable failure() {
            return thrown;
        }

        /**
         * Tells whether a {@code load()} logged the failure.
         *
         * @return {@code true} if a {@code load()} logged it, {@code false} if a {@code run()} did
         */
        boolean byLoad() {
            return loggedByLoad;
        }
    }

    /**
     * An outermost run on a thread, which a {@link ReportedFailure} built in it keeps, so a later run tells whether it
     * was built in the same one (see {@link #loggedBelow(ReportedFailure)}).
     */
    static final class Outermost {
        private final long thread = Thread.currentThread().threadId();
    }

    /**
     * What is in progress on one thread, the last {@value #MAX_LOGGED} fatal {@link Error}s logged while it was, with
     * how deep the run that logged each was, or, if a shallower run has started since, how deep the shallowest such run
     * was, when it was logged, and which of the runs it was nested in were a {@code load()} or a {@code validate()}, in
     * rings created with it, before any fails, so recording an {@link OutOfMemoryError} allocates nothing, the last
     * {@value #MAX_LOGGED} failures nested runs and loads logged, in a ring created with the first of them, the last
     * {@value #MAX_LOGGED} {@link ReportedFailure}s runs built, in a ring created with the first of those, each of
     * these with how deep the run that logged it was, lowered as for a fatal error, and when, and the last
     * {@value #MAX_LOGGED} exceptions the engine built around a failure, in a ring created with the first of those.
     * When is a count of the call-outs started on the thread (see {@link LoggedFailures#callOut()}). The count the
     * innermost run had reached when its call-out in progress started is kept too, and, once a run is nested, the
     * count each run around it fewer than {@value #MARKED_DEPTHS} deep had reached, to take up again when the run
     * nested in it ends, and when each fatal error was logged, in an array created when the first nested run starts, so
     * a run that starts none allocates nothing for them, and recording an {@link OutOfMemoryError} still allocates
     * nothing: a fatal error the outermost run logs is never below a run, and needs no count. A run keeps this from
     * {@link LoggedFailures#enter()} until {@link LoggedFailures#leave()}, so marking its call-outs looks nothing up.
     */
    static final class Runs {
        // Which outermost run this is, so a ReportedFailure built in an earlier one on this thread is told apart;
        // created with the first one built, so a run that builds none allocates nothing for it.
        private Outermost outermost;
        private int depth;
        // Bit n is set while the run in progress n deep is a load() or a validate(); one deeper than a long has bits
        // for counts as a run.
        private long loads;
        // How many call-outs have started on the thread, which is when each failure is recorded as logged.
        private long calls;
        // What calls was when the call-out in progress in the innermost run started, or the run itself.
        private long callStart;
        // Entry i, below MAX_LOGGED, is what calls was when the fatal Error in loggedFatal[i] was logged, if that was
        // deeper than the outermost run; entry OUTER_CALL_STARTS + n is what callStart was for the run n deep while a
        // run nested in it is in progress. One array rather than two, and the Outermost made with the first failure
        // reported rather than with the Runs, so a run that starts no nested run and reports no failure allocates no
        // more than before call-outs were told apart.
        private long[] stamps;
        private final Error[] loggedFatal = new Error[MAX_LOGGED];
        private final int[] fatalDepth = new int[MAX_LOGGED];
        private final long[] fatalLoads = new long[MAX_LOGGED];
        private int nextFatal;
        // No fatal Error is recorded deeper than this, so a run that starts no shallower lowers none.
        private int deepestFatal;
        private Logged[] logged;
        private int next;
        private Logged[] reported;
        private int nextReported;
        // No failure in logged or reported is recorded deeper than this, so a run that starts no shallower lowers none.
        private int deepestLogged;
        private Throwable[] built;
        private int nextBuilt;

        /**
         * Marks a call-out of the innermost run in progress on this thread starting, as
         * {@link LoggedFailures#callOut()} does, for a run that holds what {@link LoggedFailures#enter()} returned.
         *
         * @return How many call-outs have started on this thread, to take this one up again with {@link #resume}
         */
        long callOut() {
            if (depth < MARKED_DEPTHS) {
                calls++;
                callStart = calls;
            }
            return calls;
        }

        /**
         * Takes up again a call-out of the innermost run in progress on this thread that started before the one in
         * progress, for a caller that has called code it doesn't own more than once and tells what one of those calls
         * threw only once they have all returned, as a listener's fatal {@link Error} is told once every listener has
         * been called.
         *
         * @param call What {@link #callOut()} returned when that call-out started
         */
        void resume(long call) {
            if (depth < MARKED_DEPTHS) {
                callStart = call;
            }
        }
    }

    /**
     * Counts a run, a {@code load()}, a {@code validate()} or a {@code close()} starting on this thread, until
     * {@link #leave()}, and marks it starting as a call-out does (see {@link #callOut()}). A fatal {@link Error} logged
     * deeper than it starts was logged by a run that has ended, which isn't below it: it's recorded as logged at the
     * depth it starts at, as a run that ended there would have logged it (see {@link #loggedAt}). So is a failure a
     * nested run or load logged, and a {@link ReportedFailure} a run built (see {@link #loggedBelow(Logged)}). In a
     * run fewer than {@value #MARKED_DEPTHS} deep, when each was logged tells that already, as it was logged before
     * the run started; a deeper run has only this to tell it by. The first nested run creates the array that keeps
     * when the runs around it marked their call-outs and when a fatal error was logged (see {@link Runs}).
     *
     * @return What is in progress on this thread, for the run to mark its call-outs with until it ends
     */
    static Runs enter() {
        Faults.at(Faults.Step.RUN_COUNTED);
        Runs runs = RUNS.get();
        if (runs == null) {
            runs = new Runs();
            RUNS.set(runs);
        }
        // Settled before the run is counted, calling nothing, so a throw here counts nothing.
        int depth = runs.depth + 1;
        if (depth > OUTERMOST && runs.stamps == null) {
            runs.stamps = new long[OUTER_CALL_STARTS + MARKED_DEPTHS];
        }
        if (runs.deepestFatal > depth) {
            for (int i = 0; i < MAX_LOGGED; i++) {
                if (runs.fatalDepth[i] > depth) {
                    runs.fatalDepth[i] = depth;
                }
            }
            runs.deepestFatal = depth;
        }
        if (runs.deepestLogged > depth) {
            lower(runs.logged, depth);
            lower(runs.reported, depth);
            runs.deepestLogged = depth;
        }
        if (runs.depth < MARKED_DEPTHS && depth > OUTERMOST) {
            runs.stamps[OUTER_CALL_STARTS + runs.depth] = runs.callStart;
        }
        runs.depth = depth;
        runs.callOut();
        return runs;
    }

    /** Records the failures in a ring logged deeper than a run that starts as logged at the depth it starts at. */
    private static void lower(Logged[] ring, int depth) {
        if (ring != null) {
            for (Logged logged : ring) {
                if (logged != null && logged.depth > depth) {
                    logged.depth = depth;
                }
            }
        }
    }

    /**
     * Counts a {@code load()} or a {@code validate()} starting on this thread, as {@link #enter()} counts a run, so a
     * fatal {@link Error} logged below it is told apart from one logged below a run (see {@link #loggedAt}), until
     * {@link #leave()}.
     *
     * @return What is in progress on this thread, as {@link #enter()} returns it
     */
    static Runs enterLoad() {
        Runs runs = enter();
        runs.loads |= bit(runs.depth);
        return runs;
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
        } else if (runs.depth < MARKED_DEPTHS) {
            // A nested run is ending, which created the array when it started.
            runs.callStart = runs.stamps[OUTER_CALL_STARTS + runs.depth];
        }
        Faults.at(Faults.Step.RUN_UNCOUNTED);
    }

    /**
     * Marks a call-out of the innermost run in progress on this thread starting, just before the run hands control to
     * code it doesn't own: a condition, an action, a listener callback, a language and the like (see
     * {@link LoggedFailures}). What a run that code starts logs from then on is below the run, and what was logged
     * before isn't, however deep the run that logged it, until the next call-out of the same run starts. A run marks a
     * call-out through what {@link #enter()} returned where it holds that; this looks it up, for code that's called
     * from more than one run, or outside any: with none in progress, nothing is marked.
     */
    static void callOut() {
        Runs runs = RUNS.get();
        if (runs != null) {
            runs.callOut();
        }
    }

    /**
     * Returns what is in progress on this thread, for code a run calls that marks the run's call-outs (see
     * {@link #callOut()}) and isn't handed what {@link #enter()} returned, such as what reports a rule's failure to the
     * listeners: it's looked up only when a rule fails or stops.
     *
     * @return What is in progress, or {@code null} if no run is
     */
    static Runs inProgress() {
        return RUNS.get();
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
        if (runs.depth > OUTERMOST) {
            runs.stamps[runs.nextFatal] = runs.calls;
        }
        runs.deepestFatal = Math.max(runs.deepestFatal, runs.depth);
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
        /** Below a {@code run()} the call-out in progress in the innermost run in progress started. */
        NESTED_RUN,
        /** Below a {@code load()} or a {@code validate()} that call-out started. */
        NESTED_LOAD,
        /**
         * By the innermost run in progress, or one around it, or a nested run that ended before that call-out started,
         * at any depth, such as one an earlier call-out started that logged the same {@link OutOfMemoryError} the JVM
         * throws again and again.
         */
        NOT_BELOW
    }

    /**
     * Tells where a fatal {@link Error} that was logged already on this thread was logged, for the code that wraps it
     * in an exception of its own (see {@link Failures#describe}): below what the call-out in progress in the innermost
     * run in progress started (see {@link #callOut()}), a {@code run()} or a {@code load()}, named for what it started,
     * however deep below that it was logged, or not below it. A level's own fatal error, such as the one in the failure
     * a listener is told of, is never nested, nor is one logged by a run that had ended before that call-out started,
     * however deep that run was, such as one an earlier rule, listener callback or condition started.
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
        // Only one logged deeper than the outermost run can be below a run, and has when it was logged recorded.
        if (runs.fatalDepth[at] <= runs.depth || !sinceCallOut(runs, runs.stamps[at])) {
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
        runs.logged[runs.next] = new Logged(failure, byLoad, runs.depth, runs.calls);
        runs.next = (runs.next + 1) % MAX_LOGGED;
        runs.deepestLogged = Math.max(runs.deepestLogged, runs.depth);
    }

    /**
     * Records a {@link ReportedFailure} a run has just built, with how deep that run is, in place of the oldest once
     * {@value #MAX_LOGGED} are, so a run that reads it later in the same outermost run on this thread tells whether a
     * run it started logged it (see {@link #loggedBelow(ReportedFailure)}). The outermost run records one too, as its
     * own code may read it. Nothing is recorded when no run is in progress.
     *
     * @param failure The failure, which the run logs
     * @return The outermost run on this thread, for the failure to keep, or {@code null} if no run is in progress
     */
    static Outermost reported(ReportedFailure failure) {
        Runs runs = RUNS.get();
        if (runs == null) {
            return null;
        }
        // Each made on its own, so a ring of reported failures never stands without the outermost run they belong to,
        // even when making one runs out of memory: the next failure built makes what's missing.
        if (runs.outermost == null) {
            runs.outermost = new Outermost();
        }
        if (runs.reported == null) {
            runs.reported = new Logged[MAX_LOGGED];
        }
        runs.reported[runs.nextReported] = new Logged(failure, false, runs.depth, runs.calls);
        runs.nextReported = (runs.nextReported + 1) % MAX_LOGGED;
        runs.deepestLogged = Math.max(runs.deepestLogged, runs.depth);
        return runs.outermost;
    }

    /**
     * Tells whether a failure a nested run or load logged, as {@link #find} found it, was logged below the call-out in
     * progress in the innermost run in progress on this thread (see {@link #callOut()}): by a run that call-out
     * started, however deep below it, rather than by a run that had ended before it started, as one an earlier sibling
     * or an earlier call-out of the same run started had, however deep that run was. Only {@link Failures#below} asks,
     * while the run that found it is still in progress.
     *
     * @param logged What {@link #find} returned
     * @return {@code true} if a run the call-out in progress in the innermost run in progress started logged it
     */
    static boolean loggedBelow(Logged logged) {
        return below(RUNS.get(), logged.depth, logged.call);
    }

    /**
     * Tells whether a failure was logged below the call-out in progress in the innermost run in progress: deeper than
     * that run, and since the call-out started. In a run {@value #MARKED_DEPTHS} deep or deeper, whose call-outs aren't
     * told apart, deeper than the run is enough, as {@link #enter()} lowered what was logged before the run started.
     *
     * @param runs  What is in progress on this thread
     * @param depth How deep the run that logged it was, lowered as {@link #enter()} lowers it
     * @param call  How many call-outs had started on the thread when it was logged
     * @return {@code true} if a run the call-out in progress started logged it
     */
    private static boolean below(Runs runs, int depth, long call) {
        return depth > runs.depth && sinceCallOut(runs, call);
    }

    /** Tells whether a failure logged deeper than the innermost run was logged since its call-out started. */
    private static boolean sinceCallOut(Runs runs, long call) {
        return runs.depth >= MARKED_DEPTHS || call >= runs.callStart;
    }

    /**
     * Tells whether a {@link ReportedFailure} was logged below the innermost run in progress on this thread, as
     * {@link #loggedBelow(Logged)} tells for a failure thrown as is: built by a run the call-out in progress in that
     * run started, in the same outermost run. One built on another thread is taken for nested, as a run an action
     * hands to an executor and waits for builds it, and so is one built when no run was in progress, or one
     * deserialized, which say nothing of where they were built, and one built in the same outermost run but before the
     * last {@value #MAX_LOGGED}, as before the engine recorded any. One built on this thread in an outermost run that
     * has ended isn't, nor is one read when no run is in progress.
     *
     * @param failure The failure
     * @return {@code true} if it's taken for logged by a run the call-out in progress in the innermost run in progress
     *         started
     */
    static boolean loggedBelow(ReportedFailure failure) {
        Outermost builtIn = failure.builtIn();
        if (builtIn == null || builtIn.thread != Thread.currentThread().threadId()) {
            return true;
        }
        Runs runs = RUNS.get();
        // The very same outermost run, as an Outermost is equal only to itself.
        if (runs == null || !builtIn.equals(runs.outermost)) {
            return false;
        }
        // Built in this outermost run, so recorded in the ring of reported failures, unless pushed out of it since.
        Logged logged = in(runs.reported, failure);
        return logged == null || below(runs, logged.depth, logged.call);
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
    static Logged find(Throwable thrown) {
        Runs runs = RUNS.get();
        if (runs == null || runs.logged == null) {
            return null;
        }
        return in(runs.logged, thrown);
    }

    /** Finds this very exception in a ring of failures logged, or {@code null} if it isn't there. */
    // The very same instance is what was logged; an equal one would still be news.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static Logged in(Logged[] ring, Throwable thrown) {
        for (Logged logged : ring) {
            if (logged != null && logged.thrown == thrown) {
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
     * Records an exception the engine has just built around a failure as {@link #builtByEngine} does, when a run, a
     * {@code load()} or a {@code validate()} is in progress on this thread, for a caller that may be outside one:
     * {@link io.github.brantunger.unruly.api.language.FactProperties} is public, and a language or a test calls it
     * wherever it likes. Outside one, nothing around the exception could read the record, so it's only returned.
     *
     * @param wrapper The exception the engine built
     * @param <T>     Its type
     * @return {@code wrapper}, for the caller to throw or return
     */
    static <T extends Throwable> T builtByEngineIfInProgress(T wrapper) {
        return RUNS.get() == null ? wrapper : builtByEngine(wrapper);
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
