package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;

import java.util.ArrayList;
import java.util.List;

/**
 * What an exception's cause chain holds of the engine's own failures, and how a failure is described and logged in
 * the light of that: the innermost {@link ReportedFailure}, the first {@link Error}, the failure a nested
 * {@code run()} or {@code load()} logged that names the chain, and the first exception wrapped around that failure
 * that says something of its own (see {@link #below}). It reads the thread's record of what nested runs logged (see
 * {@link LoggedFailures}) and the text helpers of {@link Failures}, and it alone builds a {@link ReportedFailure}
 * (see {@link #report} and {@link #stop}), so neither of those reads a cause chain for the engine's failures, and
 * every failure a run reports is recorded with the thread's record as it's built. The order of those three never
 * loops: this class calls both, and neither calls this. Nothing here logs: the callers log when {@link #unlogged}
 * tells them to, with the description {@link #describe} gives.
 *
 * <p>
 * Nothing here is a lambda, a method reference or a pattern switch, and the class has no static initializer: the
 * first failure in the JVM may be handled deep in a stack, where linking a call site or initializing a class could
 * overflow (#1093). {@code RunClasses} loads this class and {@link Below} when the first engine is built.
 * </p>
 */
final class NestedFailures {

    private NestedFailures() {
    }

    /**
     * Creates the exception for a failure that belongs to no rule, and records it with the thread's record of the
     * failures runs built (see {@link LoggedFailures#reported}): what the cause chain holds is read first, then the
     * failure is built, then recorded. Each of the three factories does those steps itself rather than through a
     * shared method, so that building a failure deep in a stack takes one frame more than its constructor did, not
     * two or three (#1093).
     *
     * @param message What failed
     * @param cause   Why, or {@code null}
     * @return The exception, for the caller to log and throw
     */
    static ReportedFailure report(String message, Throwable cause) {
        ReportedFailure failure = new ReportedFailure(message, cause, null, null, false, null, below(cause));
        failure.builtIn(LoggedFailures.reported(failure));
        return failure;
    }

    /**
     * Creates the exception for one rule's condition or action, as {@link #report(String, Throwable)} creates one for
     * a failure that belongs to no rule.
     *
     * @param message  What failed
     * @param cause    Why
     * @param ruleName The rule's name
     * @param kind     Whether its condition or its action failed
     * @return The exception, for the caller to log and throw
     */
    static ReportedFailure report(String message, Throwable cause, String ruleName, ExpressionKind kind) {
        ReportedFailure failure = new ReportedFailure(message, cause, ruleName, kind, false, null, below(cause));
        failure.builtIn(LoggedFailures.reported(failure));
        return failure;
    }

    /**
     * Creates the exception for a run that stopped because it was interrupted or passed its deadline, as
     * {@link #report(String, Throwable)} creates one for a failure.
     *
     * @param message  Where the run stopped
     * @param cause    An {@link InterruptedException} or a {@link java.util.concurrent.TimeoutException}
     * @param deadline The deadline the run passed, or {@code null} if it was interrupted
     * @return The exception, which belongs to no rule
     */
    static ReportedFailure stop(String message, Exception cause, Deadline deadline) {
        ReportedFailure failure = new ReportedFailure(message, cause, null, null, true, deadline, below(cause));
        failure.builtIn(LoggedFailures.reported(failure));
        return failure;
    }

