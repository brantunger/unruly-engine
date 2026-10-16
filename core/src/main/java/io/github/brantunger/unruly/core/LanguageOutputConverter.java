package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputConverter;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import org.jspecify.annotations.Nullable;

/**
 * The {@link OutputConverter} of a rule: {@link ExpressionCompiler#convertOutput} of the compiler that compiled it,
 * which is never asked about {@code null}. Built when the rule compiles, so a run allocates nothing for it. A class,
 * not a lambda: a lambda is linked where its call site first runs, which can be deep in a run's stack (#1097). Compared
 * by identity, as {@link CompiledRule}'s equals does, which nothing in the engine calls.
 */
final class LanguageOutputConverter implements OutputConverter {

    private final ExpressionCompiler compiler;

    LanguageOutputConverter(ExpressionCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public @Nullable Object convert(@Nullable Object value, Class<?> target) {
        return value == null ? null : compiler.convertOutput(value, target);
    }
}
