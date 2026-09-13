package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.EvaluationContext;

import java.util.Map;

/**
 * What one condition is evaluated against.
 *
 * @param facts The run's facts, read-only
 */
record EngineEvaluationContext(Map<String, Object> facts) implements EvaluationContext {
}
