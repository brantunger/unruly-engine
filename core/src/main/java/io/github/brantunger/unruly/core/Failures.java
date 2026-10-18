package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.MessageText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * How the engine treats what rules, listeners and expression languages throw: which errors must reach the caller
 * unchanged, and how an exception is described in an error message. Only {@link #closingInsteadOf},
 * {@link #insteadOf} and {@link #keepAlso} log, and under the engine's logger name,
 * {@code io.github.brantunger.unruly.engine}, as every failure is logged.
 * <b>Internal:</b> this class may change in any release, and isn't part of the API. It's public only so that the
 * {@code api} package ({@code Names}, {@code LoggingRuleListener} and {@code language.MessageText}) and the benchmarks
 * can use it: they shorten and escape text the way the engine does, rather than keeping a copy that could drift.
 */
public final class Failures {

    /**
     * How much of an exception's message an error message includes; see {@link NestedFailures#describe}.
     * {@code MessageText} publishes it.
     */
    static final int MAX_DESCRIPTION_LENGTH = MessageText.MAX_TEXT_LENGTH;

    /**
     * How much of a fact, rule or language name a message includes; see {@link #quote}. {@code MessageText} publishes
     * it.
     */
    static final int MAX_NAME_LENGTH = MessageText.MAX_NAME_LENGTH;

    /** How many links of an exception's cause chain the engine reads; see {@link #causeChain}. */
    static final int MAX_CAUSE_CHAIN_LENGTH = 100;

    /**
     * How many different exceptions, through causes and suppressed exceptions, the engine reads to learn whether one
     * already reaches another (see {@link #reaches}), or to find a fatal {@link Error} or any {@link Error} suppressed
     * in what was caught (see {@link #fatalError} and {@link NestedFailures#errorInChain}); one past that many isn't
     * found.
     */
    static final int MAX_EXCEPTIONS_READ = 10_000;

    /**
     * The {@code Default_Ignorable_Code_Point} characters of Unicode's {@code DerivedCoreProperties.txt} (the same in
     * Unicode 15.0 to 17.0) that aren't format characters, as the first and last code point of each range, in order:
     * fillers, marks such as the variation selectors, and the unassigned code points kept for more of them. U+180E and
     * the tag characters in U+E0000..U+E0FFF are format characters, which are escaped anyway; see {@link #escape}.
     */
    private static final int[] OTHER_DEFAULT_IGNORABLE = {
        0x034F, 0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F, 0x2065, 0x2065, 0x3164, 0x3164,
        0xFE00, 0xFE0F, 0xFFA0, 0xFFA0, 0xFFF0, 0xFFF8, 0xE0000, 0xE0FFF
    };

    // What a Reach looks for (see Reach#matches): a fatal error, any error, an interrupt, the stop of a run past a
    // deadline or interrupted, or the very instance given. Written as numbers that pick plain code rather than as
    // lambdas, so the first failure in the JVM links no call site, which could overflow deep in a stack (#1093).
    private static final int FATAL = 0;
    static final int ANY_ERROR = 1;
    private static final int INTERRUPT = 2;
    static final int STOP = 3;
    static final int SAME = 4;

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    private Failures() {
    }

    /**
     * Tells whether {@code thrown} is an {@link Error} the engine must not absorb, because the JVM itself is failing
     * and no message about a rule would help: a {@link VirtualMachineError} other than {@link StackOverflowError},
     * such as {@link OutOfMemoryError}, {@link InternalError} or {@link UnknownError}, but not an
     * {@link InternalError} that is an overflow (see {@link #isOverflow}).
     *
     * <p>
     * Every other error comes from the code being run, so it's handled like an exception and reported with the name of
     * the rule it came from:
     * </p>
     * <ul>
     *     <li>{@link StackOverflowError} (runaway recursion), which is itself a {@code VirtualMachineError} and so is
     *     excluded here on purpose;</li>
     *     <li>an {@link InternalError} with a {@link StackOverflowError} in its cause chain and no
     *     {@link LinkageError} before it, as the JDK wraps an overflow it hits defining a hidden class deep in a stack
     *     (#1141): the code being run ran out of stack, which more room fixes;</li>
     *     <li>{@link AssertionError} ({@code assert} in a rule, a listener or a language);</li>
     *     <li>every {@link LinkageError} — {@link NoClassDefFoundError}, {@link IllegalAccessError},
     *     {@link IncompatibleClassChangeError}, {@link ExceptionInInitializerError}, {@link VerifyError} and the rest —
     *     which means a class a rule uses is missing or can't be read: a configuration problem in one rule, not a
     *     failing JVM;</li>
     *     <li>any other {@link Error}, such as {@link java.io.IOError} or
     *     {@link java.util.ServiceConfigurationError}.</li>
     * </ul>
     *
     * <p>
     * It allocates nothing, and is the one check the engine's last-resort code makes, where handling a failure has
     * itself failed, as it can deep in a stack or with no memory left: ending a run (see {@link Teardown}), closing
     * a run's values and refusing one whose init ended the run ask it for what they caught, with instanceof checks and
     * assignments around it. Only an {@link InternalError} has its chain read, as {@link #isOverflow} reads it, so the
     * call takes one frame more than the checks themselves would (#1164).
     * </p>
     *
     * @param thrown One throwable from a cause chain, or {@code null}, which isn't fatal
     * @return {@code true} if {@code thrown} must be rethrown unchanged
     */
    static boolean isFatal(Throwable thrown) {
        // StackOverflowError is a VirtualMachineError, so excluding it needs its own check. Only an InternalError has
        // its chain read, with nothing allocated for it.
        return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError)
                && !(thrown instanceof InternalError && isOverflow(thrown));
    }

    /**
     * Finds the fatal {@link Error} (see {@link #isFatal}) in what was caught: the throwable itself, or one of its
     * causes, or failing that one suppressed on any of them, or among the causes and suppressed exceptions of those. An
     * error from Java code a rule calls, such as a method, a getter or a lambda held in a fact, reaches the engine
     * inside MVEL's own exception, so checking only the outer exception let an {@link OutOfMemoryError} be absorbed
     * into a {@link RuleExecutionException}; and a {@code try}-with-resources whose {@code close()} hits one while its
     * body throws leaves it suppressed on the body's exception, so checking only the cause chain let it be absorbed
     * too.
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
        return fatalErrorIn(causeChain(thrown));
    }

    /**
     * Finds the fatal {@link Error} in a cause chain read once, as {@link #fatalError} finds it in what was caught,
     * for a caller that goes on to ask where in that same chain it is.
     *
     * @param chain An exception and its causes (see {@link #causeChain})
     * @return The fatal error {@link #fatalError} returns for it, or {@code null}
     */
    static Error fatalErrorIn(List<Throwable> chain) {
        Error fatal = fatalIn(chain);
        return fatal != null ? fatal : (Error) suppressedMatching(chain, FATAL, null);
    }

    /** Finds the first fatal {@link Error} in a cause chain itself, or {@code null} if there is none. */
    static Error fatalIn(List<Throwable> chain) {
        for (Throwable t : chain) {
            if (isFatal(t)) {
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
     * {@link #causeOf} reads it, so a {@code getCause()} that throws ends that chain; so does a link read already.
     * {@code getSuppressed()} is final, and read once for each exception. Each exception is read once, and at most
     * {@value #MAX_EXCEPTIONS_READ} different ones are, the links a walk starts beside included, so a graph with loops
     * of its own, a {@code getCause()} that returns a new exception every time, or a great many suppressed exceptions
     * end it too; one past that many is never found. It ends as soon as an exception matches or that many are read.
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
            Throwable cause = causeOf(t);
            if (cause == null) {
                return readSuppressed(t, t.getSuppressed(), List.of());
            }
            List<Throwable> chain = new ArrayList<>();
            chain.add(t);
            for (Throwable link = cause; link != null && read(link); link = causeOf(link)) {
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
         * {@link #isFatal}); with {@link #ANY_ERROR}, any error; with {@link #INTERRUPT}, an
         * {@link InterruptedException}; with {@link #STOP}, the stop of a run past the deadline the walk was given,
         * or interrupted if it was given none (see {@link ReportedFailure#isStopFor}); and with {@link #SAME}, the
         * very instance it was given.
         */
        // The very same instance: an equal one is another failure.
        @SuppressWarnings("PMD.CompareObjectsWithEquals")
        private boolean matches(Throwable t) {
            return switch (match) {
                case FATAL -> isFatal(t);
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
     * {@link Failures#isFatal} only, so a step that failed never keeps the next from running, as combining what it
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
         * Adds what the next step threw, with nothing allocated and no call but {@link Failures#isFatal}.
         *
         * @param t What it threw, or {@code null} if it threw nothing
         */
        void add(Throwable t) {
            thrown[steps] = t;
            steps++;
            if (fatal == null && isFatal(t)) {
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
        return later == null ? null : replaced(failure, later, isFatal(later) ? (Error) later : fatalError(later));
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
        List<Throwable> chain = causeChain(failure);
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
     * message: the text {@link #loggedText} returns, shortened to {@value #MAX_DESCRIPTION_LENGTH} characters as a
     * whole before it's escaped, as {@link #quoteAll} shortens a list, because the nested failure may itself hold
     * news, run after run. The engine wrote most of that text and escaped it already, a {@link ReportedFailure}'s
     * message included, so it's shortened as {@link #clip} shortens it, never inside an escape. Its text names the
     * root cause if it's hidden, so the note of the exception around it doesn't.
     *
     * @param logged The nested failure
     * @return Its text, shortened, then escaped
     */
    static String noteText(Throwable logged) {
        String message = messageOr(logged, logged.getClass().getName());
        if (logged instanceof ReportedFailure) {
            return clip(message);
        }
        // Shortened before it's escaped, as a whole, and before an escape the message already holds, so a cut never
        // falls inside an escape.
        String note = rawCauseNote(causeChain(logged), shownOf(readableMessage(logged)));
        return clip(escape(message).contains(escape(note)) ? message : message + note);
    }

    /**
     * Returns the text a nested run or load logged a failure with, as the run around it names it: the message of a
     * {@link ReportedFailure}, which the engine wrote, or else the exception described as
     * {@link NestedFailures#describe} describes one whose cause chain holds no nested failure, which escapes a message
     * a language wrote and shortens it as {@link #clip} shortens it, never inside an escape: the engine wrote most
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
            return messageOf(logged);
        }
        String text = clip(messageOr(logged, logged.getClass().getName()));
        String note = causeNote(causeChain(logged), shownOf(readableMessage(logged)));
        return text.contains(note) ? text : text + note;
    }

    /**
     * Names the root cause when a description would otherwise hide it: its class when it has no message, and its class
     * and message when an exception above it has none, unless the part of the first exception's text that the
     * description shows already has it, the class's name for a root cause with no message, as
     * {@code new RuntimeException(cause)}'s message has it, and the message otherwise: the first message for
     * {@link NestedFailures#describe}, its {@code toString()} for {@link #describeWithClass}. That part is the text's
     * first {@value #MAX_DESCRIPTION_LENGTH} characters as {@link #clip} keeps them (see {@link #shownOf}), searched
     * raw, without the note of what was left out: a root cause's message found only past the cut isn't shown, and
     * searching all of a long text took time that grew with the square of its length. A message that can't be read
     * (see {@link #messageOf}) hides the root cause as a missing one does, and a root cause whose message can't be
     * read is named with the note that it's unavailable. Only a text that can be read can already show the root cause's
     * message: two notes that messages are unavailable read the same whatever the messages were.
     *
     * @param chain An exception and its causes
     * @param shown The part of the first exception's text the description shows, as {@link #shownOf} returns it, or
     *              {@code null} if it shows none that can be read: of {@link NestedFailures#describe}'s message, or of
     *              {@link #describeWithClass}'s {@code toString()}
     * @return {@code " (caused by ...)"}, or an empty string if nothing is hidden
     */
    static String causeNote(List<Throwable> chain, @Nullable String shown) {
        return escape(rawCauseNote(chain, shown));
    }

    /**
     * Names the root cause as {@link #causeNote} does, before the note is escaped: the root cause's class shortened as
     * {@link #quote} shortens a name, and its message as {@link #shortenBeforeEscape} shortens one, never inside an
     * escape: the message of a root cause the engine built holds escapes already, and escaping the note after, or
     * shortening it again with the text it follows (see {@link #noteText}), can't mend one cut in half.
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
        String rootMessage = messageOf(root);
        if (rootMessage == null) {
            // A text that shows the class already, as new RuntimeException(cause)'s message does, needs no note.
            String rootClass = root.getClass().getName();
            return shown != null && shown.contains(rootClass) ? "" : " (caused by " + shorten(rootClass) + ")";
        }
        boolean hidden = !allReadable(chain) && (shown == null || !shown.contains(rootMessage));
        return hidden
                ? " (caused by " + shorten(root.getClass().getName()) + ": " + shortenBeforeEscape(rootMessage) + ")"
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
     * fits, or else its first characters as {@link #clip} keeps them, which can be up to 5 fewer than
     * {@link #truncate} keeps.
     *
     * @param text The text, or {@code null} if there's none that can be read
     * @return The part shown, or {@code null} if {@code text} is
     */
    static @Nullable String shownOf(@Nullable String text) {
        if (text == null || text.length() <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        return text.substring(0, keptBeforeEscape(text, MAX_DESCRIPTION_LENGTH));
    }

    /**
     * Describes an exception with its class, as {@link Throwable#toString()} does, for a message about code the engine
     * calls outside any rule, such as the output factory: shortened to {@value #MAX_DESCRIPTION_LENGTH} characters,
     * then escaped, as {@link #clip} does, like {@link NestedFailures#describe}, so a message the engine escaped is
     * never cut inside an escape, and naming a root cause it would otherwise hide as {@link NestedFailures#describe}
     * does. The root cause counts as shown only when the part of the {@code toString()} this shows, its first
     * {@value #MAX_DESCRIPTION_LENGTH} characters as {@link #clip} keeps them, has its message, so neither a class name
     * that pushes the message past the cut nor a {@code toString()} that leaves the message out hides it. The
     * note is left out when the text already has it, as the message of a {@code run()} started from that code does
     * unless it was shortened, so it isn't there twice. When {@code toString()} throws, its class name stands in with a
     * note that its message is unavailable (see {@link #textOf}), which shows no root cause.
     *
     * @param e The exception to describe
     * @return Its class name, its message if it has one, and a note of its root cause if the text hides it and doesn't
     *         already have that note
     */
    static String describeWithClass(Throwable e) {
        String readable = readableText(e);
        String text = clip(readable != null ? readable : textOf(e));
        String note = causeNote(causeChain(e), shownOf(readable));
        return text.contains(note) ? text : text + note;
    }

    /**
     * Reads an exception's message without letting a failure to read it escape. The engine reads the message of what
     * rules, listeners and languages throw to describe it, and a {@code getMessage()} of their own can throw, such as
     * one built from a field that is {@code null}; a failure the engine was handling would then end as that one.
     * Whatever {@code getMessage()} throws, a fatal {@link Error} too, only makes the message unavailable: what an
     * accessor of an exception the engine didn't create throws says nothing about the failure the engine is handling,
     * which is still handled as it would be, its own fatal errors included. The accessors {@link #readableMessage},
     * {@link #messageOr}, {@link #textOf}, {@link #readableText}, {@link #causeOf} and {@link #issuesOf} read the same
     * way, each in a {@code try} of its own rather than through a lambda or method reference, so the JVM's first
     * failure links no call site, which could overflow deep in a stack (#1093). {@code mvel.ExceptionReads} keeps a
     * copy for the {@code mvel} package, which may not use this class, and mvel's {@code ExceptionReadsCopiesTest}
     * checks it against this one.
     *
     * @param e The exception
     * @return Its message, or {@code null} if it has none, or {@code (message unavailable: ...)}, naming the class of
     *         what {@code getMessage()} threw, if it can't be read
     */
    static String messageOf(Throwable e) {
        try {
            return e.getMessage();
        } catch (Throwable thrown) {
            return unavailable("", thrown);
        }
    }

    /**
     * Reads an exception's message as {@link #messageOf} does, telling no message and one that can't be read apart
     * from one that says something.
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
     * Reads an exception's message as {@link #messageOf} does, with a stand-in for a message it doesn't have.
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
            return unavailable(ifNone + " ", thrown);
        }
    }

    /**
     * Reads an exception's {@link Throwable#toString()} as {@link #messageOf} reads its message. The default
     * {@code toString()} reads the message, so an exception whose {@code getMessage()} throws fails here too.
     *
     * @param e The exception
     * @return Its {@code toString()}, or its class name followed by {@code (message unavailable: ...)} if that throws
     */
    static String textOf(Throwable e) {
        try {
            return e.toString();
        } catch (Throwable thrown) {
            return unavailable(e.getClass().getName() + " ", thrown);
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
     * Makes the note that an exception's text can't be read, naming only the class of what reading it threw: that
     * one's own message could be what throws.
     *
     * @param prefix What goes before the note
     * @param thrown What reading the text threw
     * @return {@code prefix} and the note
     */
    private static String unavailable(String prefix, Throwable thrown) {
        return prefix + "(message unavailable: " + thrown.getClass().getName() + ")";
    }

    /**
     * Reads an exception's cause as {@link #messageOf} reads its message: {@code getCause()} isn't final either.
     * {@code mvel.ExceptionReads} keeps a copy, which mvel's {@code ExceptionReadsCopiesTest} checks against this one
     * through {@link #causeChain}.
     *
     * @param e The exception
     * @return Its cause, or {@code null} if it has none or {@code getCause()} throws
     */
    static Throwable causeOf(Throwable e) {
        try {
            return e.getCause();
        } catch (Throwable thrown) {
            return null;
        }
    }

    /**
     * Reads the issues a language reported with an expression it rejected, as {@link #messageOf} reads a message:
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
     * Shortens a message to at most {@value #MAX_DESCRIPTION_LENGTH} characters, as {@link #shorten(String, int)}
     * does. {@code api.language.MessageText} publishes this.
     *
     * @param text The message
     * @return The message, shortened if it was longer
     */
    public static String truncate(String text) {
        return shorten(text, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * Makes text safe to put in a message: shortened as {@link #shortenBeforeEscape} shortens it, then
     * {@link #escape escaped}. Raw text, such as a name or a language's message, is never cut inside an escape, as it's
     * escaped after it's cut. Text can hold escapes already, though: the message of an exception the engine built,
     * such as a rule that fails to compile or a fact it rejects, with a language's message or a name in it, a nested
     * failure's message, and a message of a rule's own that copies one. Those are cut before an escape, never inside
     * one, so up to 5 fewer characters show; where raw text only reads as an escape, those characters are lost.
     *
     * @param text The text
     * @return The text, shortened if it was longer, then escaped
     */
    static String clip(String text) {
        return escape(shortenBeforeEscape(text));
    }

    /**
     * Shortens text to at most {@value #MAX_DESCRIPTION_LENGTH} characters as {@link #truncate} does, except where the
     * limit falls inside an escape {@link #escape} writes ({@code \n}, {@code \r}, {@code \t}, or a backslash,
     * {@code u} and four lowercase hex digits): the escape is left out whole, and the text keeps at most 5 characters
     * fewer. {@link #escape} doesn't escape a backslash, so text that only reads as an escape is left out the same way.
     * The count of what was left out counts the characters of the text as given: escaped where it was escaped already,
     * such as a nested failure's message as it's held, and raw otherwise. So it matches the count in the line a nested
     * failure was logged with only when that line shows the same text.
     *
     * @param text The text, escaped or not
     * @return The text, shortened if it was longer, not escaped
     */
    private static String shortenBeforeEscape(String text) {
        if (text.length() <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        return cut(text, keptBeforeEscape(text, MAX_DESCRIPTION_LENGTH));
    }

    /**
     * How many characters of text longer than {@code limit} to keep, as {@link #shortenBeforeEscape} keeps
     * {@value #MAX_DESCRIPTION_LENGTH} and {@link #shorten(String)} keeps {@value #MAX_NAME_LENGTH}: as many as
     * {@link #shorten(String, int)} keeps, or fewer, up to the backslash of an escape they would end inside, or of text
     * that reads as one.
     *
     * @param text  The text, longer than {@code limit}
     * @param limit The most characters to keep, at least 1
     * @return How many of its first characters to keep
     */
    private static int keptBeforeEscape(String text, int limit) {
        int kept = keptLength(text, limit);
        int backslash = text.lastIndexOf('\\', kept - 1);
        return backslash >= 0 && backslash + escapeLength(text, backslash) > kept ? backslash : kept;
    }

    /**
     * Tells whether a cut at {@code at} falls inside an escape {@link #escape} writes, or inside text that reads as
     * one. Only the last backslash before the cut can start one, as no escape holds a backslash after its first
     * character, and only one at most 5 characters before it, as no escape is longer than 6.
     *
     * @param text The text
     * @param at   Where the text would be cut
     * @return {@code true} if an escape starts before the cut and ends after it
     */
    private static boolean insideEscape(String text, int at) {
        int from = Math.max(0, at - 5);
        int backslash = from + text.substring(from, at).lastIndexOf('\\');
        return backslash >= from && backslash + escapeLength(text, backslash) > at;
    }

    /**
     * How long the escape {@link #escape} writes that starts at a backslash is: 2 for {@code \n}, {@code \r} and
     * {@code \t}, 6 for a backslash, {@code u} and four lowercase hex digits, or 0 if what follows the backslash is
     * none of them.
     *
     * @param text The text
     * @param at   Where the backslash is
     * @return The escape's length, or 0
     */
    private static int escapeLength(String text, int at) {
        // Only escapeWithin's cut can fall after a backslash that ends the text; keptBeforeEscape's leaves some out.
        if (at + 1 == text.length()) {
            return 0;
        }
        char kind = text.charAt(at + 1);
        if ("nrt".indexOf(kind) >= 0) {
            return 2;
        }
        if (kind != 'u' || at + 6 > text.length()) {
            return 0;
        }
        for (int i = at + 2; i < at + 6; i++) {
            char digit = text.charAt(i);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) {
                return 0;
            }
        }
        return 6;
    }

    /**
     * Shortens text to at most {@code limit} characters, saying how many were left out: the plain cut of
     * {@link #truncate}, which {@link #keptBeforeEscape} backs off from before an escape. A surrogate pair the limit
     * falls inside is left out whole, so the text never ends in half a character.
     *
     * @param text  The text
     * @param limit How many characters to keep
     * @return The text, shortened if it was longer
     */
    static String shorten(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return cut(text, keptLength(text, limit));
    }

    /**
     * Keeps the first characters of a text, saying how many were left out, as {@link #shorten(String, int)} writes it.
     *
     * @param text The text
     * @param kept How many characters to keep
     * @return The kept characters, then {@code ... (N more characters)}
     */
    private static String cut(String text, int kept) {
        return text.substring(0, kept) + leftOut(text.length() - kept);
    }

    /**
     * Says how many characters were left out of a text, after the part of it a message shows.
     *
     * @param count How many characters were left out
     * @return {@code ... (N more characters)}
     */
    private static String leftOut(int count) {
        return "... (" + count + " more characters)";
    }

    /**
     * Escapes text as {@link #escape} does, shortened first, if it is longer than {@code most} characters or its
     * escaped form is longer than {@code room} characters, to as many of its first characters as fit in {@code room}
     * with {@code ... (N more characters)} after them, as {@link #cut} writes it. N counts the text's own characters
     * left out, as given, not escaped ones. The text is cut between code points, and never inside an escape
     * {@link #escape} writes or inside text that reads as one ({@code \n}, {@code \r}, {@code \t}, or a backslash,
     * {@code u} and four lowercase hex digits), which is left out whole: text can hold escapes already, such as a
     * message the engine built, and escaping it doesn't mend one cut in half. It shows at most the first {@code most}
     * characters, as {@link #keptBeforeEscape} keeps them, and none for a {@code most} of 0 or less. A room too small
     * for the count alone, a negative one included, gets the count alone; empty text is escaped as empty.
     * {@code api.language.MessageText} publishes this.
     *
     * @param text The text, raw, or with escapes the engine already wrote
     * @param most The most of its characters to show
     * @param room The most characters the escaped text may take, the count included
     * @return The text, shortened if it didn't fit or is longer than {@code most}, and escaped
     */
    public static String escapeWithin(String text, int most, int room) {
        int last = most >= text.length() ? text.length() : most <= 0 ? 0 : keptBeforeEscape(text, most);
        // Each code point escapes to at least its own characters, and each one kept can shorten the count by at most
        // one digit, so the two together only grow as more is kept, and once they don't fit, nothing more will.
        int kept = 0;
        long shown = 0;
        int end = 0;
        while (end < last && shown <= room) {
            int next = end + Character.charCount(text.codePointAt(end));
            shown += escape(text.substring(end, next)).length();
            if (shown + leftOut(text.length() - next).length() <= room && !insideEscape(text, next)) {
                kept = next;
            }
            end = next;
        }
        return end == text.length() && shown <= Math.max(room, 0) ? escape(text)
                : escape(text.substring(0, kept)) + leftOut(text.length() - kept);
    }

    /**
     * Quotes a name as {@link #quote} does, or, if that is longer than {@code room} characters, escapes it within the
     * room as {@link #escapeWithin} does, showing no more of it than {@link #quote} does. An empty name is quoted as
     * empty, whatever the room. {@code api.language.MessageText} publishes this.
     *
     * @param name The name
     * @param room The most characters the quoted name may take, the count of what was left out included
     * @return The name, quoted
     */
    public static String quoteWithin(String name, int room) {
        String quoted = quote(name);
        return quoted.length() <= room ? quoted : escapeWithin(name, MAX_NAME_LENGTH, room);
    }

    /**
     * How many characters of text longer than {@code limit} to keep: {@code limit}, or one fewer when the last of them
     * is a high surrogate.
     */
    private static int keptLength(String text, int limit) {
        return Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
    }

    /**
     * Makes a fact, rule or language name safe to put in a message the engine logs: shortened to
     * {@value #MAX_NAME_LENGTH} characters (UTF-16 units), then {@link #escape escaped}; each escaped unit shows as 2
     * or 6 characters. The name keeps one fewer where the limit falls inside a surrogate pair, which is left out whole,
     * and up to 5 fewer where it falls inside raw text that reads as an escape, which is left out whole too, as
     * {@link #keptBeforeEscape} keeps them, so it never shows as an escape cut in half.
     * {@code api.language.MessageText} publishes this.
     *
     * @param name The name
     * @return The name, shortened if it was longer, then escaped
     */
    public static String quote(String name) {
        return escape(shorten(name));
    }

    /**
     * Shortens a name as {@link #quote} does, without escaping it, for a list of names that is escaped as a whole.
     *
     * @param name The name
     * @return The name, shortened if it was longer
     */
    static String shorten(String name) {
        if (name.length() <= MAX_NAME_LENGTH) {
            return name;
        }
        return cut(name, keptBeforeEscape(name, MAX_NAME_LENGTH));
    }

    /**
     * Makes a list of names safe to put in a message, shown as a {@link java.util.List}'s {@code toString()} shows it,
     * such as {@code [eu, retail]}: each name shortened as {@link #quote} does, the list shortened and the whole
     * escaped last, as {@link #clip} does, so a cut never falls inside an escape. The count of what was
     * left out of the list counts the characters of the list as it was before it was escaped: the shortened names, the
     * notes of what was left out of each, and the separators.
     *
     * @param names The names; a {@code null} one is shown as {@code null}
     * @return The list, its names shortened to {@value #MAX_NAME_LENGTH} characters and it to
     *         {@value #MAX_DESCRIPTION_LENGTH}, then escaped
     */
    public static String quoteAll(Collection<? extends @Nullable String> names) {
        StringBuilder list = new StringBuilder("[");
        String separator = "";
        for (String name : names) {
            list.append(separator).append(name == null ? "null" : shorten(name));
            separator = ", ";
        }
        return clip(list.append(']').toString());
    }

    /**
     * Makes a list of names safe to put in a message, each name shortened as {@link #quote} does and put between
     * single quotes, the names separated by {@code ", "}, such as {@code 'eu', 'retail'}; the list is then shortened
     * and escaped as {@link #quoteAll} shortens and escapes it.
     *
     * @param names The names
     * @return The list, its names shortened to {@value #MAX_NAME_LENGTH} characters and it to
     *         {@value #MAX_DESCRIPTION_LENGTH}, then escaped
     */
    static String quoteJoined(Collection<String> names) {
        StringBuilder list = new StringBuilder();
        String separator = "";
        for (String name : names) {
            list.append(separator).append('\'').append(shorten(name)).append('\'');
            separator = ", ";
        }
        return clip(list.toString());
    }

    /**
     * Makes text the engine didn't write safe to put in a message it logs, without shortening it. Line breaks, tabs
     * and other control characters, and the Unicode line and paragraph separators, are escaped ({@code \n},
     * {@code \r}, {@code \t}, or a backslash, {@code u} and four hex digits), so neither a name nor a fact value that
     * reached the message from request data can start a log line of its own. So are the Unicode format characters
     * (category Cf), such as bidi controls and zero-width and tag characters, which could reorder the rest of a line in
     * a viewer or make two different names look the same, and the other characters Unicode marks
     * {@code Default_Ignorable_Code_Point}, which a viewer shows as nothing, such as the Hangul fillers, the combining
     * grapheme joiner, variation selectors and the code points kept unassigned for more of them. So is every space
     * separator (category Zs) other than the ordinary space U+0020: U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F
     * and U+3000, which a viewer shows as a space or a mark, so a name ending in a no-break space would look like a
     * valid name followed by an ordinary space. An escaped character outside the Basic Multilingual Plane, such as a
     * tag character or a variation selector from U+E0100, is escaped as its two UTF-16 units, each a backslash,
     * {@code u} and four hex digits.
     *
     * <p>
     * Escaping text that has already been escaped changes nothing, because a backslash isn't a control, format,
     * space-separator or default-ignorable character, so a caller that can't tell whether a message has been through
     * here may escape it again.
     * </p>
     *
     * <p>
     * A lone surrogate, half of a pair without the other half, is escaped too: a logger's encoder would otherwise
     * write it as {@code ?}, so the log would differ from the message and two different names would log the same. A
     * valid surrogate pair, such as an emoji, is kept whole.
     * </p>
     *
     * @param text The text
     * @return The text, with every character that could start a line, every format or other default-ignorable
     *         character, every space separator other than U+0020 and every lone surrogate escaped
     */
    public static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        int c;
        for (int i = 0; i < text.length(); i += Character.charCount(c)) {
            c = text.codePointAt(i);
            switch (c) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    int type = Character.getType(c);
                    // The loop reads code points, so only a lone surrogate has the type SURROGATE.
                    if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                            || type == Character.SURROGATE || isOtherDefaultIgnorable(c)
                            || (type == Character.SPACE_SEPARATOR && c != ' ')) {
                        for (char unit : Character.toChars(c)) {
                            appendEscape(escaped, unit);
                        }
                    } else {
                        escaped.appendCodePoint(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Tells whether a code point is one of Unicode's {@code Default_Ignorable_Code_Point} characters that isn't a
     * format character, which {@code Character} has no test for: the ranges of {@link #OTHER_DEFAULT_IGNORABLE}.
     *
     * @param c The code point
     * @return {@code true} if a viewer shows it as nothing
     */
    private static boolean isOtherDefaultIgnorable(int c) {
        // The ranges are in order, so a code point below the next one, as ASCII is below the first, is none of them.
        for (int i = 0; i < OTHER_DEFAULT_IGNORABLE.length; i += 2) {
            if (c < OTHER_DEFAULT_IGNORABLE[i]) {
                return false;
            }
            if (c <= OTHER_DEFAULT_IGNORABLE[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends one UTF-16 unit as a backslash, {@code u} and four lowercase hex digits, as
     * {@code String.format("\\u%04x", unit)} would, without parsing a format for every character.
     *
     * @param escaped What to append to
     * @param unit    The unit
     */
    static void appendEscape(StringBuilder escaped, char unit) {
        escaped.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            escaped.append(Character.forDigit((unit >> shift) & 0xF, 16));
        }
    }

    /**
     * Sets the current thread's interrupt status again when what was caught was caused by an interrupt, or carries
     * one as a suppressed exception. A rule, listener or language interrupted while it blocks throws an
     * {@link InterruptedException}, which clears the status, and the engine receives it wrapped by MVEL or the
     * language, or suppressed on the body's exception by a {@code try}-with-resources whose {@code close()} was
     * interrupted; without this, the caller of {@code run()} couldn't tell the thread had been interrupted. The cause
     * chain is read first, then the suppressed exceptions as {@link #fatalError} reads them: one the engine added
     * with {@link ReportedFailure#addSuppressedByEngine} isn't read, and one past the {@value #MAX_EXCEPTIONS_READ}
     * exceptions read is missed. A
     * {@link java.io.InterruptedIOException} doesn't count: a {@link java.net.SocketTimeoutException} is one.
     *
     * @param thrown What was caught, or {@code null}
     */
    static void keepInterruptStatus(Throwable thrown) {
        List<Throwable> chain = causeChain(thrown);
        for (Throwable t : chain) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (suppressedMatching(chain, INTERRUPT, null) != null) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Finds the last exception in {@code e}'s cause chain (see {@link #causeChain}). {@code mvel.ExceptionReads} keeps
     * a copy, which mvel's {@code ExceptionReadsCopiesTest} checks against this one.
     *
     * @param e The exception
     * @return The last link of its cause chain that could be read, which is {@code e} if it has no cause
     */
    static Throwable rootCause(Throwable e) {
        List<Throwable> chain = causeChain(e);
        return chain.get(chain.size() - 1);
    }

    /**
     * Logs a failure the engine reports or rethrows at ERROR. The first message logged in a JVM loads and initializes
     * classes of the logging provider's, such as SLF4J's {@code Level} and {@code FormattingTuple} with slf4j-simple,
     * which no engine's build does, and that can take more stack than the room the engine checks for before a load or
     * a run, so deep in a stack the log call itself can overflow. The line is then lost, and the caller still reports
     * or rethrows the failure, rather than a {@link StackOverflowError} in its place (#1208). Whatever records the
     * failure as logged has recorded it already, or still does, so a load or a run around this one doesn't log it
     * again. Any other failure of the log call is thrown, as before, and so is an {@link InternalError} that doesn't
     * wrap an overflow (see {@link #isOverflow}).
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
            if (!isOverflow(internal)) {
                throw internal;
            }
        }
    }

    /**
     * Logs a warning at WARN, as {@link #logError} logs a failure at ERROR: an overflow inside the log call, as the
     * first message logged in a JVM can overflow deep in a stack, loses the line, and the caller goes on as it does
     * once the line is logged, rather than throwing a {@link StackOverflowError} (#1208). Any other failure of the log
     * call is thrown, as before, and so is an {@link InternalError} that doesn't wrap an overflow (see
     * {@link #isOverflow}).
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
            if (!isOverflow(internal)) {
                throw internal;
            }
        }
    }

    /**
     * Tells whether {@code e} is a {@link StackOverflowError}, or what was thrown in place of one: walking its cause
     * chain (see {@link #causeChain}) from the top, a {@link StackOverflowError} comes before any {@link LinkageError}.
     * A {@link NoClassDefFoundError} or an {@link ExceptionInInitializerError} above the overflow says a class was left
     * unusable, by an overflow in its static initializer, maybe in an earlier load, and no change to the rule or the
     * stack's depth helps, so that isn't an overflow, as JDK 25 and 26 make one with the overflow as its cause (#1116).
     * An {@link InternalError} around one, as the JDK wraps an overflow it hits defining a hidden class, is (#1141).
     * The chain is read link by link, with nothing allocated, so the engine's last-resort checks, which run when
     * handling a failure has itself failed, as allocating can, can ask it too: up to {@value #MAX_CAUSE_CHAIN_LENGTH}
     * links, which may repeat in a chain that loops back on itself, so it answers as {@link #causeChain}'s links read
     * in order would. {@code mvel.ExceptionReads} keeps a copy, which mvel's {@code ExceptionReadsCopiesTest} checks
     * against this one, and so does the test kit's {@code KitFailures}, which the kit's {@code KitOverflowCopyTest}
     * checks.
     *
     * @param e The exception
     * @return {@code true} if a {@link StackOverflowError} is in its cause chain with no {@link LinkageError} before it
     */
    static boolean isOverflow(Throwable e) {
        Throwable link = e;
        for (int read = 0; link != null && read < MAX_CAUSE_CHAIN_LENGTH; read++) {
            if (link instanceof LinkageError) {
                return false;
            }
            if (link instanceof StackOverflowError) {
                return true;
            }
            link = causeOf(link);
        }
        return false;
    }

    /**
     * Lists {@code e} and its causes, stopping if the chain loops back on itself, at a link whose {@code getCause()}
     * throws (see {@link #causeOf}), or after {@value #MAX_CAUSE_CHAIN_LENGTH} links: a {@code getCause()} of its own
     * that returns a new exception every time would otherwise make a chain that never ends. {@code mvel.ExceptionReads}
     * keeps a copy, which mvel's {@code ExceptionReadsCopiesTest} checks against this one.
     */
    static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && chain.size() < MAX_CAUSE_CHAIN_LENGTH && seen.add(t); t = causeOf(t)) {
            chain.add(t);
        }
        return chain;
    }

    /**
     * Names a rule's condition or action for a message, such as {@code Condition for rule 'prime-rate'}.
     *
     * @param kind     Whether it's the rule's condition or its action
     * @param ruleName The rule's name
     * @return The name
     */
    static String expression(ExpressionKind kind, String ruleName) {
        return (kind == ExpressionKind.CONDITION ? "Condition" : "Action") + " for rule '" + quote(ruleName) + "'";
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
