package io.github.brantunger.unruly.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the engine's Flight Recorder events, {@link RunEvent} and {@link RuleEvent}, can be used where it runs.
 *
 * <p>
 * Loading an event class runs Flight Recorder's registration of it. That fails where Flight Recorder isn't there: in a
 * GraalVM native image built without {@code --enable-monitoring=jfr}, and in a runtime image without the
 * {@code jdk.jfr} module that runs the engine from the class path. There the engine records no events, rather than
 * failing its first run.
 * </p>
 */
final class FlightRecorderEvents {

    private static final Logger log = LoggerFactory.getLogger(AbstractRulesEngine.LOGGER_NAME);

    /** Whether both event classes loaded. Checked once, when the engine first starts a run or a rule. */
    static final boolean USABLE = loads(new LoadEvents());

    private FlightRecorderEvents() {
    }

    /**
     * Starts a run's event, if the events can be used and a recording has it enabled.
     *
     * @return The started event, or {@code null}
     */
    static RunEvent startRun() {
        return startRun(USABLE);
    }

    /**
     * Starts a run's event only if {@code usable}. A plain call, not a method reference: linking a method reference
     * loads its class, and {@link RunEvent} extends {@code jdk.jfr.Event}, which may not be there.
     *
     * @param usable Whether the events can be used
     * @return The started event, or {@code null}
     */
    static RunEvent startRun(boolean usable) {
        return usable ? RunEvent.startIfEnabled() : null;
    }

    /**
     * Starts a condition's or an action's event, if the events can be used and a recording has it enabled.
     *
     * @return The started event, or {@code null}
     */
    static RuleEvent startRule() {
        return startRule(USABLE);
    }

    /**
     * Starts a condition's or an action's event only if {@code usable}, with a plain call for the reason
     * {@link #startRun(boolean)} gives.
     *
     * @param usable Whether the events can be used
     * @return The started event, or {@code null}
     */
    static RuleEvent startRule(boolean usable) {
        return usable ? RuleEvent.startIfEnabled() : null;
    }

    /**
     * Runs {@code load}, which loads event classes, and reports whether they loaded.
     *
     * @param load Loads the event classes
     * @return {@code false} if loading them failed with a {@link LinkageError}, which is logged at DEBUG
     */
    static boolean loads(Runnable load) {
        try {
            load.run();
            return true;
        } catch (LinkageError e) {
            log.debug("The engine records no Flight Recorder events here, because they can't be loaded: {}",
                    e.toString(), e);
            return false;
        }
    }

    /** Loads both event classes. */
    private static final class LoadEvents implements Runnable {
        @Override
        public void run() {
            RunEvent.load();
            RuleEvent.load();
        }
    }
}
