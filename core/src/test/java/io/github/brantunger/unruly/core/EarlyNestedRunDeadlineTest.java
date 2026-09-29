package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactReference;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run started while the outer run doesn't hold its copy of the rules, while it reads its facts, while a language
 * makes a session for its copy, or while a session is closed as the copy is given back, stops no later than the outer
 * run's deadline, as a run started from a rule or a listener does (#772).
 */
@DisplayName("a run started while another run reads its facts, or gets or gives back its copy, shares that run's"
        + " deadline")
class EarlyNestedRunDeadlineTest {

    /** Far enough off that no run in these tests gets near it. */
    private static final Duration OUTER_TIMEOUT = Duration.ofMinutes(5);

    /** The deadline the inner engine's one rule saw, or empty if its run had none. */
    private final AtomicReference<Instant> innerDeadline = new AtomicReference<>();

    /** The deadline the outer engine's one rule saw. */
    private final AtomicReference<Instant> outerDeadline = new AtomicReference<>();

    /** An engine with no timeout of its own, whose one rule records the deadline its run has. */
    private RulesEngine<Map<String, Object>> innerEngine() {
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new StubExpressionLanguage().action((action, session) -> {
                    innerDeadline.set(action.deadline());
                    return ActionResult.done();
                })).build();
        inner.load(List.of(Rule.builder().ruleName("inner").condition("c").action("a").build()));
        return inner;
    }

    /**
     * An engine with {@link #OUTER_TIMEOUT}, which makes no copy at load, so a run makes the one it uses, and whose one
     * rule records the deadline its run has.
     */
    private RulesEngine<Map<String, Object>> outerEngine(StubExpressionLanguage language) {
        RulesEngine<Map<String, Object>> outer = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language.action((action, session) -> {
                    outerDeadline.set(action.deadline());
                    return ActionResult.done();
                })).runTimeout(OUTER_TIMEOUT).copiesAtLoad(0).build();
        outer.load(List.of(Rule.builder().ruleName("outer").condition("c").action("a").build()));
        return outer;
    }

    @Test
    @DisplayName("a run a language starts from newSession() stops at the outer run's deadline")
    void aRunStartedFromNewSessionSharesTheDeadline() {
        try (RulesEngine<Map<String, Object>> inner = innerEngine();
             RulesEngine<Map<String, Object>> outer = outerEngine(new StubExpressionLanguage().newSession(() -> {
                 inner.run(new FactMap<>());
                 return new Session() {
                 };
             }))) {

            outer.run(new FactMap<>());

            assertNotNull(outerDeadline.get(), "the outer run had no deadline");
            assertEquals(outerDeadline.get(), innerDeadline.get(), "the deadline of the run newSession() started");
        }
    }

    @Test
    @DisplayName("a run a session's close() starts while the outer run gives its copy back stops at the outer run's"
            + " deadline")
    void aRunStartedFromCloseSharesTheDeadline() {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<RulesEngine<Map<String, Object>>> self = new AtomicReference<>();
        List<Rule> rules = List.of(Rule.builder().ruleName("outer").condition("c").action("a").build());
        try (RulesEngine<Map<String, Object>> inner = innerEngine();
             RulesEngine<Map<String, Object>> outer = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                     .language(new StubExpressionLanguage().newSession(() -> new Session() {
                         @Override
                         public void close() {
                             // Only the first: the run's own session, closed as the run gives its copy back.
                             if (closed.compareAndSet(false, true)) {
                                 inner.run(new FactMap<>());
                             }
                         }
                     }).action((action, session) -> {
                         outerDeadline.set(action.deadline());
                         // Retires the rules the run holds a copy of, so giving the copy back closes its session.
                         self.get().load(rules);
                         return ActionResult.done();
                     })).runTimeout(OUTER_TIMEOUT).copiesAtLoad(0).build()) {
            self.set(outer);
            outer.load(rules);

            outer.run(new FactMap<>());

            assertTrue(closed.get(), "the run's session was never closed");
            assertNotNull(outerDeadline.get(), "the outer run had no deadline");
            assertEquals(outerDeadline.get(), innerDeadline.get(), "the deadline of the run close() started");
        }
    }

    @Test
    @DisplayName("a run a fact's getValue() starts stops at the outer run's deadline")
    void aRunStartedFromGetValueSharesTheDeadline() {
        try (RulesEngine<Map<String, Object>> inner = innerEngine();
             RulesEngine<Map<String, Object>> outer = outerEngine(new StubExpressionLanguage())) {
            FactMap<Object> facts = new FactMap<>();
            facts.put(new FactReference<>() {
                @Override
                public String getName() {
                    return "x";
                }

                @Override
                public Object getValue() {
                    return inner.run(new FactMap<>());
                }
            });

            outer.run(facts);

            assertNotNull(outerDeadline.get(), "the outer run had no deadline");
            assertEquals(outerDeadline.get(), innerDeadline.get(), "the deadline of the run getValue() started");
        }
    }
}
