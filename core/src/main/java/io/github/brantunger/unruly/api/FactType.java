package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.CompileContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * A fact's type written with its type arguments, such as {@code List<String>}, which a class literal can't say. Create
 * one as an anonymous subclass, which is what keeps the type argument for the engine to read, and declare the fact
 * with it:
 *
 * <pre>{@code
 * builder.fact("tags", new FactType<List<String>>() { })
 * }</pre>
 *
 * <p>
 * It is a {@link Type}, so {@link RulesEngineBuilder#fact(String, Type)} and {@link RulesEngineBuilder#facts} take it
 * as they take a {@link Class} or a {@link ParameterizedType} from another library. The builder keeps the type it
 * stands for, {@link #type()}, not the token, so a language is told {@code java.util.List<java.lang.String>} as a
 * {@code ParameterizedType}, as {@link CompileContext#declaredFacts() CompileContext.declaredFacts()} describes. A
 * language reads the class of a declared type, whichever kind it is, with {@link CompileContext#rawClass(Type)}.
 * </p>
 *
 * <p>
 * Two tokens are equal when the types they stand for are, and a token is never equal to that type itself, a
 * {@code Class} or a {@code ParameterizedType}, which knows nothing of tokens. It holds no more than the type, so it
 * is immutable and safe to share between threads.
 * </p>
 *
 * @param <T> The fact's type: a class, or a parameterized type such as {@code List<String>} or
 *            {@code Map<String, Applicant>}. Its own type arguments can be anything Java allows there, wildcards
 *            included
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/fact-types.md">Fact types</a>
 */
public abstract class FactType<T> implements Type {

    // What T is: a Class, or a ParameterizedType whose raw type is a Class.
    private final Type argument;

    /**
     * Reads the type argument the subclass was written with.
     *
     * @throws IllegalArgumentException if the subclass doesn't extend {@code FactType} directly with a type argument,
     *                                  as a raw {@code new FactType() { }} doesn't; or if the type argument is a type
     *                                  variable, such as the {@code T} of a generic method, or an array of a
     *                                  parameterized type or of a type variable, such as {@code List<String>[]},
     *                                  neither of which a fact can be declared with, as
     *                                  {@link CompileContext#rawClass(Type)} says
     */
    protected FactType() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterized) || parameterized.getRawType() != FactType.class) {
            throw new IllegalArgumentException("A FactType must be created as a direct subclass with its type argument,"
                    + " such as new FactType<List<String>>() { }, not as " + superclass.getTypeName());
        }
        Type written = parameterized.getActualTypeArguments()[0];
        // Rejects a type no fact can be declared with, naming it.
        CompileContext.rawClass(written);
        this.argument = written;
    }

    /**
     * Returns the type this token stands for, which a language is told the fact was declared with.
     *
     * @return A {@link Class}, or a {@link ParameterizedType} whose raw type is a {@code Class}
     */
    public final Type type() {
        return argument;
    }

    /**
     * Returns the name of the type this token stands for, such as {@code java.util.List<java.lang.String>}.
     *
     * @return The type's name, as its own {@link Type#getTypeName()} gives it
     */
    @Override
    public final String getTypeName() {
        return argument.getTypeName();
    }

    /**
     * Returns whether {@code other} is a token that stands for an equal type.
     *
     * @param other Any object, or {@code null}
     * @return {@code true} if {@code other} is a {@code FactType} whose {@link #type()} equals this one's
     */
    @Override
    public final boolean equals(@Nullable Object other) {
        return this == other || other instanceof FactType<?> that && argument.equals(that.argument);
    }

    @Override
    public final int hashCode() {
        return argument.hashCode();
    }

    /**
     * Returns the name of the type this token stands for, as {@link #getTypeName()} does.
     */
    @Override
    public final String toString() {
        return argument.getTypeName();
    }
}
