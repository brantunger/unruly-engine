package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What the tests of runs that wait for a copy of the rules share: a fact that holds the run reading it until the test
 * lets it go, and a language whose runs each need a copy of their own.
 */
final class GatedRuns {

    /**
     * The toy language, with a session of its own: a language whose sessions are {@link Session#none()} needs no copy
     * of the rules per run, so only a language with state has a copy limit to keep.
     */
    static final ExpressionLanguage STATEFUL_TOY = new ExpressionLanguage() {

        @Override
        public String name() {
            return ToyExpressionLanguage.LANGUAGE_NAME;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            ExpressionCompiler toy = new ToyExpressionLanguage().newCompiler(context);
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return toy.compileCondition(expression);
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return toy.compileAction(expression);
                }

                @Override
                public Session newSession() {
                    return new Session() {
                    };
                }
            };
        }
    };

    private GatedRuns() {
    }

    /** A fact whose method holds the run that calls it until the test lets it go. */
    public static final class Gate {

        private final CountDownLatch holding = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * Waits until the test releases the gate. It is a getter, so a rule reads it as the property
         * {@code gate.hold}.
         *
         * @return {@code true}, so a condition that reads it matches
         * @throws InterruptedException if the thread is interrupted while it waits
         */
        public boolean getHold() throws InterruptedException {
            holding.countDown();
            return release.await(30, TimeUnit.SECONDS);
        }

        /**
         * Waits until a run is held at the gate.
         *
         * @param timeout How long to wait
         * @param unit    The unit of {@code timeout}
         * @return Whether a run reached the gate in time
         * @throws InterruptedException if the thread is interrupted while it waits
         */
        boolean awaitHolding(long timeout, TimeUnit unit) throws InterruptedException {
            return holding.await(timeout, unit);
        }

        /** Lets every run held at the gate, and every run that reaches it from now on, go. */
        void release() {
            release.countDown();
        }
    }
}