    /**
     * Finds the {@link Error} of any kind in what was caught: the throwable itself, or one of its causes, or failing
     * that one suppressed on any of them, at any depth, read as {@link Failures#fatalError} reads them. This is what a
     * cancelled run asks before it reports a stop, because an error from Java code a rule calls, such as a method, a
     * getter or a lambda held in a fact, reaches the engine wrapped in the expression language's own exception, or
     * suppressed on an exception of its own, as a {@code try}-with-resources whose {@code close()} fails leaves it, and
     * a run past its deadline or interrupted would otherwise report it as a stop that names no rule rather than as that
     * rule's failure.
     *
     * <p>
     * Unlike {@link Failures#fatalError}, which looks for the first error the engine must not absorb (see
     * {@link Failures#isFatal}) and so may pass over a non-fatal one above it, this finds the first error in the chain
     * whether it is fatal or not.
     * </p>
     *
     * <p>
     * A failure of a {@code run()} started from a condition or action answers for the chain below it: it recorded the
     * error in its own cause chain when it was built, so an error many nested runs down is still found, although each
     * run adds a link and only {@value Failures#MAX_CAUSE_CHAIN_LENGTH} links are read (see {@link #below}).
     * </p>
     *
     * <p>
     * At most {@value Failures#MAX_EXCEPTIONS_READ} exceptions are read, as {@link Failures.Reach} reads them, so an
     * error suppressed past that many is missed.
     * </p>
     *
     * @param thrown What was caught, or {@code null}
     * @return The first error in {@code thrown}'s cause chain, or else the first found among the suppressed exceptions
     *         of its links and what they lead to, in the order {@link Failures.Reach} reads them, or {@code null} if
     *         there is none among the exceptions read
     */
    static Error errorInChain(Throwable thrown) {
        List<Throwable> chain = Failures.causeChain(thrown);
        Error error = below(chain).error();
        return error != null ? error : (Error) Failures.suppressedMatching(chain, Failures.ANY_ERROR, null);
    }

