package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * How the engine treats what rules, listeners and expression languages throw: which errors must reach the caller
 * unchanged, and how an exception is described in an error message. Only {@link #fatalInsteadOf} logs, and under the
 * engine's logger name, {@code io.github.brantunger.unruly.engine}, as every failure is logged.
 * <b>Internal:</b> this class may change in any release. It's public only so that the {@code api} package can escape
 * text the way the engine does, rather than keeping a copy of the escaping that could drift.
 */
public final class Failures {

    /** How much of an exception's message an error message includes; see {@link #describe}. */
    static final int MAX_DESCRIPTION_LENGTH = 1_000;

    /** How much of a fact, rule or language name a message includes; see {@link #quote}. */
    static final int MAX_NAME_LENGTH = 200;

    /** How many links of an exception's cause chain the engine reads; see {@link #causeChain}. */
    static final int MAX_CAUSE_CHAIN_LENGTH = 100;

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

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    private Failures() {
    }

    /**
     * Tells whether {@code thrown} is an {@link Error} the engine must not absorb, because the JVM itself is failing
     * and no message about a rule would help: a {@link VirtualMachineError} other than {@link StackOverflowError},
     * such as {@link OutOfMemoryError}, {@link InternalError} or {@link UnknownError}.
     *
     * <p>
     * Every other error comes from the code being run, so it's handled like an exception and reported with the name of
     * the rule it came from:
     * </p>
     * <ul>
     *     <li>{@link StackOverflowError} (runaway recursion), which is itself a {@code VirtualMachineError} and so is
     *     excluded here on purpose;</li>
     *     <li>{@link AssertionError} ({@code assert} in a rule, a listener or a language);</li>
     *     <li>every {@link LinkageError} — {@link NoClassDefFoundError}, {@link IllegalAccessError},
     *     {@link IncompatibleClassChangeError}, {@link ExceptionInInitializerError}, {@link VerifyError} and the rest —
     *     which means a class a rule uses is missing or can't be read: a configuration problem in one rule, not a
     *     failing JVM;</li>
     *     <li>any other {@link Error}, such as {@link java.io.IOError} or
     *     {@link java.util.ServiceConfigurationError}.</li>
     * </ul>
     *
     * @param thrown One throwable from a cause chain
     * @return {@code true} if {@code thrown} must be rethrown unchanged
     */
    private static boolean isFatal(Throwable thrown) {
        // StackOverflowError is a VirtualMachineError, so excluding it needs its own check.
        return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError);
    }

    /**
     * Finds the fatal {@link Error} (see {@link #isFatal}) in what was caught: the throwable itself, or one of its
     * causes. An error from Java code a rule calls, such as a method, a getter or a lambda held in a fact, reaches the
     * engine inside MVEL's own exception, so checking only the outer exception let an {@link OutOfMemoryError} be
     * absorbed into a {@link RuleExecutionException}.
     *
     * @param thrown What was caught, or {@code null}
     * @return The first fatal error in {@code thrown}'s cause chain, or {@code null} if there is none
     */
    static Error fatalError(Throwable thrown) {
        for (Throwable t : causeChain(thrown)) {
            if (isFatal(t)) {
                return (Error) t;
            }
        }
        return null;
    }

    /**
     * Finds the {@link Error} of any kind in what was caught: the throwable itself, or one of its causes. This is
     * what a cancelled run asks before it reports a stop, because an error from Java code a rule calls, such as a
     * method, a getter or a lambda held in a fact, reaches the engine wrapped in the expression language's own
     * exception, and a run past its deadline or interrupted would otherwise report it as a stop that names no rule
     * rather than as that rule's failure.
     *
     * <p>
     * Unlike {@link #fatalError}, which looks for the first error the engine must not absorb (see {@link #isFatal})
     * and so may pass over a non-fatal one above it, this finds the first error in the chain whether it is fatal or
     * not.
     * </p>
     *
     * <p>
     * A failure of a {@code run()} started from a condition or action answers for the chain below it: it recorded the
     * error in its own cause chain when it was built, so an error many nested runs down is still found, although each
     * run adds a link and only {@value #MAX_CAUSE_CHAIN_LENGTH} links are read (see {@link #below}).
     * </p>
     *
     * @param thrown What was caught, or {@code null}
     * @return The first error in {@code thrown}'s cause chain, or {@code null} if there is none
     */
    static Error errorInChain(Throwable thrown) {
        return below(thrown).error();
    }

    static void throwIfPresent(Error fatal) {
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Chooses which of two fatal errors, from two things closed one after the other, the caller throws: the first. The
     * other was logged when it was caught, as {@link Closing} logs every failure to close, and goes no further.
     *
     * @param first  The fatal error from what was closed first, or {@code null}
     * @param second The fatal error from what was closed after it, or {@code null}
     * @return {@code first} if there is one, else {@code second}, which may be {@code null}
     */
    static Error first(Error first, Error second) {
        return first != null ? first : second;
    }

    /**
     * Chooses what the caller throws when closing what a failure left behind threw a fatal {@link Error}: a fatal
     * error beats any other failure, and of two fatal errors the first wins. So {@code closeFatal} replaces
     * {@code failure} only when neither {@code failure} nor any of its causes is fatal (see {@link #fatalError}), and
     * then carries it as a suppressed exception, or, if it can't carry one, {@code failure} is logged at WARN. A fatal
     * error that loses was logged when it was caught, and goes no further.
     *
     * <p>
     * A {@code failure} caused by an interrupt sets the thread's interrupt status again when it's replaced (see
     * {@link #keepInterruptStatus}), because the caller that would have set it never sees it. The fatal error that
     * can't carry a suppressed exception is one built with suppression disabled, as the {@link OutOfMemoryError} the
     * JVM keeps ready for when it has no memory left is: it would lose {@code failure}, so it's logged instead.
     * </p>
     *
     * @param failure    What was being thrown when the closing began
     * @param closeFatal The fatal error closing threw, or {@code null} if it threw none
     * @return {@code closeFatal}, with {@code failure} added to its suppressed exceptions, or logged if it can't carry
     *         one, if the caller throws it in place of {@code failure}; otherwise {@code null}, and the caller throws
     *         {@code failure}
     */
    static Error fatalInsteadOf(Throwable failure, Error closeFatal) {
        if (closeFatal == null || fatalError(failure) != null) {
            return null;
        }
        keepInterruptStatus(failure);
        closeFatal.addSuppressed(failure);
        // A fatal error built with suppression disabled ignores addSuppressed().
        if (closeFatal.getSuppressed().length == 0) {
            log.warn("A failure was replaced by the fatal error {}, which can't carry it as a suppressed exception: {}",
                    describeWithClass(closeFatal), describeWithClass(failure));
        }
        return closeFatal;
    }

    /**
     * Describes an exception for an error message:
     * <ul>
     *     <li>its message, or its class name if it has none (NPEs and bare RuntimeExceptions carry no message)</li>
     *     <li>the class of its root cause when that has no message either, and the root cause's class and message
     *     when an exception above it has none: MVEL copies a cause's missing message into its own as
     *     {@code ": null"}, which would hide what went wrong</li>
     *     <li>at most {@value #MAX_DESCRIPTION_LENGTH} characters of the message: MVEL pads its messages with spaces up
     *     to the error's column, so a long expression produced messages hundreds of thousands of characters long</li>
     *     <li>the message {@link #escape escaped}, because the engine didn't write it: a language quotes the fact
     *     values a failing expression read, and those come from request data far more often than names do</li>
     *     <li>its class name and a note that its message is unavailable when {@code getMessage()} throws (see
     *     {@link #messageOf}), and likewise for a cause</li>
     * </ul>
     * The message is shortened before it's escaped, so the count of what was left out counts the exception's own
     * characters. The exception is never changed: its {@code getMessage()} still reads as the language wrote it.
     * A failure of a {@code run()} started from a condition, an action, the output supplier, a listener callback or a
     * language is described by that run's innermost failure only, so a failure nested many runs deep isn't repeated
     * once per level.
     *
     * @param e The exception to describe
     * @return A description of the exception for an error message
     */
    static String describe(Throwable e) {
        RuleExecutionException nested = nestedRunFailure(e);
        if (nested != null) {
            return "a nested run() failed: " + messageOf(nested);
        }
        String text = escape(truncate(messageOr(e, e.getClass().getName())));
        return text + causeNote(causeChain(e), readableMessage(e));
    }

    /**
     * Names the root cause when a description would otherwise hide it: its class when it has no message, and its class
     * and message when an exception above it has none, unless the part of the first exception's text that the
     * description shows already has it: the first message for {@link #describe}, its {@code toString()} for
     * {@link #describeWithClass}. That part is the text's first {@value #MAX_DESCRIPTION_LENGTH} characters as
     * {@link #truncate} keeps them, searched raw, without {@link #truncate}'s note of what was left out: a root cause's
     * message found only past the cut isn't shown, and searching all of a long text took time that grew with the
     * square of its length. A message that can't be read (see {@link #messageOf})
     * hides the root cause as a missing one does, and a root cause whose message can't be read is named with the note
     * that it's unavailable. Only a text that can be read can already show the root cause's message: two notes that
     * messages are unavailable read the same whatever the messages were.
     *
     * @param chain An exception and its causes
     * @param text  The text the description shows of the first exception, whole, or {@code null} if it shows none
     *              that can be read: {@link #describe}'s message, or {@link #describeWithClass}'s {@code toString()}
     * @return {@code " (caused by ...)"}, or an empty string if nothing is hidden
     */
    private static String causeNote(List<Throwable> chain, @Nullable String text) {
        if (chain.subList(1, chain.size()).isEmpty()) {
            return "";
        }
        Throwable root = chain.get(chain.size() - 1);
        String rootMessage = messageOf(root);
        if (rootMessage == null) {
            return " (caused by " + quote(root.getClass().getName()) + ")";
        }
        // The kept characters themselves, not truncate()'s text, whose note of what was left out could match.
        String shown = text == null || text.length() <= MAX_DESCRIPTION_LENGTH
                ? text : text.substring(0, keptLength(text, MAX_DESCRIPTION_LENGTH));
        boolean hidden = !chain.stream().allMatch(t -> readableMessage(t) != null)
                && (shown == null || !shown.contains(rootMessage));
        return hidden
                ? " (caused by " + quote(root.getClass().getName()) + ": " + escape(truncate(rootMessage)) + ")"
                : "";
    }

    /**
     * Describes an exception with its class, as {@link Throwable#toString()} does, for a message about code the engine
     * calls outside any rule, such as the output factory: shortened to {@value #MAX_DESCRIPTION_LENGTH} characters,
     * then escaped, like {@link #describe}, and naming a root cause it would otherwise hide as {@link #describe} does.
     * The root cause counts as shown only when the part of the {@code toString()} this shows, its first
     * {@value #MAX_DESCRIPTION_LENGTH} characters as {@link #truncate} keeps them, has its message, so neither a class
     * name that pushes the message past the cut nor a {@code toString()} that leaves the message out hides it. The
     * note is left out when the text already has it, as the message of a {@code run()} started from that code does
     * unless it was shortened, so it isn't there twice. When {@code toString()} throws, its class name stands in with a
     * note that its message is unavailable (see {@link #textOf}), which shows no root cause.
     *
     * @param e The exception to describe
     * @return Its class name, its message if it has one, and a note of its root cause if the text hides it and doesn't
     *         already have that note
     */
    static String describeWithClass(Throwable e) {
        String readable = read(e::toString, thrown -> null);
        String text = escape(truncate(readable != null ? readable : textOf(e)));
        String note = causeNote(causeChain(e), readable);
        return text.contains(note) ? text : text + note;
    }

    /**
     * Reads an exception's message without letting a failure to read it escape. The engine reads the message of what
     * rules, listeners and languages throw to describe it, and a {@code getMessage()} of their own can throw, such as
     * one built from a field that is {@code null}; a failure the engine was handling would then end as that one.
     * Whatever {@code getMessage()} throws, a fatal {@link Error} too, only makes the message unavailable (see
     * {@link #read}).
     *
     * @param e The exception
     * @return Its message, or {@code null} if it has none, or {@code (message unavailable: ...)}, naming the class of
     *         what {@code getMessage()} threw, if it can't be read
     */
    static String messageOf(Throwable e) {
        return read(e::getMessage, unavailable(""));
    }

    /**
     * Reads an exception's message as {@link #messageOf} does, telling no message and one that can't be read apart
     * from one that says something.
     *
     * @param e The exception
     * @return Its message, or {@code null} if it has none or it can't be read
     */
    private static String readableMessage(Throwable e) {
        return read(e::getMessage, thrown -> null);
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
        return read(() -> {
            String message = e.getMessage();
            return message != null ? message : ifNone;
        }, unavailable(ifNone + " "));
    }

    /**
     * Reads an exception's {@link Throwable#toString()} as {@link #messageOf} reads its message. The default
     * {@code toString()} reads the message, so an exception whose {@code getMessage()} throws fails here too.
     *
     * @param e The exception
     * @return Its {@code toString()}, or its class name followed by {@code (message unavailable: ...)} if that throws
     */
    static String textOf(Throwable e) {
        return read(e::toString, unavailable(e.getClass().getName() + " "));
    }

    /**
     * Makes the note that an exception's text can't be read, naming only the class of what reading it threw: that
     * one's own message could be what throws.
     *
     * @param prefix What goes before the note
     * @return What turns what the accessor threw into {@code prefix} and the note
     */
    private static Function<Throwable, String> unavailable(String prefix) {
        return thrown -> prefix + "(message unavailable: " + thrown.getClass().getName() + ")";
    }

    /**
     * Calls one of the accessors of an exception the engine didn't create, none of which is final, without letting
     * anything it throws escape, a fatal {@link Error} too: what an accessor throws says nothing about the failure the
     * engine is handling, which is still handled as it would be, its own fatal errors included.
     *
     * @param accessor The accessor
     * @param ifThrown What to return instead, from what the accessor threw
     * @param <T>      What the accessor returns
     * @return What the accessor returned, or what {@code ifThrown} makes of what it threw
     */
    private static <T> T read(Supplier<T> accessor, Function<Throwable, T> ifThrown) {
        try {
            return accessor.get();
        } catch (Throwable thrown) {
            return ifThrown.apply(thrown);
        }
    }

    /**
     * Reads an exception's cause as {@link #messageOf} reads its message: {@code getCause()} isn't final either.
     *
     * @param e The exception
     * @return Its cause, or {@code null} if it has none or {@code getCause()} throws
     */
    static Throwable causeOf(Throwable e) {
        return read(e::getCause, thrown -> null);
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
        return read(() -> List.copyOf(e.issues()), thrown -> List.of());
    }

    /**
     * Shortens a message to at most {@value #MAX_DESCRIPTION_LENGTH} characters, saying how many were left out. A
     * surrogate pair the limit falls inside is left out whole, so the message never ends in half a character.
     * {@code mvel.FactNames} keeps a copy of this, for MVEL's issues.
     *
     * @param text The message
     * @return The message, shortened if it was longer
     */
    static String truncate(String text) {
        if (text.length() <= MAX_DESCRIPTION_LENGTH) {
            return text;
        }
        int kept = keptLength(text, MAX_DESCRIPTION_LENGTH);
        return text.substring(0, kept) + "... (" + (text.length() - kept) + " more characters)";
    }

    /**
     * How many characters of text longer than {@code limit} to keep: {@code limit}, or one fewer when the last of them
     * is a high surrogate. {@code mvel.FactNames.quote} and {@code mvel.FactNames.truncate} keep copies of this.
     */
    private static int keptLength(String text, int limit) {
        return Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
    }

    /**
     * Finds the innermost failure of a {@code run()} started by the code that threw {@code e}. That run already
     * logged it and told its listeners. Only an exception an engine threw counts ({@link ReportedFailure}, of any
     * engine): a {@link RuleExecutionException} a language or a rule throws itself was never logged.
     *
     * @param e What was caught, or {@code null}
     * @return The innermost {@link ReportedFailure} in {@code e}'s cause chain, or {@code null}
     */
    static RuleExecutionException nestedRunFailure(Throwable e) {
        return innermostReported(e);
    }

    /**
     * Tells whether a {@code run()} started by the code that threw {@code e} stopped for the same reason as the run
     * around it, and so already logged that stop.
     *
     * @param e        What was caught
     * @param deadline The deadline the run around it passed, or {@code null} if its thread was interrupted
     * @return {@code true} if the innermost {@link ReportedFailure} in {@code e}'s cause chain is that same stop
     */
    static boolean nestedRunStopped(Throwable e, Instant deadline) {
        ReportedFailure innermost = innermostReported(e);
        return innermost != null && innermost.isStopFor(deadline);
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
     * What an exception's cause chain holds of the engine's own failures: the innermost {@link ReportedFailure}, and
     * the first {@link Error}.
     *
     * @param innermost The innermost failure of a nested {@code run()}, or {@code null} if there is none
     * @param error     The first error, or {@code null} if there is none
     */
    record Below(ReportedFailure innermost, Error error) {
    }

    /**
     * Reads an exception's cause chain for the innermost {@link ReportedFailure} and the first {@link Error}, in one
     * walk. Every nested run adds a link to the chain and only {@value #MAX_CAUSE_CHAIN_LENGTH} links are read, so a
     * failure or an error more than that many runs down would be out of reach; but each {@link ReportedFailure}
     * recorded both for its own chain when it was built, so the walk stops at the first one that did. One serialized
     * before they were recorded answers for nothing: the walk goes on past it, and if it finds no failure that recorded
     * them, the last of those in the chain is the innermost, as before.
     *
     * @param e What was caught, or {@code null}
     * @return The innermost failure and the first error, each {@code null} if the chain has none
     */
    static Below below(Throwable e) {
        ReportedFailure innermost = null;
        Error error = null;
        for (Throwable t : causeChain(e)) {
            if (t instanceof ReportedFailure failure) {
                if (failure.recorded()) {
                    return new Below(failure.innermost(), error != null ? error : failure.error());
                }
                innermost = failure;
            } else if (error == null && t instanceof Error found) {
                error = found;
            }
        }
        return new Below(innermost, error);
    }

    /**
     * Makes a fact, rule or language name safe to put in a message the engine logs: shortened to
     * {@value #MAX_NAME_LENGTH} characters (UTF-16 units), then {@link #escape escaped}; each escaped unit shows as 2
     * or 6 characters. The name keeps one fewer where the limit falls inside a surrogate pair, which is left out whole.
     * {@code mvel.FactNames} keeps a copy of this, because the {@code mvel} package may not use this one.
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
        int kept = keptLength(name, MAX_NAME_LENGTH);
        return name.substring(0, kept) + "... (" + (name.length() - kept) + " more characters)";
    }

    /**
     * Makes a list of names safe to put in a message, shown as a {@link java.util.List}'s {@code toString()} shows it,
     * such as {@code [eu, retail]}: each name shortened as {@link #quote} does, the list shortened as
     * {@link #truncate} does, and the whole escaped last, so a cut never falls inside an escape. The count of what was
     * left out of the list counts the characters of the list as it was before it was escaped: the shortened names, the
     * notes of what was left out of each, and the separators.
     *
     * @param names The names; a {@code null} one is shown as {@code null}
     * @return The list, its names shortened to {@value #MAX_NAME_LENGTH} characters and it to
     *         {@value #MAX_DESCRIPTION_LENGTH}, then escaped
     */
    public static String quoteAll(Collection<? extends @Nullable String> names) {
        return escape(truncate(names.stream().map(name -> name == null ? "null" : shorten(name))
                .collect(Collectors.joining(", ", "[", "]"))));
    }

    /**
     * Makes text the engine didn't write safe to put in a message it logs, without shortening it. Line breaks, tabs
     * and other control characters, and the Unicode line and paragraph separators, are escaped ({@code \n},
     * {@code \r}, {@code \t}, or a backslash, {@code u} and four hex digits), so neither a name nor a fact value that
     * reached the message from request data can start a log line of its own. So are the Unicode format characters
     * (category Cf), such as bidi controls and zero-width and tag characters, which could reorder the rest of a line in
     * a viewer or make two different names look the same, and the other characters Unicode marks
     * {@code Default_Ignorable_Code_Point}, which a viewer shows as nothing, such as the Hangul fillers, the combining
     * grapheme joiner, variation selectors and the code points kept unassigned for more of them. An escaped character
     * outside the Basic Multilingual Plane, such as a tag character or a variation selector from U+E0100, is escaped as
     * its two UTF-16 units, each a backslash, {@code u} and four hex digits.
     *
     * <p>
     * Escaping text that has already been escaped changes nothing, because a backslash isn't a control, format or
     * default-ignorable character, so a caller that can't tell whether a message has been through here may escape it
     * again.
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
     *         character and every lone surrogate escaped
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
                            || type == Character.SURROGATE || isOtherDefaultIgnorable(c)) {
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
     * {@code mvel.FactNames} keeps a copy of this.
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
     * {@code mvel.FactNames} keeps a copy of this.
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
     * Sets the current thread's interrupt status again when what was caught was caused by an interrupt. A rule,
     * listener or language interrupted while it blocks throws an {@link InterruptedException}, which clears the
     * status, and the engine receives it wrapped by MVEL or the language; without this, the caller of {@code run()}
     * couldn't tell the thread had been interrupted. A {@link java.io.InterruptedIOException} doesn't count: a
     * {@link java.net.SocketTimeoutException} is one.
     *
     * @param thrown What was caught, or {@code null}
     */
    static void keepInterruptStatus(Throwable thrown) {
        for (Throwable t : causeChain(thrown)) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static Throwable rootCause(Throwable e) {
        List<Throwable> chain = causeChain(e);
        return chain.get(chain.size() - 1);
    }

    /**
     * Lists {@code e} and its causes, stopping if the chain loops back on itself, at a link whose {@code getCause()}
     * throws (see {@link #causeOf}), or after {@value #MAX_CAUSE_CHAIN_LENGTH} links: a {@code getCause()} of its own
     * that returns a new exception every time would otherwise make a chain that never ends.
     */
    private static List<Throwable> causeChain(Throwable e) {
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
