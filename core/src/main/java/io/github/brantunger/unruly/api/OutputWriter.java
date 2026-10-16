package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

/**
 * Sets a property on the output object. The engine uses it for the properties an action returns with
 * {@link io.github.brantunger.unruly.api.language.ActionResult#set(java.util.Map)}, as a language whose expressions
 * have no side effects does. Give an engine one with {@link RulesEngineBuilder#outputWriter(OutputWriter)}; the default
 * is {@link #beansAndMaps()}.
 *
 * <p>
 * <b>Implemented by</b> applications, to write to an output object the default writer doesn't suit. A method added to
 * this interface is a {@code default} method, so an existing writer keeps compiling and working.
 * </p>
 *
 * <p>
 * The engine calls {@link #set(Object, String, Object, OutputConverter)} on the thread running the rule, once for each
 * property, in the order the action returned them, with an {@link OutputConverter} that converts a value as the rule's
 * language understands it. By default that calls {@link #set(Object, String, Object)} with the value as the action
 * returned it, so a writer written as a lambda, or one that doesn't override it, never converts; one that does override
 * it may ask the converter for a value of the type it needs. What it throws is treated as what an action throws. It
 * fails the rule with a
 * {@link io.github.brantunger.unruly.api.exception.RuleExecutionException} naming the rule and the property, which
 * listeners receive in {@code onError}; a fatal {@link Error}, such as an {@link OutOfMemoryError}, is rethrown
 * unchanged once they have. But when the thread was interrupted, or the run passed its deadline, by the time it threw,
 * the run stops, as a cancelled run does, with an exception whose {@code getRuleName()} is {@code null}, which
 * keeps what it threw as a suppressed exception; unless that is or wraps an {@link Error}, which fails the rule all
 * the same. A run that was interrupted, or passed its deadline, while the writer set the properties without throwing
 * stops once every one is set, and they stay set. It must be thread-safe, because runs on many threads share it.
 * </p>
 *
 * @param <O> The type of the output objects it writes to
 */
@FunctionalInterface
public interface OutputWriter<O> {

    /**
     * Sets one property on the output object.
     *
     * @param output   The output object
     * @param property The property's name, not empty
     * @param value    The value, possibly {@code null}
     * @throws Exception if the property can't be set
     */
    void set(O output, String property, @Nullable Object value) throws Exception;

    /**
     * Sets one property on the output object, given a converter for the value from the rule's expression language. By
     * default, calls {@link #set(Object, String, Object)} with the value as it is, so a writer that doesn't override
     * this method sees the value as the action returned it, and converts nothing. A writer that needs a value of
     * another type overrides it and asks the converter: {@link OutputConverter#convert converter.convert(value, type)}
     * returns a value the type accepts, as the language understands the value, or the value unchanged where the
     * language has no conversion for the type, and throws an {@link IllegalArgumentException} where the language
     * refuses to convert it. The engine calls this method, never the other one directly.
     *
     * @param output    The output object
     * @param property  The property's name, not empty
     * @param value     The value, possibly {@code null}
     * @param converter Converts the value to the type of a setter, as the rule's language understands the value
     * @throws Exception if the property can't be set
     */
    default void set(O output, String property, @Nullable Object value, OutputConverter converter) throws Exception {
        set(output, property, value);
    }

