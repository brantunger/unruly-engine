package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.OutputConverter;
import io.github.brantunger.unruly.api.OutputWriter;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Sets the properties an action returns on a bean, with the default writer, instead of on the engine's map output, for
 * the contract kit's {@code outputNumbersConverted}, {@code outputNumbersConvertedConcurrently},
 * {@code outputUnconvertibleValueFailsTheRule} and {@code outputLongsNotNarrowed}: the check's engine keeps the map
 * output every other check has, so
 * {@link ExpressionLanguageContractTest#configure} applies to it as to any other, and the bean's setters take what the
 * default writer, given the language's {@link OutputConverter}, makes of the values. An engine that calls the
 * three-argument {@code set}, one released before the converter, writes the bean without converting.
 */
final class BeanWriter implements OutputWriter<Map<String, Object>> {

    private final Supplier<?> bean;

    // Volatile, so that the check sees a set the engine made on another thread.
    private volatile boolean setCalled;

    /**
     * Creates the writer.
     *
     * @param bean The bean whose setters every property is set with
     */
    BeanWriter(Object bean) {
        this(() -> bean);
    }

    /**
     * Creates a writer that sets each property on the bean {@code bean} supplies when the engine sets it, as
     * {@code outputNumbersConvertedConcurrently} gives each thread a bean of its own by supplying it from a
     * {@link ThreadLocal}.
     *
     * @param bean Supplies the bean whose setters the property is set with, on the thread that sets it
     */
    BeanWriter(Supplier<?> bean) {
        this.bean = bean;
    }

    /**
     * Returns whether the engine asked the writer to set a property, which it does only for a property an action
     * returned: an action that changes the output in place never asks.
     *
     * @return {@code true} once either {@code set} was called
     */
    boolean asked() {
        return setCalled;
    }

    @Override
    public void set(Map<String, Object> output, String property, @Nullable Object value) throws Exception {
        setCalled = true;
        OutputWriter.beansAndMaps().set(bean.get(), property, value);
    }

    @Override
    public void set(Map<String, Object> output, String property, @Nullable Object value, OutputConverter converter)
            throws Exception {
        setCalled = true;
        OutputWriter.beansAndMaps().set(bean.get(), property, value, converter);
    }
}
