package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.Session;

import java.io.Serializable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The compiled MVEL expressions of one copy of a rule list. MVEL caches an accessor in a compiled expression as it
 * runs, and replaces it without synchronization when a later run binds the same name to a different kind of object, so
 * each session compiles its own expressions, the first time each one runs. The engine lets one run at a time use a
 * session, so it needs no synchronization either.
 */
final class MvelSession implements Session {

    private final Map<MvelExpression, Serializable> expressions = new IdentityHashMap<>();

    /**
     * Returns this session's compiled form of an expression, compiling it the first time.
     *
     * @param expression The expression
     * @return MVEL's compiled expression, which only this session runs
     */
    Serializable compiled(MvelExpression expression) {
        return expressions.computeIfAbsent(expression, MvelExpression::newCompiled);
    }
}
