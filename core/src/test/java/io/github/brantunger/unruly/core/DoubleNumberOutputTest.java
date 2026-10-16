package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reproduction of #1127: a language whose numbers are all doubles, as JavaScript's are, computes {@code 1 + 2} as
 * the {@link Double} 3.0, and its action returns it for {@code count}, which the output's setter takes as an
 * {@code int}, a {@code long} or an {@link Integer}. The engine sets it once the language converts it for the setter.
 * Written against the API before the conversion hook, so that it compiles on a release without it, where each test
 * fails with the default writer's refusal to narrow a {@code Double}.
 */
@DisplayName("#1127 a language whose numbers are doubles sets an int, a long or an Integer property")
class DoubleNumberOutputTest {

    /** An order whose count is an int. */
    public static final class IntOrder {
        private int count;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    /** An order whose count is a long. */
    public static final class LongOrder {
        private long count;

        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }
    }

    /** An order whose count is an Integer. */
    public static final class BoxedOrder {
        private Integer count;

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }
    }

    /**
     * A language whose conditions are always true and whose every action computes {@code count = 1 + 2} as a
     * JavaScript engine would: a double. It converts a whole double for a setter taking an {@code int}, a {@code long}
     * or an {@link Integer}, in {@code convertOutput}. The method has no {@code @Override}, so that this class
     * compiles against a release without the hook (as #848's tests compile against the kit before theirs), where it
     * is a method of the compiler's own that nothing calls, and the tests fail as the issue reports.
     */
    private static ExpressionLanguage doubles() {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "js";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (action, session) -> ActionResult.set(Map.of("count", 1.0 + 2.0));
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }

                    public Object convertOutput(Object value, Class<?> target) {
                        if (value instanceof Double number && number == Math.rint(number)
                                && (target == int.class || target == Integer.class)) {
                            return number.intValue();
                        }
                        if (value instanceof Double number && number == Math.rint(number) && target == long.class) {
                            return number.longValue();
                        }
                        return value;
                    }
                };
            }
        };
    }

    private static <O> O countOf(Supplier<O> output) {
        try (RulesEngine<O> engine = RulesEngineBuilder.firstMatch(output).language(doubles()).build()) {
            engine.load(List.of(Rule.builder().ruleName("count").condition("true").action("count = 1 + 2")
                    .language("js").build()));
            return engine.run(new FactMap<>());
        }
    }

    @Test
    @DisplayName("count = 1 + 2, a Double, sets setCount(int)")
    void doubleSetsAnInt() {
        assertEquals(3, countOf(IntOrder::new).getCount());
    }

    @Test
    @DisplayName("count = 1 + 2, a Double, sets setCount(long)")
    void doubleSetsALong() {
        assertEquals(3L, countOf(LongOrder::new).getCount());
    }

    @Test
    @DisplayName("count = 1 + 2, a Double, sets setCount(Integer)")
    void doubleSetsAnInteger() {
        assertEquals(3, countOf(BoxedOrder::new).getCount());
    }
}
