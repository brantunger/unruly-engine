package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ActionContext;

import java.util.Map;

/**
 * What one action runs against.
 *
 * @param facts  The run's facts, read-only
 * @param output The output object the action changes
 */
record EngineActionContext(Map<String, Object> facts, Object output) implements ActionContext {
}
