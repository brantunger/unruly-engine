package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.core.FatalErrors;
import io.github.brantunger.unruly.internal.Causes;
import io.github.brantunger.unruly.internal.Lookups;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Reads what an expression language, a rule or the application's code threw, as the engine reads it: which errors
 * the engine rethrows unchanged, what a stack overflow looks like once the JDK or a library has wrapped it, and the
 * message and causes of an exception the language didn't create, whose {@code getMessage()} or {@code getCause()}
 * may throw. It delegates to the engine's own readers rather than keeping a copy of them, so a language that catches
 * what it calls decides as the engine decides: a warm-up that must skip an overflow but rethrow a class left unusable
 * by one (see {@link ExpressionLanguage#prepare()}), an action registered with {@link EvaluationContext#onCancel}
 * that must let a fatal error through, or a class lookup that must read the JVM's "wrong name" error as "not found".
 *
 * <p>
 * Every method here takes what it reads and returns a plain answer, with nothing retained: the class is
 * stateless, and its methods are safe to call from any thread at once. {@link #isOverflow} and {@link #isFatal}
 * allocate nothing and link no call site, so they can be called where handling a failure has itself failed, deep in
 * a stack or with no memory left. Each is fixed for 2.x: what it reads, in what order, how far, and what it answers
 * for {@code null}.
 * </p>
 */
public final class Throwables {

    private Throwables() {
    }

    /**
     * Tells whether a throwable is a {@link StackOverflowError}, or what was thrown in place of one: walking its cause
     * chain from the top, as {@link #causeChain} walks it, a {@code StackOverflowError} comes before any
     * {@link LinkageError}. A {@link NoClassDefFoundError} or an {@link ExceptionInInitializerError} above the
     * overflow says a class was left unusable, by an overflow in its static initializer, maybe in an earlier load,
     * which no change to the rule or the stack's depth helps, as JDK 25 and later make one with the overflow as its
     * cause; so that isn't an overflow. An {@link InternalError} around one, as the JDK wraps an overflow it hits
     * defining a hidden class, is. The engine reports an overflow as the rule's failure, with more stack as the fix,
     * and never as a fatal error (see {@link #isFatal}).
     *
     * <p>
     * The chain is read link by link, at most 100 links, a {@code getCause()} that throws ending it, with nothing
     * allocated and no call site linked, so it can be asked where handling a failure has itself failed. A chain that
     * loops back on itself is read as its first 100 links.
     * </p>
     *
     * @param thrown What was caught, or {@code null}, which isn't an overflow
     * @return {@code true} if a {@code StackOverflowError} is in its cause chain with no {@code LinkageError} before it
     */
    public static boolean isOverflow(@Nullable Throwable thrown) {
        return Causes.isOverflow(thrown);
    }

    /**
     * Tells whether a throwable is an {@link Error} the engine must not absorb, because the JVM itself is failing and
     * no message about a rule would help: a {@link VirtualMachineError} other than {@link StackOverflowError}, such as
     * {@link OutOfMemoryError}, {@link InternalError} or {@link UnknownError}, but not an {@code InternalError} that
     * wraps an overflow (see {@link #isOverflow}). The engine rethrows such an error unchanged from {@code run()},
     * {@code load()}, {@code validate()} and {@code close()}, even when a language wraps it or suppresses it in an
     * exception (see {@link #fatalError}), and reports every other error, an {@link AssertionError} or a
     * {@link LinkageError} included, as the failure of the rule, listener or language it came from.
     *
     * <p>
     * Only the throwable itself is read, not its causes: it allocates nothing and links no call site, so it can be
     * asked where handling a failure has itself failed, deep in a stack or with no memory left. Only an
     * {@code InternalError} has its cause chain read, as {@link #isOverflow} reads it.
     * </p>
     *
     * @param thrown What was caught, or {@code null}, which isn't fatal
     * @return {@code true} if the engine would rethrow {@code thrown} unchanged
     */
    public static boolean isFatal(@Nullable Throwable thrown) {
        return Causes.isFatal(thrown);
    }

    /**
     * Finds the fatal error (see {@link #isFatal}) in what was caught, as the engine finds the one it rethrows: the
     * throwable itself, or one of its causes, or failing that one suppressed on any of them, or among the causes and
     * suppressed exceptions of those, at any depth, as a {@code try}-with-resources whose {@code close()} hits an
     * {@link OutOfMemoryError} while its body throws leaves it. The cause chain is read first, as {@link #causeChain}
     * reads it; then the direct suppressed exceptions of each link, from the top link down, before what any of them
     * leads to, which is read the same way, depth first. Each exception is read once, by identity, and at most 10,000
     * are, so a fatal error past that many is missed, as the engine misses it. A suppressed exception the engine
     * itself added to a failure it reported, beside the one it rethrows, isn't read: the engine keeps there a fatal
     * error a listener threw, and finding it again would take it for the failure's own.
     *
     * <p>
     * It allocates the list and the identity set of {@link #causeChain}, and a walk when any link has suppressed
     * exceptions.
     * </p>
     *
     * @param thrown What was caught, or {@code null}, which holds no fatal error
     * @return The first fatal error found in that order, or {@code null} if there is none among the exceptions read
     */
    public static @Nullable Error fatalError(@Nullable Throwable thrown) {
        return FatalErrors.in(thrown);
    }

    /**
     * Tells whether what was caught was caused by an interrupt, or carries one: an {@link InterruptedException} is
     * the throwable itself, one of its causes, or suppressed on any of them, or among the causes and suppressed
     * exceptions of those, read as {@link #fatalError} reads them, in the same order, with the same limits, and
     * skipping what the engine itself added. A rule, listener or language interrupted while it blocks throws an
     * {@code InterruptedException}, which clears the thread's interrupt status, and the engine receives it wrapped by
     * the language, or suppressed on the body's exception by a {@code try}-with-resources whose {@code close()} was
     * interrupted; the engine sets the status again when it finds one, so the caller of {@code run()} can tell. A
     * {@link java.io.InterruptedIOException} doesn't count: it isn't an {@code InterruptedException}.
     *
     * <p>
     * It allocates what {@link #fatalError} allocates.
     * </p>
     *
     * @param thrown What was caught, or {@code null}, which holds no interrupt
     * @return {@code true} if an {@code InterruptedException} is among the exceptions read
     */
    public static boolean isInterrupt(@Nullable Throwable thrown) {
        return FatalErrors.isInterrupt(thrown);
    }

    /**
     * Reads an exception's message without letting a failure to read it escape. A {@code getMessage()} of the
     * application's own can throw, such as one built from a field that is {@code null}, and a failure being handled
     * would then end as that one. Whatever {@code getMessage()} throws, a fatal {@link Error} too, only makes the
     * message unavailable: it says nothing about the failure being handled. The accessor is called in a {@code try}
     * of its own rather than through a lambda or method reference, so the JVM's first failure links no call site,
     * which could overflow deep in a stack. It allocates only when {@code getMessage()} throws.
     *
     * @param thrown The exception
     * @return Its message, or {@code null} if it has none, or {@code (message unavailable: ...)}, naming the class of
     *         what {@code getMessage()} threw, if it can't be read
     * @throws NullPointerException if {@code thrown} is {@code null}
     */
    public static @Nullable String messageOf(Throwable thrown) {
        return Causes.messageOf(Objects.requireNonNull(thrown, "thrown must not be null"));
    }

    /**
     * Lists an exception and its causes, from the top: the exception, its cause, its cause's cause and so on, each
     * read as {@link #messageOf} reads a message, so a {@code getCause()} that throws ends the chain there. The chain
     * also stops before a link it holds already, by identity, as a chain that loops back on itself has one, and after
     * 100 links, as a {@code getCause()} of its own that returns a new exception every time would otherwise make a
     * chain that never ends. The engine reads a cause chain this way wherever it describes a failure or looks for a
     * fatal error in one.
     *
     * <p>
     * It allocates a new list, which the caller owns and may change, and an identity set while it reads.
     * </p>
     *
     * @param thrown The exception, or {@code null}, whose chain is empty
     * @return A new list of {@code thrown} and the causes that could be read, in order, which is empty only for
     *         {@code null}
     */
    public static List<Throwable> causeChain(@Nullable Throwable thrown) {
        return Causes.causeChain(thrown);
    }

    /**
     * Finds the last exception in an exception's cause chain, as {@link #causeChain} reads it: the exception itself
     * if it has no cause, or the last cause that could be read, which the engine names in a message when the
     * description would otherwise hide it. It allocates what {@link #causeChain} allocates.
     *
     * @param thrown The exception
     * @return The last link of its cause chain
     * @throws NullPointerException if {@code thrown} is {@code null}
     */
    public static Throwable rootCause(Throwable thrown) {
        return Causes.rootCause(Objects.requireNonNull(thrown, "thrown must not be null"));
    }

    /**
     * Tells whether an error a class lookup threw only means that a class file was found for the name looked up in
     * a different case, as a class directory on a case-insensitive file system finds one, so there is no class by
     * that name: the JVM's "wrong name" {@link NoClassDefFoundError}. Its message names the class asked for, in
     * internal form ({@code com/acme/Rule}), on one side: first on HotSpot, as in {@code com/acme/rule (wrong name:
     * com/acme/Rule)}, and last on OpenJ9, as in {@code ... (wrong name: com/acme/rule)}. An error about a class the
     * one looked up depends on, such as its superclass, names it on neither side, so it isn't taken for it; nor is
     * any other error, or one whose message can't be read (see {@link #messageOf}). The engine treats a lookup that
     * threw such an error as a class that isn't there, and tries the next form of the name.
     *
     * <p>
     * HotSpot cuts the message at 1023 bytes of UTF-8, so for a name over about 1,000 ASCII characters, or 500
     * two-byte ones, {@code " (wrong name: "} is cut off and the error isn't recognised: the class then can't be
     * loaded rather than being missing, an error, never a wrong answer. {@link ImportLimits#MAX_IMPORT_LENGTH} keeps
     * an ASCII import under the cut. For a {@code NoClassDefFoundError}, it allocates the internal form of a name with
     * dots, the strings it compares the message with, and what {@link #messageOf} allocates when the message can't be
     * read; for any other error, nothing.
     * </p>
     *
     * @param error What the lookup threw, or {@code null}, which isn't the error
     * @param name  The binary name the lookup asked for, with dots, as {@code com.acme.Rule} or
     *              {@code com.acme.Rule$Nested}
     * @return {@code true} for the JVM's "wrong name" {@code NoClassDefFoundError} for {@code name}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static boolean isWrongName(@Nullable Throwable error, String name) {
        Objects.requireNonNull(name, "name must not be null");
        return error != null && Lookups.isWrongName(error, name);
    }

    /**
     * Tells whether an error is the one a native image built with strict reachability metadata throws for a class or
     * method lookup it has no metadata for, existing or not, where any other build, and the JVM, throw
     * {@link ClassNotFoundException} or {@link NoSuchMethodException}, or list what is there: GraalVM's
     * {@code org.graalvm.nativeimage.MissingReflectionRegistrationError}, an {@link Error} in GraalVM for JDK 21 and
     * a {@link LinkageError} in GraalVM for JDK 25. It is told by its class's name, so neither the engine nor a
     * language needs a dependency on GraalVM's SDK, and no other error, a {@link StackOverflowError} least of all, is
     * taken for it. The engine treats such an error as "not found", so a package import still works in an image. It
     * allocates nothing but the name of the error's class, the first time the JVM is asked for it.
     *
     * @param error What the lookup threw, or {@code null}, which isn't the error
     * @return {@code true} for GraalVM's {@code MissingReflectionRegistrationError}
     */
    public static boolean isMissingRegistration(@Nullable Throwable error) {
        return error != null && Lookups.isMissingRegistration(error);
    }
}