    /**
     * Describes an exception for an error message:
     * <ul>
     *     <li>its message, or its class name if it has none (NPEs and bare RuntimeExceptions carry no message)</li>
     *     <li>the class of its root cause when that has no message either, and the root cause's class and message
     *     when an exception above it has none: MVEL copies a cause's missing message into its own as
     *     {@code ": null"}, which would hide what went wrong</li>
     *     <li>at most {@value Failures#MAX_DESCRIPTION_LENGTH} characters of the message: MVEL pads its messages with
     *     spaces up to the error's column, so a long expression produced messages hundreds of thousands of characters
     *     long</li>
     *     <li>the message {@link Failures#escape escaped}, because the engine didn't write it: a language quotes the
     *     fact values a failing expression read, and those come from request data far more often than names do</li>
     *     <li>its class name and a note that its message is unavailable when {@code getMessage()} throws (see
     *     {@link Failures#messageOf}), and likewise for a cause</li>
     * </ul>
     * The message is shortened before it's escaped, so the count of what was left out counts the exception's own
     * characters. Every message is shortened as {@link Failures#clip} shortens it, never inside an escape, with up to 5
     * fewer characters shown: the message of an exception the engine built, or of one that copies such a message,
     * holds escapes already, and the count counts it as it's held, escaped where it was escaped. The exception is
     * never changed: its {@code getMessage()} still reads as the language wrote it.
     * A failure of a {@code run()} or a {@code load()} started from a condition, an action, the output supplier, a
     * listener callback or a language is described by the innermost failure it logged only, as
     * {@code a nested run() failed: } or {@code a nested load() failed: } and that failure, so a failure nested many
     * runs deep isn't repeated once per level (see {@link #nestedRunFailure}). That holds while every exception
     * wrapped around it adds nothing to it (see {@link #below}). Otherwise the first exception from the top of the
     * chain with a message of its own, one that has the nested failure's text with words around it too, is described
     * by that message, shortened and escaped, with the nested failure as a note,
     * {@code (after a nested run() failed: ...)} or {@code (after a nested load() failed: ...)}, so neither is lost.
     * The code that wrapped it is the call-out in progress in the innermost run in progress on the thread, such as
     * the condition, the action, the listener callback, the output supplier or the language call the run is in (see
     * {@link LoggedFailures#callOut()}). When no run that call-out started logged that failure, but a run that had
     * ended before it started, in the same outermost run, such as one an earlier sibling, an earlier rule, the same
     * rule's condition or a listener started, however deep, the note reads {@code (caused by ..., already logged)}
     * instead, and so it does for a {@link ReportedFailure} built in an earlier outermost run on the thread (see
     * {@link LoggedFailures#loggedBelow(ReportedFailure)}). A failure thrown as is in an earlier outermost run, such as
     * facts a nested run rejected, isn't found at all, so the exception around it reads by its own message, with no
     * nested run's note, and a {@link ReportedFailure} built in the same outermost run before the last
     * {@value LoggedFailures#MAX_LOGGED} is noted as a nested run's.
     * Where every exception wrapped around such a failure logged already adds nothing to it, as when the code throws it
     * on as is or as {@code new RuntimeException(e)}, it's described by its text alone, followed by
     * {@code (already logged)}, not as a nested run's: the text as {@link Failures#loggedText} shortens it, so the note
     * comes after any count of what was left out. That holds only for what such code throws itself: a run further out,
     * around the run whose rule threw it, still names the failure as a nested run's.
     * The note's text is shortened to {@value Failures#MAX_DESCRIPTION_LENGTH} characters as a whole, a hidden root
     * cause's note included, before it's escaped, so the count of what was left out counts the characters as they were
     * written (see {@link Failures#noteText}). A nested failure's text is mostly text the engine has already escaped,
     * so where it's shortened, in a note or alone (see {@link Failures#loggedText}), it's shortened as
     * {@link Failures#clip} shortens it too: never inside an escape, and counting the characters of the nested
     * failure's message as it's held. A fatal {@link Error} logged already, wrapped in an exception that says something
     * of its own (see {@link #wrapsLoggedFatal}), is a note the same way, described with its class, as
     * {@link Failures#describeWithClass} describes it: {@code (after a nested run() failed: ...)} or
     * {@code (after a nested load() failed: ...)} when what the call-out that wrapped it started logged it, or else
     * {@code (caused by ..., already logged)} when the error is in the cause chain, or
     * {@code (with suppressed ..., already logged)} when it's found among the suppressed exceptions of the chain's
     * links or what they lead to (see {@link Failures#fatalError}).
     * The note is read from what was logged when this is called, so a caller describes what it caught before it asks
     * {@link #unlogged}, which records a fatal error it's told of.
     *
     * @param e The exception to describe
     * @return A description of the exception for an error message
     */
    static String describe(Throwable e) {
        // Read once, so where the fatal error is and what's around it are told from the same chain.
        List<Throwable> chain = Failures.causeChain(e);
        Below below = below(chain);
        Throwable logged = below.logged();
        if (logged == null) {
            Error fatal = Failures.fatalErrorIn(chain);
            LoggedFailures.LoggedAt at = fatal == null ? null : LoggedFailures.loggedAt(fatal);
            Throwable news = at == null ? null : newsAbove(chain, fatal);
            if (news != null) {
                // The error found is in the chain itself whenever the chain holds one, and suppressed on it otherwise.
                String how = Failures.fatalIn(chain) != null ? "caused by " : "with suppressed ";
                String note = at == LoggedFailures.LoggedAt.NOT_BELOW
                        ? alreadyLogged(how, Failures.describeWithClass(fatal))
                        : "after " + nested(at == LoggedFailures.LoggedAt.NESTED_LOAD)
                                + Failures.describeWithClass(fatal);
                return withNote(news, note);
            }
            String text = Failures.clip(Failures.messageOr(e, e.getClass().getName()));
            return text + Failures.causeNote(chain, Failures.shownOf(Failures.readableMessage(e)));
        }
        String nested = nested(below.loggedByLoad());
        Throwable news = below.news();
        if (news == null) {
            return below.loggedBelow() ? nested + Failures.loggedText(logged)
                    : Failures.loggedText(logged) + " (already logged)";
        }
        // The failure is in the chain, which is where it was found.
        return withNote(news, below.loggedBelow() ? "after " + nested + Failures.noteText(logged)
                : alreadyLogged("caused by ", Failures.noteText(logged)));
    }

