package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Closes the sessions and compilers expression languages created for a rule list. A failure to close is logged at WARN
 * and doesn't fail the run or the load that closes them; the rest are still closed. A fatal {@link Error} (see
 * {@link Failures#fatalError}) is returned unchanged once everything has been closed, for the caller to throw once it
 * has closed the rest of what it closes: the first, if several were thrown.
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

    private static Error closeAll(Map<String, ? extends AutoCloseable> resources, String what) {
        Error fatal = null;
        for (Map.Entry<String, ? extends AutoCloseable> resource : resources.entrySet()) {
            try {
                resource.getValue().close();
            } catch (Exception | Error e) {
                Failures.keepInterruptStatus(e);
                log.warn("The '{}' expression language failed to close {}: {}", Failures.quote(resource.getKey()), what,
                        Failures.describe(e));
                if (fatal == null) {
                    fatal = Failures.fatalError(e);
                }
            }
        }
        return fatal;
    }
}
