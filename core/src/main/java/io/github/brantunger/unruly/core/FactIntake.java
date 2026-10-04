package io.github.brantunger.unruly.core;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;

/**
 * Takes in a run's facts for an engine: collects their values, widening a boxed primitive to the primitive type its
 * fact was declared with, and checks them, first as the engine itself does and then with the languages of the rules in
 * use. It holds only the engine's declared facts, whether a run may supply only those, the facts among them declared
 * with a primitive type, the fact names the engine's languages reserve, and the engine's logger, all fixed when the
 * engine is built.
 */
final class FactIntake {

    // The engine's logger, which logs the facts a run is rejected for.
    private final Logger log;
    // The declared type of each fact, by name, a primitive type as it was declared, and whether a run may supply only
    // those facts.
    private final Map<String, Class<?>> declaredFacts;
    private final boolean allFactsDeclared;
    // The facts declared with a primitive type, by name, which a run widens a boxed primitive to. Empty for most
    // engines, which then convert nothing.
    private final Map<String, Class<?>> primitiveFacts;
    // The language that reserves each fact name the engine's languages reserve, by name, which no fact may have; and
    // the names alone, which a run's names are looked up in.
    private final Map<String, String> reservedFactNames;
    private final Set<String> reserved;

    /**
     * Creates the fact intake of an engine. Built with the engine, so {@code FactIntake} itself is loaded then, not by
     * a run, which may be nested deep in another run's stack (see StackHeadroom). {@link FactNames} and
     * {@link FactNames.Problem} are loaded then too, and {@code FactIntake} itself neither loads a class nor bootstraps
     * a lambda the first time a run's fact name is rejected or a run's fact declared with a primitive type is widened.
     *
     * @param log              The engine's logger
     * @param declaredFacts     The declared type of each fact, by name
     * @param allFactsDeclared  Whether a run may supply only the declared facts
     * @param reservedFactNames The language that reserves each fact name the engine's languages reserve, by name, as
     *                          {@link FactNames#reserved} returns them
     */
    FactIntake(Logger log, Map<String, Class<?>> declaredFacts, boolean allFactsDeclared,
               Map<String, String> reservedFactNames) {
        // FactNames and FactNames.Problem are initialized here, when the engine is built, so checking a run's first
        // fact name never loads them: the run may be nested deep in another run's stack (see StackHeadroom). A null
        // name is checked because it returns at once, with Problem.NULL.
        FactNames.check(null, Set.of());
        this.log = log;
        this.declaredFacts = declaredFacts;
        this.allFactsDeclared = allFactsDeclared;
        this.reservedFactNames = reservedFactNames;
        this.reserved = reservedFactNames.keySet();
        Map<String, Class<?>> primitives = new HashMap<>();
        declaredFacts.forEach((name, type) -> {
            // void is primitive, but nothing widens to it, and no value is a Void.
            if (type.isPrimitive() && type != void.class) {
                primitives.put(name, type);
            }
        });
        this.primitiveFacts = Map.copyOf(primitives);
    }

    /**
     * Collects the fact values a run was given, without checking their names, so the run's listeners can be given the
     * facts before the names are checked. A fact declared with a primitive type whose value is a boxed primitive that
     * Java widens to that type, such as an {@link Integer} for a {@code long}, is widened here, so listeners and
     * languages see only the declared type; its value in the store isn't changed. Any other value is kept as it is,
     * for {@link #checkDeclaredType(String, Object)} to judge. Reading the store and reading each fact are call-outs
     * of the run (see {@link LoggedFailures#callOut()}).
     *
     * @param facts The key/value fact store
     * @param runs  What is in progress on the run's thread, which marks each call-out
     * @return A map of fact names to their values, which may hold a {@code null} name a custom store allowed
     */
    Map<String, Object> factValues(FactStore<?> facts, LoggedFailures.Runs runs) {
        Map<String, Object> entryMap = new HashMap<>();
        runs.callOut();
        for (Map.Entry<String, ? extends FactReference<?>> entry : facts.asMap().entrySet()) {
            // A null reference is bound as null, like a Fact holding null. Skipping it left the name
            // unresolvable, so `x == null` failed instead of matching.
            FactReference<?> fact = entry.getValue();
            runs.callOut();
            entryMap.put(entry.getKey(), fact != null ? fact.getValue() : null);
        }
        if (primitiveFacts.isEmpty()) {
            return entryMap;
        }
        // A loop, not a lambda, so the engine's first run with such a fact bootstraps nothing (see StackHeadroom).
        for (Map.Entry<String, Class<?>> primitive : primitiveFacts.entrySet()) {
            // A null value, or a fact the run left out, stays as it is.
            Object value = entryMap.get(primitive.getKey());
            if (value != null) {
                entryMap.put(primitive.getKey(), Widening.widen(value, primitive.getValue()));
            }
        }
        return entryMap;
    }