    /**
     * Describes what a language threw from a call other than compiling an expression, such as {@code prepare},
     * {@code newCompiler}, {@code factNamesRead}, {@code checkFactName}, {@code warmUp} or {@code newSession}, or
     * from a condition or an action a run evaluates, as {@link #describe} does, unless it's an overflow (see
     * {@link Failures#isOverflow}): its class alone would say nothing of why. Compiling an expression says why in
     * words of its own (see {@code RuleListCompiler.overflowReason()}).
     *
     * @param e        What the language threw
     * @param loadTime {@code true} for a call made about the rules, {@code newCompiler}, {@code factNamesRead},
     *                 {@code checkFactName} or {@code warmUp}, whose overflow may come from rule text too long or too
     *                 deeply nested for the language; {@code false} for {@code newSession}, which a run makes for its
     *                 copy, {@code prepare}, which reads no rule text, and a condition or an action that a run
     *                 evaluates
     * @return {@code the stack overflowed}, followed for a load-time call by
     *         {@code : the rule text may be too long or too deeply nested for the language}, or else the description
     */
    static String describeCallOut(Throwable e, boolean loadTime) {
        if (!Failures.isOverflow(e)) {
            return describe(e);
        }
        return loadTime ? "the stack overflowed: the rule text may be too long or too deeply nested for the language"
                : "the stack overflowed";
    }

    /** Says what failed below a description: {@code a nested run() failed: } or {@code a nested load() failed: }. */
    private static String nested(boolean byLoad) {
        return "a nested " + (byLoad ? "load()" : "run()") + " failed: ";
    }

    /**
     * Says a failure was logged already, by no run the call-out that wrapped it started, for the note
     * {@link #describe} adds: {@code caused by ..., already logged} or {@code with suppressed ..., already logged}.
     *
     * @param how  {@code "caused by "} or {@code "with suppressed "}, where the failure was found
     * @param text The failure's text
     * @return The note
     */
    private static String alreadyLogged(String how, String text) {
        return how + text + ", already logged";
    }

    /**
     * Describes an exception that says something of its own by its message, shortened and escaped, with a note.
     *
     * @param news The exception
     * @param note The note, which goes in parentheses after the message
     * @return The description
     */
    private static String withNote(Throwable news, String note) {
        return Failures.clip(Failures.messageOr(news, news.getClass().getName())) + " (" + note + ")";
    }

    /**
     * Tells whether what was caught still has to be logged: not when it's a nested run's or load's failure, which
     * that run or load logged, nor when its fatal {@link Error} was logged already (see
     * {@link LoggedFailures#unloggedFatal}), unless an exception wrapped around that error says something of its own
     * (see {@link #wrapsLoggedFatal}): the caller logs that, with the error as a note (see {@link #describe}), and
     * still rethrows the error. A fatal error it has is recorded as logged, so the caller must log it when this
     * returns {@code true}.
     *
     * @param thrown What was caught
     * @return {@code true} if the caller logs it
     */
    static boolean unlogged(Throwable thrown) {
        Error fatal = Failures.fatalError(thrown);
        if (fatal == null) {
            return nestedRunFailure(thrown) == null;
        }
        return LoggedFailures.unloggedFatal(fatal) || newsAbove(thrown, fatal) != null;
    }

