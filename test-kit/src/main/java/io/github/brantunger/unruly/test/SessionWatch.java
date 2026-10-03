package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ConditionResult;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.test.KitResources.ResourceCheck;
import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.brantunger.unruly.test.KitFailures.describe;
import static io.github.brantunger.unruly.test.KitFailures.suppressAll;

/**
 * Watches the sessions a language returns: whether it returns one instance twice, whether closing one throws,
 * how many have been closed so far ({@code closed()}), and whether the language returned any session of its own,
 * rather than {@link Session#none()} ({@code anyReturned()}). Each session the engine gets is wrapped, so its
 * close can be seen, and the language is handed back its own session, unwrapped, wherever the engine passes one.
 * {@code closing()} runs a check with an engine built with {@link #watching}, and attaches what closing the
 * sessions threw to the check's failure.
 */
final class SessionWatch {

    /** Every session the language returned, by identity: two sessions that are merely equal are still two. */
    private final Set<Session> returned = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    private final List<Session> shared = new CopyOnWriteArrayList<>();
    private final List<Throwable> closeFailures = new CopyOnWriteArrayList<>();
    private final AtomicInteger closes = new AtomicInteger();

    /**
     * Runs a check with an engine built with {@link #watching}, and closes it, however the check ends, as
     * {@link KitResources#closing} does.
     */
    void closing(RulesEngine<Map<String, Object>> engine, ResourceCheck<RulesEngine<Map<String, Object>>> check)
            throws Exception {
        try {
            KitResources.closing(engine, check);
        } catch (Throwable e) {
            // The engine is closed by now. The check's failure stays the one reported, with what closing the
            // sessions threw attached to it.
            suppressAll(e, closeFailures);
            throw e;
        }
    }

    /** Whether the language returned a session of its own, rather than {@link Session#none()}. */
    boolean anyReturned() {
        return !returned.isEmpty();
    }

    /** How many times a session's close() has been called so far, whether it threw or not. */
    int closed() {
        return closes.get();
    }

    void assertNoneShared() {
        assertNoneShared(null);
    }

    /**
     * Fails if the language returned one session twice, with {@code cause}, what went wrong in the check because
     * of it, as the failure's cause.
     */
    void assertNoneShared(@Nullable Throwable cause) {
        if (!shared.isEmpty()) {
            throw new AssertionFailedError("newSession() returned the same session for two copies of the rules, so"
                    + " two runs use it at once and the engine closes it twice: " + describe(shared.get(0)), cause);
        }
    }

    void assertNoneThrewOnClose() {
        if (!closeFailures.isEmpty()) {
            throw new AssertionFailedError("a session's close() threw " + describe(closeFailures.get(0))
                    + ", which the engine only logs at WARN");
        }
    }

    void assertNotASession(@Nullable Object detail) {
        if (returned.contains(detail)) {
            throw new AssertionFailedError("the condition's detail is the session it ran with, which the engine gives"
                    + " to another run or closes: " + describe(detail));
        }
    }

    // Session.none() is one shared instance, and the engine asks whether a language's session is it by identity.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private @Nullable Session watch(@Nullable Session session) {
        // Passed through as it is: a wrapped Session.none() would make the engine keep a copy of the rules for
        // each run, where it shares one, and change what's being checked. A null is passed through too, so the
        // engine still rejects it with its own message.
        if (session == null || session == Session.none()) {
            return session;
        }
        // Recorded, not failed here: an exception from newSession() would be the engine's failure to create a
        // session, reported by load() here, not a message about the language's session being shared.
        if (!returned.add(session)) {
            shared.add(session);
        }
        return new Watched(session);
    }

    /** The language's own session, which is what its expressions and warmUp() expect. */
    private static Session unwrap(Session session) {
        return session instanceof Watched watched ? watched.session : session;
    }

    // The wrapper holds the language's compiler, and closes it when the engine closes the wrapper.
    @SuppressWarnings("PMD.CloseResource")
    ExpressionLanguage watching(ExpressionLanguage language) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return language.name();
            }

            // Forwarded, so the language initializes its classes when the engine is built, as it does unwrapped.
            @Override
            public void prepare() {
                language.prepare();
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        CompiledCondition condition = compiler.compileCondition(expression);
                        // Not a lambda: that would implement only evaluate(), and drop the language's detail.
                        return new CompiledCondition() {
                            @Override
                            public @Nullable Object evaluate(EvaluationContext evaluation, Session session)
                                    throws Exception {
                                return condition.evaluate(evaluation, unwrap(session));
                            }

                            @Override
                            public ConditionResult evaluateWithDetail(EvaluationContext evaluation,
                                                                      Session session) throws Exception {
                                return condition.evaluateWithDetail(evaluation, unwrap(session));
                            }
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        return (actionContext, session) -> action.execute(actionContext, unwrap(session));
                    }

                    @Override
                    public Session newSession() {
                        return watch(compiler.newSession());
                    }

                    @Override
                    public void warmUp(Session session) throws Exception {
                        compiler.warmUp(unwrap(session));
                    }

                    @Override
                    public void checkFactName(String name) {
                        compiler.checkFactName(name);
                    }

                    @Override
                    public void close() {
                        compiler.close();
                    }
                };
            }
        };
    }

    /** A language's session, whose close() records what it throws before throwing it on. */
    private final class Watched implements Session {

        private final Session session;

        Watched(Session session) {
            this.session = session;
        }

        // Anything it throws, a checked exception thrown sneakily included: the engine logs any Exception or Error at
        // WARN, and rethrows the fatal error one is or carries, which fails the check by itself.
        @Override
        public void close() {
            closes.incrementAndGet();
            try {
                session.close();
            } catch (Throwable e) {
                if (!KitFailures.isFatal(e)) {
                    closeFailures.add(e);
                }
                throw e;
            }
        }
    }
}