    /**
     * Checks a run's facts as the engine itself does, before the run borrows a copy of the rules, and returns what the
     * check threw, for the run to fail with once its listeners have heard of it: every name is not {@code null}, not
     * blank and not one the engine's languages reserve, every value is an instance of the type its fact was declared
     * with or of its wrapper, and, when the engine requires declared facts, the run supplied every declared fact and
     * nothing else. The languages check the names later, with {@link #checkFactNames}.
     *
     * @param values The fact values by name
     * @return The {@link IllegalArgumentException} the check threw, or {@code null} if the facts passed
     */
    RuntimeException factRejection(Map<String, Object> values) {
        try {
            for (Map.Entry<String, Object> fact : values.entrySet()) {
                checkName(fact.getKey());
                checkDeclaredType(fact.getKey(), fact.getValue());
            }
            checkNothingWasLeftOut(values);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /**
     * Checks that a fact name is not {@code null}, not blank and not one the engine's languages reserve.
     *
     * @param name The fact's name
     * @throws IllegalArgumentException if it is
     */
    private void checkName(String name) {
        FactNames.Problem problem = FactNames.check(name, reserved);
        if (problem == null) {
            return;
        }
        // Not a switch: the class javac makes for one is loaded by a run's first rejected name (see StackHeadroom).
        if (problem == FactNames.Problem.NULL) {
            throw rejectedFact(FactNames.NULL_MESSAGE);
        }
        if (problem == FactNames.Problem.BLANK) {
            throw rejectedFact(FactNames.BLANK_MESSAGE);
        }
        // A language binds something of its own to this name, such as the output object, which would silently hide a
        // fact of the same name.
        throw rejectedFact(FactNames.reservedMessage(name, reservedFactNames.get(name), false));
    }

    /**
     * Checks every fact name of a run with the language of each loaded rule, once the engine's own checks have passed
     * (see {@link #factRejection}).
     *
     * @param values The fact values by name
     * @param checks The compilers of the rule list the run uses, which check each name, by language name
     * @param runs   What is in progress on the run's thread, which marks each check as a call-out
     * @throws IllegalArgumentException if a language can't refer to a fact's name, or its check of the name throws
     *                                  anything else
     */
    void checkFactNames(Map<String, Object> values, Map<String, ExpressionCompiler> checks, LoggedFailures.Runs runs) {
        for (String name : values.keySet()) {
            IllegalArgumentException rejected = factNameRejection(log, name, checks, true, runs);
            if (rejected != null) {
                throw rejected;
            }
        }
    }

    /**
     * Logs the failure of a run whose facts the engine rejects, and records it as logged, so the code around a nested
     * run that rejects its facts doesn't log it again (see {@link LoggedFailures}).
     *
     * @param msg Why the facts were rejected
     * @return The exception to throw
     */
    private IllegalArgumentException rejectedFact(String msg) {
        log.error(msg);
        return LoggedFailures.loggedByRun(new IllegalArgumentException(msg));
    }

    /**
     * Checks one fact's value against the type it was declared with, or the type's wrapper if it's primitive: a value
     * that Java widens to a primitive type was widened when the facts were collected. A {@code null} value passes:
     * nothing about it contradicts the declaration, and a language can't tell it from an absent fact either.
     *
     * @param name  The fact's name
     * @param value The fact's value, which may be {@code null}
     * @throws IllegalArgumentException if the fact was declared and its value isn't an instance of that type, or of its
     *                                  wrapper; for a primitive type and a number, a character or a boolean, the
     *                                  message says why the value wasn't widened
     */
    private void checkDeclaredType(String name, Object value) {
        Class<?> declared = declaredFacts.get(name);
        if (declared == null || value == null || Widening.wrap(declared).isInstance(value)) {
            return;
        }
        // Concatenated, not formatted: String.formatted() has the JDK initialize Formatter's classes, which a run
        // that rejects a fact would be the first to use (see RunClasses).
        String why = primitiveFacts.containsKey(name) && Widening.isPrimitiveLike(value)
                ? " (" + Widening.ONLY_WIDENED + ")" : "";
        throw rejectedFact("Fact '" + Failures.quote(name) + "' was declared as " + declared.getName()
                + ", but the run supplied a " + value.getClass().getName() + why);
    }

    /**
     * Checks that a run supplied every declared fact and nothing else, when the engine was built with
     * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#requireDeclaredFacts()}. Without it, a run may supply
     * whatever it likes, and a declared fact only says what its type is when it's there.
     *
     * @param values The fact values by name
     * @throws IllegalArgumentException if a declared fact is missing, or a fact nobody declared was supplied
     */
    private void checkNothingWasLeftOut(Map<String, Object> values) {
        if (!allFactsDeclared) {
            return;
        }
        for (String name : values.keySet()) {
            if (!declaredFacts.containsKey(name)) {
                throw rejectedFact("Fact '" + Failures.quote(name)
                        + "' wasn't declared, and this engine was built with requireDeclaredFacts()");
            }
        }
        for (String name : declaredFacts.keySet()) {
            if (!values.containsKey(name)) {
                throw rejectedFact("Fact '" + Failures.quote(name) + "' was declared, but the run didn't supply it, "
                        + "and this engine was built with requireDeclaredFacts()");
            }
        }
    }

    /**
     * Checks a fact name with the language of each rule in use. A language rejects a name with an
     * {@link IllegalArgumentException}, which is returned as is. Anything else a language throws, a {@link Throwable}
     * that is neither an exception nor an error too, is returned as an {@code IllegalArgumentException} naming the fact
     * and the language, except a fatal {@link Error}, thrown or among the causes of what the language throws, or
     * suppressed on them (see {@link Failures#fatalError}), a rejection included, which is logged and rethrown. A
     * failure of a {@code run()} or a {@code load()} the check started, and a fatal error that run logged, isn't logged
     * a second time, and a rejection this logs is recorded as logged, so the code around a nested run doesn't log it
     * again (see {@link LoggedFailures}). Each language's check is a call-out of the run, load or validation (see
     * {@link LoggedFailures#callOut()}).
     *
     * @param log    The engine's logger
     * @param name   The fact's name
     * @param checks The compilers to check it with, by language name
     * @param logged Whether to log the rejection at ERROR, escaped; {@code false} when the caller logs its own message
     * @param runs   What is in progress on this thread, which marks each check
     * @return The exception a language rejected the name with, or {@code null} if every language accepts it
     */
    static IllegalArgumentException factNameRejection(Logger log, String name, Map<String, ExpressionCompiler> checks,
                                                      boolean logged, LoggedFailures.Runs runs) {
        for (Map.Entry<String, ExpressionCompiler> check : checks.entrySet()) {
            IllegalArgumentException rejected = factNameRejection(log, name, check.getKey(), check.getValue(), logged,
                    runs);
            if (rejected != null) {
                return rejected;
            }
        }
        return null;
    }

    /**
     * Checks a fact name with one language, as
     * {@link #factNameRejection(Logger, String, Map, boolean, LoggedFailures.Runs)} describes.
     *
     * @param log      The engine's logger
     * @param name     The fact's name
     * @param language The language's name
     * @param compiler The compiler to check it with
     * @param logged   Whether to log the rejection at ERROR, escaped; {@code false} when the caller logs its own
     *                 message
     * @param runs     What is in progress on this thread, which marks the check
     * @return The exception the language rejected the name with, or {@code null} if it accepts it
     */
    private static IllegalArgumentException factNameRejection(Logger log, String name, String language,
                                                              ExpressionCompiler compiler, boolean logged,
                                                              LoggedFailures.Runs runs) {
        Throwable failure;
        try {
            runs.callOut();
            compiler.checkFactName(name);
            return null;
        } catch (IllegalArgumentException e) {
            if (Failures.fatalError(e) == null) {
                // An interrupt the language wrapped, or left suppressed, in its rejection is put back, as for any
                // other exception it throws.
                Failures.keepInterruptStatus(e);
                // The language wrote this message and it names the fact, so it's escaped before it's logged. The
                // exception is returned as it came, so a caller still reads exactly what the language said. A failed
                // run() or load() the check started has already logged its failure; the language's own instance is
                // what's recorded as logged here.
                if (logged && Failures.nestedRunFailure(e) == null) {
                    log.error(Failures.describe(e));
                    LoggedFailures.loggedByRun(e);
                }
                return e;
            }
            // A rejection that carries a fatal Error is handled like anything else that does.
            failure = e;
        } catch (Throwable e) {
            failure = e;
        }
        Failures.keepInterruptStatus(failure);
        String msg = "The '" + Failures.quote(language) + "' expression language failed to check fact name '"
                + Failures.quote(name) + "': " + Failures.describe(failure);
        Error fatal = Failures.fatalError(failure);
        boolean logs = (logged || fatal != null) && LoggedFailures.unlogged(failure);
        if (logs) {
            log.error(msg);
        }
        Failures.throwIfPresent(fatal);
        // The engine's words around what the language threw, which may be a nested run's failure (see LoggedFailures).
        IllegalArgumentException rejected = LoggedFailures.builtByEngine(new IllegalArgumentException(msg, failure));
        return logs ? LoggedFailures.loggedByRun(rejected) : rejected;
    }
}