    /**
     * Tells whether what was caught has been logged already, as {@link #unlogged} does, without recording anything, for
     * a caller that logs at WARN what it doesn't throw, such as a listener's exception or a failure to close a session:
     * a nested run's or load's failure (see {@link #nestedRunFailure}), or a fatal {@link Error} a run in progress on
     * this thread logged (see {@link LoggedFailures#loggedAt}), when nothing wrapped around it says something of its
     * own (see {@link #newsAbove}). A fatal error isn't recorded here: logging one at WARN doesn't count as logging it
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
            return nestedRunFailure(thrown) != null;
        }
        return LoggedFailures.loggedAt(fatal) != null && newsAbove(thrown, fatal) == null;
    }

    /**
     * Tells whether what was caught wraps a fatal {@link Error} that was logged already on this thread (see
     * {@link LoggedFailures#loggedAt}) in an exception that says something of its own (see {@link #newsAbove}), such
     * as {@code new IllegalStateException("audit write failed", e)} around what a nested {@code run()} threw. Nothing
     * logged that exception's message, so the code that caught it logs it, as {@link #describe} describes it, and
     * rethrows the error.
     *
     * @param thrown What was caught
     * @return {@code true} if the caller logs it for what's wrapped around the error
     */
    static boolean wrapsLoggedFatal(Throwable thrown) {
        Error fatal = Failures.fatalError(thrown);
        return fatal != null && LoggedFailures.loggedAt(fatal) != null && newsAbove(thrown, fatal) != null;
    }

    /**
     * Finds the failure a {@code run()} or a {@code load()} started by the code that threw {@code e} logged. That run
     * or load already logged it, and a run told its listeners. Only a failure an engine threw counts: a
     * {@link ReportedFailure}, of any engine, or a failure a nested run or load on this thread logged and threw as is,
     * such as a fact it rejected or a rule that failed to compile (see {@link LoggedFailures}). A
     * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} a language or a rule throws itself was
     * never logged. Nor was an exception wrapped around that failure with a message of its own (see {@link #below}),
     * so a chain that holds one is news, and is taken for no nested run's failure here.
     *
     * @param e What was caught, or {@code null}
     * @return That failure, the innermost if there are several (see {@link #below}), or {@code null} if {@code e}'s
     *         cause chain holds none, or an exception with a message of its own is wrapped around it
     */
    static Throwable nestedRunFailure(Throwable e) {
        Below below = below(e);
        return below.news() == null ? below.logged() : null;
    }

    /**
     * Tells whether a {@code run()} started by the code that threw {@code e} stopped for the same reason as the run
     * around it, and so already logged that stop.
     *
     * @param e        What was caught
     * @param deadline The deadline the run around it passed, or {@code null} if its thread was interrupted
     * @return {@code true} if the innermost {@link ReportedFailure} in {@code e}'s cause chain is that same stop, or
     *         one is found among the suppressed exceptions of its links and what they lead to, as
     *         {@link Failures#fatalError} reads them, as a {@code try}-with-resources whose {@code close()} started
     *         that run leaves it
     */
    static boolean nestedRunStopped(Throwable e, Deadline deadline) {
        List<Throwable> chain = Failures.causeChain(e);
        ReportedFailure innermost = below(chain).innermost();
        if (innermost != null && innermost.isStopFor(deadline)) {
            return true;
        }
        return Failures.suppressedMatching(chain, Failures.STOP, deadline) != null;
    }

    /**
     * Finds the innermost {@link ReportedFailure} in an exception's cause chain, however many nested runs deep (see
     * {@link #below}).
     *
     * @param e What was caught, or {@code null}
     * @return The innermost {@link ReportedFailure} in {@code e}'s cause chain, or {@code null} if there is none
     */
    static ReportedFailure innermostReported(Throwable e) {
        return below(e).innermost();
    }

    /**
     * What an exception's cause chain holds of the engine's own failures: the innermost {@link ReportedFailure}, the
     * first {@link Error}, the failure a nested run or load logged that names the chain, and the first exception
     * wrapped around that failure that says something of its own (see {@link #below}). A plain class, not a record:
     * nothing compares or prints one, and a record's own methods would link through {@code ObjectMethods}, which can
     * fail for good when first called deep in the stack (#996).
     */
    // Each accessor is named after the field it returns, as a record's are.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    static final class Below {
        private final ReportedFailure innermost;
        private final Error error;
        private final Throwable logged;
        private final boolean loggedByLoad;
        private final Throwable news;
        private final boolean loggedBelow;

