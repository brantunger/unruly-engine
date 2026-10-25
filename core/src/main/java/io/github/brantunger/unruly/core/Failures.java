package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.internal.Causes;
import io.github.brantunger.unruly.internal.Text;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * How the engine treats what rules, listeners and expression languages throw: which errors must reach the caller
 * unchanged, and how an exception is described in an error message. Only {@link #closingInsteadOf},
 * {@link #insteadOf}, {@link #keepAlso} and {@link #reportCalledCodeFailure} log, and under the engine's logger name,
 * {@code io.github.brantunger.unruly.engine}, as every failure is logged. What was thrown is read as {@link Causes}
 * reads it, and text is shortened and escaped as {@link Text} does it; {@code api.language} publishes both, and
 * {@link FatalErrors} the search for a fatal error made here.
 */
final class Failures {

    /**
     * How many different exceptions, through causes and suppressed exceptions, the engine reads to learn whether one
     * already reaches another (see {@link #reaches}), or to find a fatal {@link Error} or any {@link Error} suppressed
     * in what was caught (see {@link #fatalError} and {@link NestedFailures#errorInChain}); one past that many isn't
     * found.
     */
    static final int MAX_EXCEPTIONS_READ = 10_000;

    // What a Reach looks for (see Reach#matches): a fatal error, any error, an interrupt, the stop of a run past a
    // deadline or interrupted, or the very instance given. Written as numbers that pick plain code rather than as
    // lambdas, so the first failure in the JVM links no call site, which could overflow deep in a stack (#1093).
    private static final int FATAL = 0;
    static final int ANY_ERROR = 1;
    private static final int INTERRUPT = 2;
    static final int STOP = 3;
    static final int SAME = 4;

    private static final Logger log = LoggerFactory.getLogger(CoreRulesEngine.LOGGER_NAME);

    private Failures() {
    }

    /**
     * Finds the fatal {@link Error} (see {@link Causes#isFatal}) in what was caught: the throwable itself, or one of
     * its causes, or failing that one suppressed on any of them, or among the causes and suppressed exceptions of
     * those. An error from Java code a rule calls, such as a method, a getter or a lambda held in a fact, reaches the
     * engine inside MVEL's own exception, so checking only the outer exception let an {@link OutOfMemoryError} be
     * absorbed into a {@link RuleExecutionException}; and a {@code try}-with-resources whose {@code close()} hits one
     * while its body throws leaves it suppressed on the body's exception, so checking only the cause chain let it be
     * absorbed too.
     *
     * <p>
     * A suppressed exception the engine itself added to a failure it reported isn't read (see
     * {@link ReportedFailure#addSuppressedByEngine}): the engine keeps there a fatal error a listener threw, beside the
     * one it rethrows, and finding that one again would take it for the failure's own. Every other suppressed exception
     * is read, one that code outside the engine added to a failure the engine reported too, as a
     * {@code try}-with-resources around a nested run whose {@code close()} fails adds one. The suppressed exceptions
     * are read as {@link Reach} reads them: the direct suppressed exceptions of every link, from the top, before what
     * any of them leads to. At most {@value #MAX_EXCEPTIONS_READ} exceptions are read, so a fatal error past that many
     * is missed.
     * </p>
     *
     * @param thrown What was caught, or {@code null}
     * @return The first fatal error in {@code thrown}'s cause chain, or else the first found among the suppressed
     *         exceptions of its links and what they lead to, in the order {@link Reach} reads them, or {@code null} if
     *         there is none among the exceptions read
     */
    static Error fatalError(Throwable thrown) {
        return fatalErrorIn(Causes.causeChain(thrown));
    }

    /**
     * Finds the fatal {@link Error} in a cause chain read once, as {@link #fatalError} finds it in what was caught,
     * for a caller that goes on to ask where in that same chain it is.
     *
     * @param chain An exception and its causes (see {@link Causes#causeChain})
     * @return The fatal error {@link #fatalError} returns for it, or {@code null}
     */
    static Error fatalErrorIn(List<Throwable> chain) {
        Error fatal = fatalIn(chain);
        return fatal != null ? fatal : (Error) suppressedMatching(chain, FATAL, null);
    }

    /** Finds the first fatal {@link Error} in a cause chain itself, or {@code null} if there is none. */
    static Error fatalIn(List<Throwable> chain) {
        for (Throwable t : chain) {
            if (Causes.isFatal(t)) {
                return (Error) t;
            }
        }
        return null;
    }

    /**
     * Finds an exception that matches among the suppressed exceptions of a cause chain, and what they lead to, read as
     * {@link Reach} reads them. A suppressed exception the engine itself added to a failure it reported isn't read
     * (see {@link ReportedFailure#addSuppressedByEngine}). A chain with no suppressed exceptions allocates nothing.
     *
     * @param chain  A cause chain, none of whose links matches, which counts as read
     * @param match  What to look for, as {@link Reach#matches} tells it
     * @param target The deadline or the instance it looks for, if it looks for one, or else {@code null}
     * @return The first exception found, or {@code null} if there is none, or if it's past the
     *         {@value #MAX_EXCEPTIONS_READ} exceptions read
     */
    static Throwable suppressedMatching(List<Throwable> chain, int match, Object target) {
        Throwable[][] suppressed = null;
        for (int i = 0; i < chain.size(); i++) {
            Throwable[] ofLink = chain.get(i).getSuppressed();
            if (ofLink.length > 0) {
                if (suppressed == null) {
                    suppressed = new Throwable[chain.size()][];
                }
                suppressed[i] = ofLink;
            }
        }
        return suppressed == null ? null : new Reach(false, match, target).beside(suppressed, chain);
    }

    /**
     * A walk through exceptions, their causes and their suppressed exceptions, as {@link #reaches} and
     * {@link #suppressedMatching} make it. Reading an exception counts it and checks it. For each exception the walk
     * goes into, it reads the exception's cause chain first, each link in order; then it reads the direct suppressed
     * exceptions of each link of that chain, from the top link down, each in order, before it goes into any of them;
     * then it goes into each of those in the same order, the same way, depth first. A cause is read as
     * {@link Causes#causeOf} reads it, so a {@code getCause()} that throws ends that chain; so does a link read
     * already. {@code getSuppressed()} is final, and read once for each exception. Each exception is read once, and at
     * most {@value #MAX_EXCEPTIONS_READ} different ones are, the links a walk starts beside included, so a graph with
     * loops of its own, a {@code getCause()} that returns a new exception every time, or a great many suppressed
     * exceptions end it too; one past that many is never found. It ends as soon as an exception matches or that many
     * are read.
     * Besides the set of what it read, the walk holds a list of the suppressed exceptions it read for each exception
     * it's going into, each exception in one list at most, so no more than that many exceptions; the copy
     * {@code getSuppressed()} returned for each link of the chain it starts beside, for as long as it walks; and,
     * deeper, one more such copy at a time, only while it reads it.
     */
    static final class Reach {
        private final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        // The exceptions read and still to go into, a list for each that was gone into, the next on top.
        private final Deque<Deeper> deeper = new ArrayDeque<>();
        // Whether a suppressed exception the engine added to a failure it reported is read too.
        private final boolean engineAddedToo;
        // What it looks for, as matches() tells it, and the deadline or the instance it looks for, if any.
        private final int match;
        private final Object target;
        private Throwable found;
        private boolean stopped;

        Reach(boolean engineAddedToo, int match, Object target) {
            this.engineAddedToo = engineAddedToo;
            this.match = match;
            this.target = target;
        }

        /**
         * Walks beside a cause chain read already, from the suppressed exceptions of its links.
         *
         * @param chain      The chain
         * @param suppressed The suppressed exceptions of each link, or {@code null} for a link with none
         * @return The first exception that matches, or {@code null} if there is none
         */
        Throwable beside(Throwable[][] suppressed, List<Throwable> chain) {
            seen.addAll(chain);
            List<Throwable> tested = List.of();
            for (int i = 0; i < chain.size(); i++) {
                if (suppressed[i] != null) {
                    tested = readSuppressed(chain.get(i), suppressed[i], tested);
                }
            }
            return walk(tested);
        }

        /**
         * Walks from an exception: reads it, then goes into it.
         *
         * @return The first exception that matches, {@code t} itself included, or {@code null} if there is none
         */
        Throwable from(Throwable t) {
            return read(t) ? walk(goInto(t)) : found;
        }

        /** Counts and checks an exception; {@code true} if it was read for the first time, and didn't match. */
        private boolean read(Throwable t) {
            if (seen.size() >= MAX_EXCEPTIONS_READ) {
                stopped = true;
                return false;
            }
            if (!seen.add(t)) {
                return false;
            }
            if (matches(t)) {
                found = t;
                stopped = true;
                return false;
            }
            return true;
        }

        /**
         * Goes into an exception read already: reads its causes, then the direct suppressed exceptions of it and of
         * each cause.
         *
         * @return The suppressed exceptions read, to go into next, which may be none
         */
        private List<Throwable> goInto(Throwable t) {
            Throwable cause = Causes.causeOf(t);
            if (cause == null) {
                return readSuppressed(t, t.getSuppressed(), List.of());
            }
            List<Throwable> chain = new ArrayList<>();
            chain.add(t);
            for (Throwable link = cause; link != null && read(link); link = Causes.causeOf(link)) {
                chain.add(link);
            }
            List<Throwable> tested = List.of();
            for (int i = 0; i < chain.size() && !stopped; i++) {
                tested = readSuppressed(chain.get(i), chain.get(i).getSuppressed(), tested);
            }
            return tested;
        }

        /** Reads the direct suppressed exceptions of {@code owner}, adding those read for the first time to a list. */
        private List<Throwable> readSuppressed(Throwable owner, Throwable[] suppressed, List<Throwable> tested) {
            List<Throwable> result = tested;
            for (int i = 0; i < suppressed.length && !stopped; i++) {
                if ((engineAddedToo || !addedByEngine(owner, suppressed[i])) && read(suppressed[i])) {
                    if (result.isEmpty()) {
                        result = new ArrayList<>();
                    }
                    result.add(suppressed[i]);
                }
            }
            return result;
        }

        /**
         * Tells whether an exception is what the walk looks for: with {@link #FATAL}, a fatal {@link Error} (see
         * {@link Causes#isFatal}); with {@link #ANY_ERROR}, any error; with {@link #INTERRUPT}, an
         * {@link InterruptedException}; with {@link #STOP}, the stop of a run past the deadline the walk was given,
         * or interrupted if it was given none (see {@link ReportedFailure#isStopFor}); and with {@link #SAME}, the
         * very instance it was given.
         */
        // The very same instance: an equal one is another failure.
        @SuppressWarnings("PMD.CompareObjectsWithEquals")
        private boolean matches(Throwable t) {
            return switch (match) {
                case FATAL -> Causes.isFatal(t);
                case ANY_ERROR -> t instanceof Error;
                case INTERRUPT -> t instanceof InterruptedException;
                case STOP -> t instanceof ReportedFailure reported && reported.isStopFor((Deadline) target);
                default -> t == target;
            };
        }

        private static boolean addedByEngine(Throwable owner, Throwable suppressed) {
            return owner instanceof ReportedFailure failure && failure.suppressedByEngine(suppressed);
        }

        /** Goes into what's been read, depth first, until an exception matches or the limit is reached. */
        private Throwable walk(List<Throwable> tested) {
            push(tested);
            while (!stopped && !deeper.isEmpty()) {
                Deeper next = deeper.peek();
                if (next.index == next.exceptions.size()) {
                    deeper.pop();
                } else {
                    Throwable t = next.exceptions.get(next.index);
                    next.index++;
                    push(goInto(t));
                }
            }
            return found;
        }

        private void push(List<Throwable> tested) {
            if (!tested.isEmpty() && !stopped) {
                deeper.push(new Deeper(tested));
            }
        }
    }

    /** Exceptions a {@link Reach} has read and goes into in turn, and how many it has gone into. */
    static final class Deeper {
        private final List<Throwable> exceptions;
        private int index;

        Deeper(List<Throwable> exceptions) {
            this.exceptions = exceptions;
        }
    }

    static void throwIfPresent(Error fatal) {
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Throws what was thrown, if anything, as it is, whatever its type: an unchecked exception or an {@link Error},
     * or a checked exception a language's code threw undeclared, which the caller would have had thrown as it is too.
     * Unlike {@link #throwIfPresent}, which is given only a fatal error.
     *
     * @param failure What was thrown, or {@code null}
     * @param <T>     The type the compiler takes it for: an unchecked one, so the caller declares nothing
     * @throws T {@code failure}, unless it's {@code null}
     */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> void rethrowUnchecked(Throwable failure) throws T {
        if (failure != null) {
            throw (T) failure;
        }
    }

    /**
     * Chooses which of two throwables, from two steps of a clean-up one after the other, the caller throws, so that a
     * fatal {@link Error} (see {@link #fatalError}) is never dropped for one that isn't: the fatal error in
     * {@code second} if {@code first} carries none, and else {@code first}. What's chosen carries the other as a
     * suppressed exception, as {@link #keepAlso} keeps it; but a {@code second} that wraps the fatal error chosen
     * from it isn't kept on that error: it reaches the error already, and keeping it would make a loop of causes
     * and suppressed exceptions.
     *
     * @param first  What the earlier step threw, or {@code null}
     * @param second What the later step threw, or {@code null}
     * @return What the caller throws, or {@code null} if neither threw
     */
    static Throwable fatalFirst(Throwable first, Throwable second) {
        if (second == null) {
            return first;
        }
        if (first == null) {
            return second;
        }
        Error fatal = fatalError(second);
        if (fatal != null && fatalError(first) == null) {
            keepAlso(fatal, first);
            keepAlso(fatal, second);
            return fatal;
        }
        keepAlso(first, second);
        return first;
    }

    /**
     * Chooses which of two fatal errors, from two things closed one after the other, the caller throws: the first,
     * which carries the other as a suppressed exception (see {@link #keepAlso}). The other was logged when it was
     * caught, as {@link Closing} logs every failure to close.
     *
     * @param first  The fatal error from what was closed first, or {@code null}
     * @param second The fatal error from what was closed after it, or {@code null}
     * @return {@code first} if there is one, else {@code second}, which may be {@code null}
     */
    static Error first(Error first, Error second) {
        if (first == null) {
            return second;
        }
        keepAlso(first, second);
        return first;
    }

    /**
     * What the steps that end a run threw, each kept where it was caught, which can't fail again, and combined last,
     * as {@link Failures#fatalFirst} combines two: the first fatal {@link Error} wins, or else the first failure,
     * carrying the rest. The run makes it before it holds anything, a copy of the rules or a value, so that ending a
     * run that has run out of memory allocates nothing until what the steps threw is combined (#855), and adds what
     * each step threw, or {@code null}, in order: {@link #add} is assignments and the instanceof checks of
     * {@link Causes#isFatal} only, so a step that failed never keeps the next from running, as combining what it
     * threw there could, when it runs out of stack or memory.
     * {@link #result} combines them once every step has run, and when combining fails, as it can when it allocates,
     * falls back on the first fatal error the JVM threw, kept by assignment as the steps were added, or else on what
     * combining threw, the rest being lost: that last resort allocates nothing and calls nothing.
     */
    static final class Teardown {
        // What each step threw, in order, null for one that threw nothing; made for as many steps as there are.
        private final Throwable[] thrown;
        private int steps;
        // The first fatal error the JVM threw while the run ended, kept with instanceof checks and assignments only,
        // for the last resort of result().
        private Throwable fatal;
        // Where combining can be made to fail.
        private final Faults.Step combining;

        /**
         * @param steps     How many steps end the run, each added once, with what it threw or {@code null}
         * @param combining The step a test makes fail where what they threw is combined
         */
        Teardown(int steps, Faults.Step combining) {
            this.thrown = new Throwable[steps];
            this.combining = combining;
        }

        /**
         * Adds what the next step threw, with nothing allocated and no call but {@link Causes#isFatal}.
         *
         * @param t What it threw, or {@code null} if it threw nothing
         */
        void add(Throwable t) {
            thrown[steps] = t;
            steps++;
            if (fatal == null && Causes.isFatal(t)) {
                fatal = t;
            }
        }

        /**
         * Combines what the steps threw, once every one has run.
         *
         * @return What the caller throws, or {@code null} if nothing is to be thrown: the first fatal {@link Error}
         *         a step threw, or else the first other throwable, either carrying the rest as suppressed exceptions
         *         (see {@link Failures#fatalFirst}); the fatal error itself when the first failure holds one, carrying
         *         it (see {@link Failures#fatalError}), as a run that fails with one throws it. If combining them
         *         fails, the first fatal error met is returned alone, or else what combining threw.
         */
        // Any Throwable: combining allocates, and the last resort must return whatever stopped it.
        Throwable result() {
            try {
                Faults.at(combining);
                Throwable ending = null;
                for (int i = 0; i < steps; i++) {
                    ending = fatalFirst(ending, thrown[i]);
                }
                // The fatal error itself, not a throwable that carries it.
                Error carried = fatalError(ending);
                if (carried != null) {
                    keepAlso(carried, ending);
                    return carried;
                }
                return ending;
            } catch (Throwable t) {
                // The last resort, which can't fail again: the fatal error the JVM threw, if any, and else what
                // combining threw, the rest being lost.
                return fatal != null ? fatal : t;
            }
        }
    }

    /**
     * Keeps a failure on the fatal {@link Error} the caller throws in its place, as a suppressed exception, so that
     * every fatal error the engine meets reaches the caller of {@code run()}, {@code load()}, {@code validate()} or
     * {@code close()}, not only the one it throws. Nothing is added when either is {@code null}, when they are the
     * same instance, as a language that throws one cached error from every {@code close()} makes them, or when one
     * already reaches the other through causes and suppressed exceptions (see {@link #reaches}): {@code winner} that
     * reaches {@code loser} carries it already, and {@code loser} that reaches {@code winner}, as a listener's error
     * that wraps the rule's does, or one kept on it before, as two cached errors met in turn in either order are,
     * would make a loop of causes and suppressed exceptions. It was logged when it was caught either way. A
     * {@code winner} built with suppression disabled, as the {@link OutOfMemoryError} the
     * JVM keeps ready for when it has no memory left is, can't carry it: that is logged at WARN.
     *
     * @param winner What the caller throws, or {@code null}
     * @param loser  What it's thrown in place of, or {@code null}
     */
    static void keepAlso(Throwable winner, Throwable loser) {
        if (winner == null || loser == null || reaches(loser, winner) || reaches(winner, loser)) {
            return;
        }
        winner.addSuppressed(loser);
        // A throwable built with suppression disabled ignores addSuppressed().
        if (!carries(winner, loser)) {
            logWarn(log, "The fatal error {} can't carry a suppressed exception, so it's rethrown without {}",
                    describeWithClass(winner), describeWithClass(loser));
        }
    }

    /**
     * Tells whether {@code target} is {@code from} itself, or can be reached from it through causes and suppressed
     * exceptions, those the engine added included, read as {@link Reach} reads them: one past the
     * {@value #MAX_EXCEPTIONS_READ} exceptions it reads isn't found.
     *
     * @param from   Where to start
     * @param target What to look for
     * @return {@code true} if {@code target} was found
     */
    private static boolean reaches(Throwable from, Throwable target) {
        return new Reach(true, SAME, target).from(from) != null;
    }

    /**
     * Tells whether {@code winner} carries {@code loser} among its own suppressed exceptions, as {@link #keepAlso}
     * keeps it there.
     *
     * @param winner What the caller throws
     * @param loser  What it may carry, or {@code null}
     * @return {@code true} if {@code loser} is one of {@code winner}'s direct suppressed exceptions
     */
    // The very same instance: an equal one is another failure.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    static boolean carries(Throwable winner, Throwable loser) {
        for (Throwable kept : winner.getSuppressed()) {
            if (kept == loser) {
                return true;
            }
        }
        return false;
    }

    /**
     * Chooses what the caller throws when closing what a failure left behind threw a fatal {@link Error}: a fatal
     * error beats any other failure, and of two fatal errors the first wins. So {@code closeFatal} replaces
     * {@code failure} only when {@code failure} holds no fatal error (see {@link #fatalError}), and then carries it as
     * a suppressed exception, or, if it can't carry one, {@code failure} is logged at WARN. A fatal error a language's
     * {@code close()} threw that loses was logged when it was caught; one from elsewhere is kept on what the caller
     * throws, so a caller that must have it logged as well logs it itself, as a failed {@code load()} does for one
     * retiring threw. The caller throws {@code failure}, which carries the one that loses as a suppressed exception
     * (see {@link #keepAlso}), when its own fatal error is in its cause chain; when that error is only suppressed on
     * it, the caller throws that error itself, carrying {@code closeFatal}, rather than an exception that carries two.
     * {@code closeFatal} is taken for the fatal error it is, as what closing returns is one, and never searched: the
     * caller has it in hand.
     *
     * <p>
     * A {@code failure} caused by an interrupt, or carrying one suppressed, sets the thread's interrupt status again
     * when it's replaced (see {@link #keepInterruptStatus}), because the caller that would have set it never sees it.
     * The fatal error that can't carry a suppressed exception is one built with suppression disabled, as the
     * {@link OutOfMemoryError} the JVM keeps ready for when it has no memory left is: it would lose {@code failure}, so
     * it's logged instead.
     * </p>
     *
     * @param failure    What was being thrown when the closing began
     * @param closeFatal The fatal error closing threw, or {@code null} if it threw none
     * @return {@code closeFatal}, with {@code failure} added to its suppressed exceptions, or logged if it can't carry
     *         one, if the caller throws it in place of {@code failure}; the fatal error suppressed on {@code failure},
     *         carrying {@code closeFatal}, if the caller throws that; otherwise {@code null}, and the caller throws
     *         {@code failure}, with {@code closeFatal}, if there is one, added to its suppressed exceptions
     */
    static Error closingInsteadOf(Throwable failure, Error closeFatal) {
        // Either the fatal error chosen from the failure or closeFatal itself, so what replaced returns is an Error.
        return closeFatal == null ? null : (Error) replaced(failure, closeFatal, closeFatal);
    }

    /**
     * Chooses what the caller throws when cleaning up after a failure, as retiring what a failed {@code load()} left
     * behind or ending a failed run does, threw anything: the fatal {@link Error} it holds, if it holds one (see
     * {@link #fatalError}), as {@link #closingInsteadOf} chooses for one closing threw, and else what it threw as it
     * is, as running out of stack is. A fatal error in {@code failure} comes first: when the error is in its cause
     * chain, the caller throws {@code failure}, carrying {@code later}'s error or {@code later}; only when it's
     * suppressed on it alone does the caller throw that error itself, carrying that, with {@code failure} logged at
     * WARN. When {@code failure} holds no fatal error, {@code later}'s error or {@code later} replaces it, and carries
     * it as a suppressed exception, as {@link #closingInsteadOf} keeps it on the error, or as {@link #keepAlso} keeps
     * it on anything else.
     *
     * @param failure What was being thrown when the cleaning up began
     * @param later   What cleaning up threw, or {@code null} if it threw nothing
     * @return The fatal error {@code later} holds, with {@code failure} added to its suppressed exceptions, or logged
     *         if it can't carry one, or else {@code later}, carrying {@code failure}, if the caller throws it in place
     *         of {@code failure}; the fatal error suppressed on {@code failure}, carrying {@code later}'s error or
     *         {@code later}, if the caller throws that; otherwise {@code null}, and the caller throws {@code failure},
     *         with {@code later}'s error or {@code later}, if there is one, added to its suppressed exceptions, or
     *         logged at WARN if it can't carry one (see {@link #keepAlso})
     */
    static Throwable insteadOf(Throwable failure, Throwable later) {
        // A fatal error itself, as ending a run returns one, is read with nothing allocated.
        return later == null ? null
                : replaced(failure, later, Causes.isFatal(later) ? (Error) later : fatalError(later));
    }

    /**
     * Chooses what the caller throws, as {@link #closingInsteadOf} and {@link #insteadOf} choose it.
     *
     * @param failure    What was being thrown when the cleaning up began
     * @param later      What cleaning up threw
     * @param laterFatal The fatal error it holds, or {@code null} if it holds none
     * @return {@code laterFatal}, {@code later}, the fatal error suppressed on {@code failure} alone, or {@code null}
     *         for {@code failure} itself, as those return it
     */
    private static Throwable replaced(Throwable failure, Throwable later, Error laterFatal) {
        Throwable other = laterFatal != null ? laterFatal : later;
        List<Throwable> chain = Causes.causeChain(failure);
        Error fatal = fatalErrorIn(chain);
        if (fatal != null) {
            return fatalOfFailure(failure, chain, fatal, other);
        }
        // Replaced, so the caller that would have set it again never sees it.
        keepInterruptStatus(failure);
        if (laterFatal == null) {
            keepAlso(later, failure);
            return later;
        }
        laterFatal.addSuppressed(failure);
        // A fatal error built with suppression disabled ignores addSuppressed().
        if (laterFatal.getSuppressed().length == 0) {
            log.warn("A failure was replaced by the fatal error {}, which can't carry it as a suppressed exception: {}",
                    describeWithClass(laterFatal), describeWithClass(failure));
        }
        return laterFatal;
    }

    /**
     * Keeps what failed after {@code failure} beside the fatal {@link Error} {@code failure} holds, which comes first.
     *
     * @param failure What was being thrown first
     * @param chain   Its cause chain
     * @param fatal   The fatal error it holds (see {@link #fatalErrorIn})
     * @param other   What failed after it
     * @return {@code null} when the error is in {@code failure}'s cause chain, and the caller throws {@code failure},
     *         carrying {@code other}; otherwise the error, carrying {@code other}, for the caller to throw
     */
    private static Error fatalOfFailure(Throwable failure, List<Throwable> chain, Error fatal, Throwable other) {
        if (fatalIn(chain) != null) {
            keepAlso(failure, other);
            return null;
        }
        // Only suppressed on the failure: that error is thrown, not an exception that carries two. The failure
        // reaches it, so it can't be kept on it, and is logged instead.
        keepInterruptStatus(failure);
        keepAlso(fatal, other);
        log.warn("A failure was replaced by the fatal error {} suppressed on it: {}", describeWithClass(fatal),
                describeWithClass(failure));
        return fatal;
    }

    /**
     * Returns the text of a nested failure for the note {@link NestedFailures#describe} adds after an exception's own
     * message: the text {@link #loggedText} returns, shortened to {@value Text#MAX_DESCRIPTION_LENGTH} characters as a
     * whole before it's escaped, as {@link Text#quoteAll} shortens a list, because the nested failure may itself hold
     * news, run after run. The engine wrote most of that text and escaped it already, a {@link ReportedFailure}'s
     * message included, so it's shortened as {@link Text#clip} shortens it, never inside an escape. Its text names the
     * root cause if it's hidden, so the note of the exception around it doesn't.
     *
     * @param logged The nested failure
     * @return Its text, shortened, then escaped
     */
    static String noteText(Throwable logged) {
        String message = messageOr(logged, logged.getClass().getName());
        if (logged instanceof ReportedFailure) {
            return Text.clip(message);
        }
        // Shortened before it's escaped, as a whole, and before an escape the message already holds, so a cut never
        // falls inside an escape.
        String note = rawCauseNote(Causes.causeChain(logged), shownOf(readableMessage(logged)));
        return Text.clip(Text.escape(message).contains(Text.escape(note)) ? message : message + note);
    }

    /**
     * Returns the text a nested run or load logged a failure with, as the run around it names it: the message of a
     * {@link ReportedFailure}, which the engine wrote, or else the exception described as
     * {@link NestedFailures#describe} describes one whose cause chain holds no nested failure, which escapes a message
     * a language wrote and shortens it as {@link Text#clip} shortens it, never inside an escape: the engine wrote most
     * such messages, such as a rule that fails to compile or a fact it rejects, and escaped the names and text in them
     * already. The note of a root cause it would otherwise hide is left out when the message already has it, as a
     * message the engine wrote from a language's exception does, so it isn't there twice, as
     * {@link #describeWithClass} leaves it out.
     *
     * @param logged The failure
     * @return Its text
     */
    static String loggedText(Throwable logged) {
        if (logged instanceof ReportedFailure) {
            return Causes.messageOf(logged);
        }
        String text = Text.clip(messageOr(logged, logged.getClass().getName()));
        String note = causeNote(Causes.causeChain(logged), shownOf(readableMessage(logged)));
        return text.contains(note) ? text : text + note;
    }

    /**
     * Names the root cause when a description would otherwise hide it: its class when it has no message, and its class
     * and message when an exception above it has none, unless the part of the first exception's text that the
     * description shows already has it, the class's name for a root cause with no message, as
     * {@code new RuntimeException(cause)}'s message has it, and the message otherwise: the first message for
     * {@link NestedFailures#describe}, its {@code toString()} for {@link #describeWithClass}. That part is the text's
     * first {@value Text#MAX_DESCRIPTION_LENGTH} characters as {@link Text#clip} keeps them (see {@link #shownOf}),
     * searched raw, without the note of what was left out: a root cause's message found only past the cut isn't shown,
     * and searching all of a long text took time that grew with the square of its length. A message that can't be read
     * (see {@link Causes#messageOf}) hides the root cause as a missing one does, and a root cause whose message can't
     * be read is named with the note that it's unavailable. Only a text that can be read can already show the root
     * cause's message: two notes that messages are unavailable read the same whatever the messages were.
     *
     * @param chain An exception and its causes
     * @param shown The part of the first exception's text the description shows, as {@link #shownOf} returns it, or
     *              {@code null} if it shows none that can be read: of {@link NestedFailures#describe}'s message, or of
     *              {@link #describeWithClass}'s {@code toString()}
     * @return {@code " (caused by ...)"}, or an empty string if nothing is hidden
     */
    static String causeNote(List<Throwable> chain, @Nullable String shown) {
        return Text.escape(rawCauseNote(chain, shown));
    }

    /**
     * Names the root cause as {@link #causeNote} does, before the note is escaped: the root cause's class shortened as
     * {@link Text#quote} shortens a name, and its message as {@link Text#shortenBeforeEscape} shortens one, never
     * inside an escape: the message of a root cause the engine built holds escapes already, and escaping the note
     * after, or shortening it again with the text it follows (see {@link #noteText}), can't mend one cut in half.
     *
     * @param chain An exception and its causes
     * @param shown As for {@link #causeNote}
     * @return {@code " (caused by ...)"}, unescaped, or an empty string if nothing is hidden
     */
    private static String rawCauseNote(List<Throwable> chain, @Nullable String shown) {
        if (chain.subList(1, chain.size()).isEmpty()) {
            return "";
        }
        Throwable root = chain.get(chain.size() - 1);
        String rootMessage = Causes.messageOf(root);
        if (rootMessage == null) {
            // A text that shows the class already, as new RuntimeException(cause)'s message does, needs no note.
            String rootClass = root.getClass().getName();
            return shown != null && shown.contains(rootClass) ? "" : " (caused by " + Text.shorten(rootClass) + ")";
        }
        boolean hidden = !allReadable(chain) && (shown == null || !shown.contains(rootMessage));
        return hidden
                ? " (caused by " + Text.shorten(root.getClass().getName()) + ": "
                        + Text.shortenBeforeEscape(rootMessage) + ")"
                : "";
    }

    /** Tells whether every exception in a cause chain has a message that can be read (see {@link #readableMessage}). */
    private static boolean allReadable(List<Throwable> chain) {
        for (Throwable t : chain) {
            if (readableMessage(t) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the part of a text that a description shows, for {@link #causeNote} to search: the characters it keeps
     * themselves, not the shortened text, whose note of what was left out could match. That's the whole text if it
     * fits, or else its first characters as {@link Text#clip} keeps them, which can be up to 5 fewer than
     * {@link Text#truncate} keeps.
     *
     * @param text The text, or {@code null} if there's none that can be read
     * @return The part shown, or {@code null} if {@code text} is
     */
    static @Nullable String shownOf(@Nullable String text) {
        if (text == null || text.length() <= Text.MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        return text.substring(0, Text.keptBeforeEscape(text, Text.MAX_DESCRIPTION_LENGTH));
    }

    /**
     * Describes an exception with its class, as {@link Throwable#toString()} does, for a message about code the engine
     * calls outside any rule, such as the output factory: shortened to {@value Text#MAX_DESCRIPTION_LENGTH} characters,
     * then escaped, as {@link Text#clip} does, like {@link NestedFailures#describe}, so a message the engine escaped is
     * never cut inside an escape, and naming a root cause it would otherwise hide as {@link NestedFailures#describe}
     * does. The root cause counts as shown only when the part of the {@code toString()} this shows, its first
     * {@value Text#MAX_DESCRIPTION_LENGTH} characters as {@link Text#clip} keeps them, has its message, so neither a
     * class name that pushes the message past the cut nor a {@code toString()} that leaves the message out hides it.
     * The note is left out when the text already has it, as the message of a {@code run()} started from that code
     * does unless it was shortened, so it isn't there twice. When {@code toString()} throws, its class name stands in
     * with a note that its message is unavailable (see {@link #textOf}), which shows no root cause.
     *
     * @param e The exception to describe
     * @return Its class name, its message if it has one, and a note of its root cause if the text hides it and doesn't
     *         already have that note
     */
    static String describeWithClass(Throwable e) {
        String readable = readableText(e);
        String text = Text.clip(readable != null ? readable : textOf(e));
        String note = causeNote(Causes.causeChain(e), shownOf(readable));
        return text.contains(note) ? text : text + note;
    }

    /**
     * Reads an exception's message as {@link Causes#messageOf} does, telling no message and one that can't be read
     * apart from one that says something.
     *
     * @param e The exception
     * @return Its message, or {@code null} if it has none or it can't be read
     */
    static String readableMessage(Throwable e) {
        try {
            return e.getMessage();
        } catch (Throwable thrown) {
            return null;
        }
    }

    /**
     * Reads an exception's message as {@link Causes#messageOf} does, with a stand-in for a message it doesn't have.
     *
     * @param e      The exception
     * @param ifNone What to return if it has no message
     * @return Its message, or {@code ifNone} if it has none, or {@code ifNone} followed by
     *         {@code (message unavailable: ...)} if the message can't be read
     */
    static String messageOr(Throwable e, String ifNone) {
        try {
            String message = e.getMessage();
            return message != null ? message : ifNone;
        } catch (Throwable thrown) {
            return Causes.unavailable(ifNone + " ", thrown);
        }
    }

    /**
     * Reads an exception's {@link Throwable#toString()} as {@link Causes#messageOf} reads its message. The default
     * {@code toString()} reads the message, so an exception whose {@code getMessage()} throws fails here too.
     *
     * @param e The exception
     * @return Its {@code toString()}, or its class name followed by {@code (message unavailable: ...)} if that throws
     */
    static String textOf(Throwable e) {
        try {
            return e.toString();
        } catch (Throwable thrown) {
            return Causes.unavailable(e.getClass().getName() + " ", thrown);
        }
    }

    /**
     * Reads an exception's {@link Throwable#toString()} as {@link #readableMessage} reads its message.
     *
     * @param e The exception
     * @return Its {@code toString()}, or {@code null} if that throws
     */
    static @Nullable String readableText(Throwable e) {
        try {
            return e.toString();
        } catch (Throwable thrown) {
            return null;
        }
    }

    /**
     * Reads the issues a language reported with an expression it rejected, as {@link Causes#messageOf} reads a message:
     * {@code issues()} isn't final either, and a language's own subclass may throw from it, or return {@code null} or
     * a list with a {@code null} in it, which the {@code RuleCompilationException} that reports them can't take.
     *
     * @param e The exception
     * @return A copy of its issues, or no issues if they can't be read
     */
    static List<InvalidExpressionException.Issue> issuesOf(InvalidExpressionException e) {
        try {
            return List.copyOf(e.issues());
        } catch (Throwable thrown) {
            return List.of();
        }
    }

    /**
     * Sets the current thread's interrupt status again when what was caught was caused by an interrupt, or carries
     * one as a suppressed exception. A rule, listener or language interrupted while it blocks throws an
     * {@link InterruptedException}, which clears the status, and the engine receives it wrapped by MVEL or the
     * language, or suppressed on the body's exception by a {@code try}-with-resources whose {@code close()} was
     * interrupted; without this, the caller of {@code run()} couldn't tell the thread had been interrupted. The
     * interrupt is found as {@link #hasInterrupt} finds it.
     *
     * @param thrown What was caught, or {@code null}
     */
    static void keepInterruptStatus(Throwable thrown) {
        if (hasInterrupt(thrown)) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tells whether what was caught was caused by an interrupt, or carries one as a suppressed exception, as
     * {@link #keepInterruptStatus} asks before it sets the thread's interrupt status again. The cause chain is read
     * first, then the suppressed exceptions as {@link #fatalError} reads them: one the engine added with
     * {@link ReportedFailure#addSuppressedByEngine} isn't read, and one past the {@value #MAX_EXCEPTIONS_READ}
     * exceptions read is missed. A {@link java.io.InterruptedIOException} doesn't count: a
     * {@link java.net.SocketTimeoutException} is one.
     *
     * @param thrown What was caught, or {@code null}
     * @return {@code true} if an {@link InterruptedException} is among the exceptions read
     */
    static boolean hasInterrupt(Throwable thrown) {
        List<Throwable> chain = Causes.causeChain(thrown);
        for (Throwable t : chain) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return suppressedMatching(chain, INTERRUPT, null) != null;
    }

    /**
     * Logs a failure the engine reports or rethrows at ERROR. The first message logged in a JVM loads and initializes
     * classes of the logging provider's, such as SLF4J's {@code Level} and {@code FormattingTuple} with slf4j-simple,
     * which no engine's build does, and that can take more stack than the room the engine checks for before a load or
     * a run, so deep in a stack the log call itself can overflow. The line is then lost, and the caller still reports
     * or rethrows the failure, rather than a {@link StackOverflowError} in its place (#1208). Whatever records the
     * failure as logged has recorded it already, or still does, so a load or a run around this one doesn't log it
     * again. Any other failure of the log call is thrown, as before, and so is an {@link InternalError} that doesn't
     * wrap an overflow (see {@link Causes#isOverflow}).
     *
     * @param logger  The engine's logger
     * @param message What failed
     */
    static void logError(Logger logger, String message) {
        try {
            Faults.at(Faults.Step.FAILURE_LOGGED);
            logger.error(message);
        } catch (StackOverflowError ignored) {
            // Only the line is lost; the caller still has the failure.
        } catch (InternalError internal) {
            // As for a StackOverflowError when it wraps one, as the JDK does, and else as for any other
            // VirtualMachineError.
            if (!Causes.isOverflow(internal)) {
                throw internal;
            }
        }
    }

    /**
     * Logs a warning at WARN, as {@link #logError} logs a failure at ERROR: an overflow inside the log call, as the
     * first message logged in a JVM can overflow deep in a stack, loses the line, and the caller goes on as it does
     * once the line is logged, rather than throwing a {@link StackOverflowError} (#1208). Any other failure of the log
     * call is thrown, as before, and so is an {@link InternalError} that doesn't wrap an overflow (see
     * {@link Causes#isOverflow}).
     *
     * @param logger    The engine's logger
     * @param format    The message, with a {@code {}} for each argument, as SLF4J formats it
     * @param arguments The arguments
     */
    static void logWarn(Logger logger, String format, Object... arguments) {
        try {
            Faults.at(Faults.Step.FAILURE_LOGGED);
            logger.warn(format, arguments);
        } catch (StackOverflowError ignored) {
            // Only the line is lost; the caller goes on.
        } catch (InternalError internal) {
            // As in logError.
            if (!Causes.isOverflow(internal)) {
                throw internal;
            }
        }
    }

    /**
     * Logs the failure of code the engine calls but doesn't own, such as a language's session or the output factory,
     * unless a nested run or load already logged it (see {@link LoggedFailures}), and rethrows a fatal {@link Error}
     * found in its cause chain, or suppressed on it, unchanged (see {@link Failures#fatalError}). The caller keeps the
     * interrupt status first (see {@link Failures#keepInterruptStatus}), then builds the message, as describing the
     * failure reads what was logged before this records it, and throws a {@link ReportedFailure} with that message
     * itself once this returns.
     *
     * @param msg What failed
     * @param e   What the called code threw
     */
    static void reportCalledCodeFailure(String msg, Throwable e) {
        if (NestedFailures.unlogged(e)) {
            Failures.logError(log, msg);
        }
        Failures.throwIfPresent(Failures.fatalError(e));
    }

    /**
     * Names a rule's condition or action for a message, such as {@code Condition for rule 'prime-rate'}.
     *
     * @param kind     Whether it's the rule's condition or its action
     * @param ruleName The rule's name
     * @return The name
     */
    static String expression(ExpressionKind kind, String ruleName) {
        return (kind == ExpressionKind.CONDITION ? "Condition" : "Action") + " for rule '" + Text.quote(ruleName) + "'";
    }

    /**
     * Describes where an issue is for a message, such as {@code  at line 2, column 5}.
     *
     * @param issue The issue
     * @return The position with a leading space, or an empty string if the issue's line isn't known
     */
    static String position(InvalidExpressionException.Issue issue) {
        if (issue.line() == 0) {
            return "";
        }
        return " at line " + issue.line() + (issue.column() == 0 ? "" : ", column " + issue.column());
    }
}
