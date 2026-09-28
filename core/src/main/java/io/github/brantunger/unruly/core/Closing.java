package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Closes the sessions and compilers expression languages created for a rule list. Whatever a {@code close()} throws,
 * any {@link Throwable}, is logged at WARN, and the rest are still closed; unless it's the failure of a
 * {@code run()} or a {@code load()} the {@code close()} started, or a fatal {@link Error} that run logged, which that
 * run or load logged already (see {@link LoggedFailures#logged}). A fatal error (see {@link Failures#fatalError}) is
 * returned unchanged once everything has been closed, for the caller to throw once it has closed the rest of what it
 * closes: the first, if several were thrown, carrying the others as suppressed exceptions (see
 * {@link Failures#first}). Nothing else a {@code close()} throws reaches the caller.
 */
final class Closing {

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    private Closing() {
    }

    /**
     * Closes the sessions of one copy of the rules.
     *
     * @param sessions The sessions by language name
     * @return The first fatal {@link Error} a session threw, or {@code null} if none did
     */
    static Error sessions(Map<String, Session> sessions) {
        return closeAll(sessions, "a session");
    }

    /**
     * Closes the compilers of a rule list.
     *
     * @param compilers The compilers by language name
     * @return The first fatal {@link Error} a compiler threw, or {@code null} if none did
     */
    static Error compilers(Map<String, ExpressionCompiler> compilers) {
        return closeAll(compilers, "its compiler");
    }

    // Any Throwable: one that stopped the loop would leave the rest open, and callers that close more after this, such
    // as the compilers after the sessions, would never reach them. Every caller is inside a run, a load(), a
    // validate() or a close(), so what a run a close() starts logged is known.
    private static Error closeAll(Map<String, ? extends AutoCloseable> resources, String what) {
        Error fatal = null;
        for (Map.Entry<String, ? extends AutoCloseable> resource : resources.entrySet()) {
            try {
                resource.getValue().close();
            } catch (Throwable e) {
                Failures.keepInterruptStatus(e);
                if (!LoggedFailures.logged(e)) {
                    log.warn("The '{}' expression language failed to close {}: {}", Failures.quote(resource.getKey()),
                            what, Failures.describe(e));
                }
                fatal = Failures.first(fatal, Failures.fatalError(e));
            }
        }
        return fatal;
    }
}