        /**
         * @param innermost    The innermost failure of a nested {@code run()}, or {@code null} if there is none
         * @param error        The first error, or {@code null} if there is none
         * @param logged       The failure a nested run or load logged that names the chain, or {@code null} if there
         *                     is none (see {@link #below})
         * @param loggedByLoad {@code true} if a {@code load()} logged {@code logged}, {@code false} if a {@code run()}
         *                     did
         * @param news         The first exception from the top of the chain, above {@code logged}, with a message of
         *                     its own, which nothing has logged, or {@code null} if there is none, and {@code logged}
         *                     names the chain whole
         * @param loggedBelow  {@code true} if a run the call-out in progress in the innermost run in progress on this
         *                     thread started logged {@code logged}, however deep below it (see
         *                     {@link LoggedFailures#callOut()}), or a run on another thread did, or it names the
         *                     chain of a {@link ReportedFailure} built in the same outermost run before the last
         *                     {@value LoggedFailures#MAX_LOGGED}, or one built outside any run, or one deserialized;
         *                     {@code false} if a run that had ended before that call-out started did, such as one an
         *                     earlier call-out of the same run started, or {@code logged} names a
         *                     {@link ReportedFailure}'s chain and that failure was built in an earlier outermost run
         *                     on this thread (see {@link LoggedFailures#loggedBelow(ReportedFailure)}), or if there
         *                     is no {@code logged}
         */
        Below(ReportedFailure innermost, Error error, Throwable logged, boolean loggedByLoad, Throwable news,
              boolean loggedBelow) {
            this.innermost = innermost;
            this.error = error;
            this.logged = logged;
            this.loggedByLoad = loggedByLoad;
            this.news = news;
            this.loggedBelow = loggedBelow;
        }

        /** The innermost failure of a nested {@code run()}, or {@code null} if there is none. */
        ReportedFailure innermost() {
            return innermost;
        }

        /** The first error, or {@code null} if there is none. */
        Error error() {
            return error;
        }

        /** The failure a nested run or load logged that names the chain, or {@code null} if there is none. */
        Throwable logged() {
            return logged;
        }

        /** Whether a {@code load()} logged {@link #logged()}, rather than a {@code run()}. */
        boolean loggedByLoad() {
            return loggedByLoad;
        }

        /** The first exception above {@link #logged()} with a message of its own, or {@code null} if there is none. */
        Throwable news() {
            return news;
        }

