package io.github.brantunger.unruly.core;

/**
 * A context of one run, which gives the run's state. Both of the engine's context records implement it, and the
 * context interfaces are sealed to them, so a cast of any evaluation or action context to it is total: the statics of
 * {@link EngineEvaluationContext} that the interfaces' defaults call reach the run through one cast, not a dispatch on
 * the context's class.
 */
// Not a functional interface: only the two records implement it, and core links no lambda on the run's path.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
interface BoundToRun {

    /**
     * Returns the state of the run the context belongs to.
     *
     * @return The run's state
     */
    RunState run();
}
