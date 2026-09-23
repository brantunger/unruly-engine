package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.core.Accessors;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The default {@link OutputWriter}: {@code put} on a {@link Map}, and otherwise the output class's public setter that
 * accepts the value. Of overloaded setters, it calls the most specific one that accepts the value and that it can
 * reach; when no single one is the most specific, the choice is fixed for the output class and the same on every run.
 * Values aren't converted. Each class's setters are looked up once.
 *
 * <p>
 * A setter is called the way {@link io.github.brantunger.unruly.api.language.FactProperties} calls a getter: through a
 * public, exported type that declares it, such as an interface the output class implements, or otherwise directly
 * where the class's package is open to the engine's module, which every package on the class path is. So an output
 * class the engine can read, it can also write.
 * </p>
 */
final class BeansAndMapsWriter implements OutputWriter<Object> {

    /** The one instance, which keeps no state of its own. */
    static final BeansAndMapsWriter INSTANCE = new BeansAndMapsWriter();

    // The public instance methods with one parameter whose names start with "set", by name, for each output class,
    // each resolved to a method the engine can call. Overloads are in the order mostSpecificFirst gives them, so the
    // first that accepts a value is a most specific one, as Java would pick, and the choice doesn't vary between runs.
    private static final ClassValue<Map<String, List<Method>>> SETTERS = new ClassValue<>() {
        @Override
        protected Map<String, List<Method>> computeValue(Class<?> type) {
            return Arrays.stream(type.getMethods())
                    .filter(method -> method.getParameterCount() == 1 && method.getName().startsWith("set")
                            && !Modifier.isStatic(method.getModifiers()))
                    .sorted(Comparator.comparing((Method method) -> parameter(method).getName()))
                    .map(method -> Accessors.callable(type, method))
                    .collect(Collectors.groupingBy(Method::getName,
                            Collectors.collectingAndThen(Collectors.toList(), BeansAndMapsWriter::mostSpecificFirst)));
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
     * @throws IllegalStateException        if the setter can't be reached from the engine's module, saying how to
     *                                      make it reachable
     * @throws ReflectiveOperationException if the setter throws, which it reports as the cause of an
     *                                      {@link java.lang.reflect.InvocationTargetException}
     */
    @SuppressWarnings("unchecked")
    @Override
    public void set(Object output, String property, @Nullable Object value) throws ReflectiveOperationException {
        if (output instanceof Map<?, ?> map) {
            ((Map<String, @Nullable Object>) map).put(property, value);
            return;
        }
        String name = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        // Every setter that accepts the value is tried, because one the engine can't reach may have an overload it
        // can: a generic interface's setter is reached through its bridge method, not the class's typed one.
        IllegalAccessException refused = null;
        Method refusedSetter = null;
        for (Method setter : SETTERS.get(output.getClass()).getOrDefault(name, List.of())) {
            if (!accepts(setter.getParameterTypes()[0], value)) {
                continue;
            }
            try {
                setter.invoke(output, value);
                return;
            } catch (IllegalAccessException e) {
                refused = e;
                refusedSetter = setter;
            }
        }
        if (refused != null) {
            throw unreachable(output, property, refusedSetter, refused);
        }
        throw new IllegalArgumentException(output.getClass().getName() + " has no public method " + name
                + " that accepts " + (value == null ? "null" : "a " + value.getClass().getName()));
    }

    private static IllegalStateException unreachable(Object output, String property, Method setter,
                                                     IllegalAccessException refused) {
        return new IllegalStateException("A " + output.getClass().getName() + " has a setter for '" + property
                + "', but " + setter.getDeclaringClass().getName() + " can't be reached from here, and no public"
                + " supertype declares it. Declare the setter on a public type, or on a public interface the type"
                + " implements; on the module path, also export that type's package, or open it to"
                + " io.github.brantunger.unruly.core for a type that isn't public.", refused);
    }

    // Orders one name's overloads, given in the order of their parameter type's name, the way Java picks between
    // them: setters taking a reference come before those taking a primitive, which a boxed value reaches only when no
    // reference setter accepts it. Each reference setter comes before those whose parameter is a supertype of its
    // own, and among those left whose parameter isn't a supertype of another's, the first by name comes next. Only a
    // wrapper's own primitive accepts a value, so the primitive setters' order never decides a call and stays by name.
    // Two setters can share a parameter type, such as a setter that overrides with a narrower return type, and its
    // bridge; neither is more specific.
    private static List<Method> mostSpecificFirst(List<Method> overloads) {
        List<Method> references = new ArrayList<>(overloads);
        references.removeIf(method -> parameter(method).isPrimitive());
        List<Method> ordered = new ArrayList<>(overloads.size());
        while (!references.isEmpty()) {
            Method next = references.stream()
                    .filter(method -> references.stream().noneMatch(other -> moreSpecific(other, method)))
                    .findFirst().orElseThrow();
            references.remove(next);
            ordered.add(next);
        }
        overloads.stream().filter(method -> parameter(method).isPrimitive()).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static boolean moreSpecific(Method setter, Method than) {
        return !parameter(setter).equals(parameter(than)) && parameter(than).isAssignableFrom(parameter(setter));
    }

    private static Class<?> parameter(Method setter) {
        return setter.getParameterTypes()[0];
    }

    private static boolean accepts(Class<?> parameter, @Nullable Object value) {
        if (value == null) {
            return !parameter.isPrimitive();
        }
        return MethodType.methodType(parameter).wrap().returnType().isInstance(value);
    }
}