        /** Whether a run the call-out in progress in the innermost run in progress started logged {@link #logged()}. */
        boolean loggedBelow() {
            return loggedBelow;
        }
    }

    /**
     * Reads an exception's cause chain for the innermost {@link ReportedFailure}, the first {@link Error} and the
     * failure a nested run or load logged, in one walk. Every nested run adds a link to the chain and only
     * {@value Failures#MAX_CAUSE_CHAIN_LENGTH} links are read, so a failure or an error more than that many runs down
     * would be out of reach; but each {@link ReportedFailure} recorded all three for its own chain when it was built,
     * so the walk stops at the first one that did. One serialized before they were recorded answers for nothing: the
     * walk goes on past it, and if it finds no failure that recorded them, the last of those in the chain is the
     * innermost, as before.
     *
     * <p>
     * The failure logged is the first link above that one that a nested run or load on this thread recorded as
     * logged (see {@link LoggedFailures#find}), which names the chain whole: a {@code load()} that failed for several
     * rules is named by the failure that lists them all, not by the first rule's, which it logged too. Failing that,
     * it's the one the {@link ReportedFailure} recorded, so a failure many runs down is named, not each run's failure
     * around it; and failing that, the innermost {@link ReportedFailure}.
     * </p>
     *
     * <p>
     * That failure names the chain only while every link above it adds nothing to it (see {@link #isNews}): a
     * {@link ReportedFailure} or another exception the engine built around it (see
     * {@link LoggedFailures#builtByEngine}), which the engine wrote, or an exception with no message, one whose message
     * is its cause's {@code toString()}, as {@code new RuntimeException(cause)} makes, or its cause's message, or one
     * whose message is the nested failure's text. Otherwise the first link that says something of its own is the
     * news, which nothing has logged, even when its message has the nested failure's text with words of its own around
     * it: the code that caught the chain logs it, and describes it with the nested failure as a note (see
     * {@link #describe}).
     * </p>
     *
     * @param e What was caught, or {@code null}
     * @return The innermost failure, the first error, the failure logged and the first link above it with a message of
     *         its own, each {@code null} if the chain has none
     */
    static Below below(Throwable e) {
        return below(Failures.causeChain(e));
    }

    /**
     * Reads a cause chain as {@link #below(Throwable)} reads an exception's.
     *
     * @param chain An exception and its causes (see {@link Failures#causeChain})
     * @return What the chain holds of the engine's own failures
     */
    private static Below below(List<Throwable> chain) {
        ReportedFailure innermost = null;
        int innermostAt = -1;
        Error error = null;
        LoggedFailures.Logged logged = null;
        int loggedAt = -1;
        for (int i = 0; i < chain.size(); i++) {
            Throwable t = chain.get(i);
            if (t instanceof ReportedFailure failure) {
                if (failure.recorded()) {
                    Error first = error != null ? error : failure.error();
                    // Where the failure that names the chain was logged is told by the link the code that caught
                    // it was given: the failure a nested run threw as is, or this one, which a run built.
                    return logged != null
                            ? below(chain, loggedAt, failure.innermost(), first, logged)
                            : below(chain, i, failure.innermost(), first, failure.logged(), failure.loggedByLoad(),
                                    LoggedFailures.loggedBelow(failure));
                }
                innermost = failure;
                innermostAt = i;
                continue;
            }
            if (error == null && t instanceof Error found) {
                error = found;
            }
            if (logged == null) {
                logged = LoggedFailures.find(t);
                loggedAt = i;
            }
        }
        // A failure that recorded nothing was serialized, which says nothing of where it was built, so it's taken for
        // a nested run's, as one from another thread is.
        return logged != null
                ? below(chain, loggedAt, innermost, error, logged)
                : below(chain, innermostAt, innermost, error, innermost, false, innermost != null);
    }

    /**
     * Makes what {@link #below} found, when the failure logged is one a nested run or load on this thread recorded.
     *
     * @param chain    The cause chain
     * @param loggedAt Where in {@code chain} the failure logged is
     */
    private static Below below(List<Throwable> chain, int loggedAt, ReportedFailure innermost, Error error,
                               LoggedFailures.Logged logged) {
        return below(chain, loggedAt, innermost, error, logged.failure(), logged.byLoad(),
                LoggedFailures.loggedBelow(logged));
    }

    /**
     * Makes what {@link #below} found, with the first link above the failure logged that says something of its own.
     *
     * @param chain    The cause chain
     * @param loggedAt Where in {@code chain} the failure logged is, or the failure that recorded it; {@code -1} if
     *                 there is none
     */
    private static Below below(List<Throwable> chain, int loggedAt, ReportedFailure innermost, Error error,
                               Throwable logged, boolean byLoad, boolean loggedBelow) {
        return new Below(innermost, error, logged, byLoad, firstNews(chain, loggedAt, logged), loggedBelow);
    }

    /**
     * Finds the first link of a cause chain, from the top, above a failure a nested run or load logged, that says
     * something of its own (see {@link #isNews}).
     *
     * @param chain    The cause chain
     * @param loggedAt Where in {@code chain} that failure is, or the failure that recorded it
     * @param nested   That failure
     * @return The link, or {@code null} if every link above {@code loggedAt} adds nothing to {@code nested}
     */
    private static Throwable firstNews(List<Throwable> chain, int loggedAt, Throwable nested) {
        for (int i = 0; i < loggedAt; i++) {
            if (isNews(chain.get(i), chain.get(i + 1), nested)) {
                return chain.get(i);
            }
        }
        return null;
    }

    /**
     * Tells whether one link of a cause chain, above a failure a nested run or load logged, says something that
     * failure doesn't: it has a message of its own, one that can be read, isn't its cause's {@code toString()} or
     * message, and isn't the nested failure's text, as the engine writes it or as it is. So an exception rethrown with
     * its cause's message, as {@code new IllegalStateException(e.getMessage(), e)} rethrows the failure of a run whose
     * own nested run failed, adds nothing. A message with words of its own around that text is news. A
     * {@link ReportedFailure} never is, nor another exception the engine built around the failure, whatever its
     * message reads (see {@link LoggedFailures#builtByEngine}): the engine wrote it, around the nested failure, and
     * it's known by the very instance, not by its text, which an application's exception can repeat. The same test
     * tells a fatal {@link Error} a nested run logged from something new wrapped around it (see {@link #newsAbove}).
     *
     * @param link   The link
     * @param cause  The next link, its cause
     * @param nested The failure logged below it
     * @return {@code true} if nothing has logged what {@code link} says
     */
    private static boolean isNews(Throwable link, Throwable cause, Throwable nested) {
        if (link instanceof ReportedFailure || LoggedFailures.isEngineWrapper(link)) {
            return false;
        }
        String message = Failures.readableMessage(link);
        return message != null && !message.equals(Failures.readableText(cause))
                && !message.equals(Failures.readableMessage(cause)) && !message.equals(Failures.loggedText(nested))
                && !message.equals(Failures.readableMessage(nested));
    }

    /**
     * Finds what, wrapped around a fatal {@link Error} in what was caught, says something of its own (see
     * {@link #isNews}), which nothing logged when a nested run logged the error. A fatal error suppressed on a link
     * (see {@link Failures#fatalError}), rather than in the cause chain, is taken for below every link, so the last
     * link, such as the body's exception a {@code try}-with-resources threw, is news when it says something of its own
     * too. One found in neither, as a {@code getCause()} that gives a different chain each time it's read leaves it,
     * is taken for the last link.
     *
     * @param thrown What was caught
     * @param fatal  The fatal error among its causes, or suppressed on one of them (see {@link Failures#fatalError})
     * @return The first link from the top above {@code fatal} with a message of its own, or {@code null} if there is
     *         none
     */
    static Throwable newsAbove(Throwable thrown, Error fatal) {
        return newsAbove(Failures.causeChain(thrown), fatal);
    }

    /**
     * Finds what says something of its own around a fatal {@link Error} in a cause chain read once, as
     * {@link #newsAbove(Throwable, Error)} finds it in what was caught.
     *
     * @param chain An exception and its causes (see {@link Failures#causeChain}), which isn't changed
     * @param fatal The fatal error in the chain, or suppressed on a link of it
     * @return The first link from the top above {@code fatal} with a message of its own, or {@code null} if there is
     *         none
     */
    // The very same instance: an equal one is another error.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static Throwable newsAbove(List<Throwable> chain, Error fatal) {
        int fatalAt = 0;
        // Bounded by the chain, which a getCause() of its own may not give the same way twice.
        while (fatalAt < chain.size() - 1 && chain.get(fatalAt) != fatal) {
            fatalAt++;
        }
        if (chain.get(fatalAt) != fatal && Failures.suppressedMatching(chain, Failures.SAME, fatal) != null) {
            // Suppressed on a link, so below every link.
            List<Throwable> links = new ArrayList<>(chain);
            links.add(fatal);
            return firstNews(links, chain.size(), fatal);
        }
        return firstNews(chain, fatalAt, fatal);
    }
}