    /**
     * Returns the default writer. It sets a property with {@code put} on a {@link java.util.Map} output, and otherwise
     * with the output's public setter whose parameter accepts the value, such as {@code setInterestRate} for
     * {@code interestRate}. A boxed primitive, only when no setter takes it as it is, goes to a setter taking a
     * primitive that Java widens it to, so an {@link Integer} reaches a setter taking a {@code long} or a
     * {@code double}. No value is otherwise converted by the writer itself: never narrowed, so a setter taking an
     * {@code int} doesn't accept a {@link Long}, and never from one wrapper to another, so a setter taking a
     * {@link Long} doesn't accept an {@link Integer}. A varargs setter, such as {@code setTags(String...)}, takes an
     * array: a single value isn't wrapped in one. An output without such a setter fails with
     * {@link IllegalArgumentException}, which, for a number, a character or a boolean, names the setters of the
     * property that take a primitive or a wrapper. Of overloaded setters, it calls the most specific one that accepts
     * the value, as Java would; when no single one is the most specific, the choice is fixed for the output class and
     * the same on every run. A {@code null} output or property name fails with {@link NullPointerException}, and an
     * empty property name with {@link IllegalArgumentException}, for a map output as for any other.
     *
     * <p>
     * Only when no setter takes a value that isn't {@code null} as it is, the writer asks the rule's language to
     * convert it, through the {@link OutputConverter} the engine gives
     * {@link #set(Object, String, Object, OutputConverter)}: for each type a setter of the property takes, the most
     * specific setter first, until one takes what the language returns, as it is or widened. So a language whose
     * numbers are doubles sets a setter taking an {@code int} with the {@link Double} 3.0, if it converts whole doubles
     * (see
     * {@link io.github.brantunger.unruly.api.language.ExpressionCompiler#convertOutput
     * ExpressionCompiler.convertOutput}). A conversion the language refuses, with an {@link IllegalArgumentException},
     * doesn't stop the writer trying the property's other setters; when none takes the value, the write fails with the
     * first refusal, or, if the language refused none, with the failure described above. Anything else the language
     * throws fails the write at once. A language without a conversion changes nothing: the value is set only as it is,
     * and the write fails as above.
     * </p>
     *
     * <p>
     * A setter declared with a type variable of a class, such as {@code setContent(T)}, takes what the output class
     * makes of the variable where the class has another setter of the property with a different parameter: with
     * {@code T} a {@link Long}, beside {@code setContent(long)}, a {@link Short} goes to {@code setContent(long)}, as
     * in Java, and text fails.
     * </p>
     *
     * <p>
     * Where it differs from Java: a setter declared with a type variable, with no other setter of the property, takes
     * whatever its erased parameter does; a setter whose parameter is a supertype of what the variable is given, such
     * as {@code setContent(CharSequence)} beside {@code setContent(T)} with {@code T} a {@link String}, is still tried
     * first, though Java would call {@code setContent(T)}; an instance of a generic class, such as
     * {@code new Box<Long>()}, gives the variable nothing, as its type argument isn't kept at run time, so the setter
     * takes the variable's bound; a type variable an inner class uses from its enclosing class, as in
     * {@code Outer<T>.Inner}, isn't resolved, so a setter declared with it, or with a variable it's passed to, takes
     * the variable's bound; and a varargs setter takes only an array.
     * </p>
     *
     * <p>
     * The setter is reached the way {@link io.github.brantunger.unruly.api.language.FactProperties} reaches a getter:
     * through a public, exported type that declares it, such as an interface the output class implements, or directly
     * where the class's package is open to {@code io.github.brantunger.unruly.core}, which every package on the class
     * path is. A setter it can't reach fails with {@link IllegalStateException}, saying what to export or open, rather
     * than a less specific overload being called in its place. One limit: where a class the writer can't reach
     * overrides a generic setter beside another public instance overload of the same name with a narrower parameter,
     * its own or inherited, even from a class that isn't public, a value for that overload reaches the generic setter
     * instead. It fails with an {@link java.lang.reflect.InvocationTargetException} caused by a
     * {@link ClassCastException}, or, where the overload's parameter is narrower than the generic setter's, the less
     * specific generic setter is called.
     * </p>
     *
     * @param <O> The type of the output objects
     * @return The writer
     */
    @SuppressWarnings("unchecked")
    static <O> OutputWriter<O> beansAndMaps() {
        return (OutputWriter<O>) BeansAndMapsWriter.INSTANCE;
    }
}
