package io.github.brantunger.unruly.api;

import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The default {@link OutputWriter}: {@code put} on a {@link Map}, and otherwise the output class's public setter whose
 * parameter accepts the value. Values aren't converted. Each class's setters are looked up once.
 */
final class BeansAndMapsWriter implements OutputWriter<Object> {

    /** The one instance, which keeps no state of its own. */
    static final BeansAndMapsWriter INSTANCE = new BeansAndMapsWriter();

    // The public instance methods with one parameter whose names start with "set", by name, for each output class.
    // Overloads are in the order of their parameter type's name, so the choice between them doesn't vary between runs.
    private static final ClassValue<Map<String, List<Method>>> SETTERS = new ClassValue<>() {
        @Override
        protected Map<String, List<Method>> computeValue(Class<?> type) {
            return Arrays.stream(type.getMethods())
                    .filter(method -> method.getParameterCount() == 1 && method.getName().startsWith("set")
                            && !Modifier.isStatic(method.getModifiers()))
                    .sorted(Comparator.comparing((Method method) -> method.getParameterTypes()[0].getName()))
                    .collect(Collectors.groupingBy(Method::getName, Collectors.toUnmodifiableList()));
        }
    };

    private BeansAndMapsWriter() {
    }

    /**
     * Sets one property on the output object.
     *
     * @param output   The output object
     * @param property The property's name, not empty
     * @param value    The value, possibly {@code null}
     * @throws IllegalArgumentException     if the output isn't a map and has no public setter for the property that
     *                                      accepts the value
     * @throws ReflectiveOperationException if the setter can't be called, or throws, which it reports as the cause of
     *                                      an {@link java.lang.reflect.InvocationTargetException}
     */
    @SuppressWarnings("unchecked")
    @Override
    public void set(Object output, String property, @Nullable Object value) throws ReflectiveOperationException {
        if (output instanceof Map<?, ?> map) {
            ((Map<String, @Nullable Object>) map).put(property, value);
            return;
        }
        String name = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Method setter : SETTERS.get(output.getClass()).getOrDefault(name, List.of())) {
            if (accepts(setter.getParameterTypes()[0], value)) {
                setter.invoke(output, value);
                return;
            }
        }
        throw new IllegalArgumentException(output.getClass().getName() + " has no public method " + name
                + " that accepts " + (value == null ? "null" : "a " + value.getClass().getName()));
    }

    private static boolean accepts(Class<?> parameter, @Nullable Object value) {
        if (value == null) {
            return !parameter.isPrimitive();
        }
        return MethodType.methodType(parameter).wrap().returnType().isInstance(value);
    }
}
