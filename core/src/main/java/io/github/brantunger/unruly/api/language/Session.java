package io.github.brantunger.unruly.api.language;

/**
 * A language's state for one run of a rule list at a time, such as a single-threaded interpreter context. Compiled
 * conditions and actions are shared by every run, so whatever changes while an expression runs belongs in a session.
 *
 * <p>
 * The engine creates sessions with {@link ExpressionCompiler#newSession()}: one for each language a copy of the rules
 * uses. A run borrows a copy that no other run is using, so a session is used by one run at a time, though possibly on
 * different threads one after another, and every condition and action of that language in the run gets the same
 * session. The engine keeps idle copies for later runs, and closes a session once it no longer needs it: when it drops
 * a copy of the rules, when {@code setRuleList()} has replaced the rules and no run is still using them, or when the
 * engine is closed.
 * </p>
 *
 * <p>
 * A language whose compiled expressions are safe to run on several threads at once, and that keeps no other state
 * between runs, returns {@link #none()}.
 * </p>
 *
 * <p>
 * <b>Implemented by</b> expression languages. A method added to this interface is a {@code default} method, so an
 * existing language keeps compiling and working.
 * </p>
 */
public interface Session extends AutoCloseable {

    /**
     * Returns the session of a language that keeps no state between runs. Closing it does nothing.
     *
     * @return The shared empty session
     */
    static Session none() {
        return NoSession.INSTANCE;
    }

    /**
     * Releases what the session holds. The engine calls it once, when no run is using the session, on the thread that
     * finished with it last. By default, does nothing. An exception it throws is logged at WARN and doesn't fail a run,
     * except a fatal {@link Error}, which is rethrown unchanged.
     */
    @Override
    default void close() {
        // Nothing to release.
    }
}
