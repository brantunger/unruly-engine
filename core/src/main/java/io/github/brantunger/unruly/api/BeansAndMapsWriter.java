package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.core.Accessors;
import io.github.brantunger.unruly.core.Widening;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.MalformedParameterizedTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The default {@link OutputWriter}: {@code put} on a {@link Map}, and otherwise the output class's public setter that
 * accepts the value. Of overloaded setters, it calls the most specific one that accepts the value, as Java would; when
 * no single one is the most specific, the choice is fixed for the output class and the same on every run. A boxed
 * primitive, only when no setter takes it as it is, goes to a primitive setter it widens to, as Java would, such as
 * an {@link Integer} to a setter taking a {@code long}; no value is otherwise converted. Each class's setters are
 * looked up once.
 *
 * <p>
 * A setter is called the way {@link io.github.brantunger.unruly.api.language.FactProperties} calls a getter: through a
 * public, exported type that declares it, such as an interface the output class implements, or otherwise directly
 * where the class's package is open to the engine's module, which every package on the class path is. So an output
 * class the engine can read, it can also write. A setter it can't reach fails, rather than a less specific overload
 * being called in its place, unless it overrides a generic setter, whose bridge method calls it. One limit: such a
 * bridge accepts the types of all the public instance setters of its name with a narrower parameter, declared beside
 * it or inherited, even from a class that isn't public, so a value for another of them that the engine can't reach
 * goes to the bridge. The bridge fails to cast it or, where the other setter's parameter is narrower than the
 * override's, calls the override, a less specific setter.
 * </p>
 */
final class BeansAndMapsWriter implements OutputWriter<Object> {

    /** The one instance, which keeps no state of its own. */
    static final BeansAndMapsWriter INSTANCE = new BeansAndMapsWriter();

    // The public instance methods with one parameter whose names start with "set", by name, for each output class,
    // each resolved to a method the engine can call, with the types of value it accepts. Those are worked out before
    // resolving, as a bridge method may resolve to an interface's, which isn't one: a bridge that the compiler adds for
    // a generic setter's override accepts only what the override does, not everything its erased parameter would.
    // Each also says whether it's such a bridge. Overloads are in the order mostSpecificFirst gives them, so the first
    // that accepts a value is a most specific one, as Java would pick, and the choice doesn't vary between runs. Each
    // class's public methods are listed once, for the output class and for a class that declares a bridge.
    private static final ClassValue<Map<String, List<Setter>>> SETTERS = new ClassValue<>() {
        @Override
        protected Map<String, List<Setter>> computeValue(Class<?> type) {
            Map<Class<?>, Method[]> methods = new HashMap<>();
            methods.put(type, type.getMethods());
            return Arrays.stream(methods.get(type))
                    .filter(method -> method.getParameterCount() == 1 && method.getName().startsWith("set")
                            && !Modifier.isStatic(method.getModifiers()))
                    .sorted(Comparator.comparing((Method method) -> parameter(method).getName()))
                    .map(method -> setter(type, method, methods))
                    .collect(Collectors.groupingBy(setter -> setter.method().getName(),
                            Collectors.collectingAndThen(Collectors.toList(), BeansAndMapsWriter::mostSpecificFirst)));
        }
    };

    /**
     * A setter the engine can call, whether it's a generic setter's bridge, and the types of value it accepts.
     *
     * @param method  The setter, resolved to a method the engine can call
     * @param generic Whether it's a bridge method the compiler added for an override of a generic setter
     * @param accepts The types of value it accepts: for a generic setter's bridge, those of its class's public instance
     *                setters of its name with a narrower parameter, and otherwise its parameter's, which for a
     *                primitive also accepts a boxed primitive that Java widens to it
     */
    private record Setter(Method method, boolean generic, List<Class<?>> accepts) {
    }

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
        // The most specific setter that accepts the value is tried first. If the engine can't reach it, only generic
        // setters' bridges are tried after it, as a generic interface's setter is reached through its bridge, not the
        // class's typed one. Such a bridge accepts only the types of the setters it may call, which come before it,
        // so but for the limit accepted() describes, it calls the setter that was refused. Calling any other setter,
        // including a bridge that calls a method with its own parameter, would call a method Java wouldn't, so the
        // setter that can't be reached fails instead.
        IllegalAccessException refused = null;
        Setter refusedSetter = null;
        List<Setter> setters = SETTERS.get(output.getClass()).getOrDefault(name, List.of());
        for (Setter setter : setters) {
            if (!accepts(setter, value)) {
                continue;
            }
            if (refusedSetter != null && !setter.generic()) {
                continue;
            }
            try {
                setter.method().invoke(output, value);
                return;
            } catch (IllegalAccessException e) {
                if (refused == null) {
                    refused = e;
                    refusedSetter = setter;
                }
            }
        }
        if (refused != null) {
            throw unreachable(output, property, refusedSetter.method(), refused);
        }
        throw new IllegalArgumentException(output.getClass().getName() + " has no public method " + name
                + " that accepts " + (value == null ? "null" : "a " + value.getClass().getName()
                + primitiveSetters(name, setters, value)));
    }

    // For a number, a character or a boolean that no setter accepts, the setters of its name that take a primitive or
    // a wrapper, so a failure says why they don't take it: the primitives in Widening.order(), then the wrappers by
    // name, each once. Otherwise, or where there are none, nothing.
    private static String primitiveSetters(String name, List<Setter> setters, Object value) {
        if (!Widening.isPrimitiveLike(value)) {
            return "";
        }
        List<String> existing = setters.stream()
                .<Class<?>>map(setter -> parameter(setter.method()))
                .filter(type -> type.isPrimitive() || Widening.isWrapper(type))
                .distinct()
                .sorted(Comparator.comparing((Class<?> type) -> !type.isPrimitive())
                        .thenComparingInt(Widening::order)
                        .thenComparing(Class::getTypeName))
                .map(type -> name + "(" + type.getTypeName() + ")")
                .toList();
        if (existing.isEmpty()) {
            return "";
        }
        return " (" + String.join(", ", existing) + (existing.size() == 1 ? " exists" : " exist")
                + ", but " + Widening.ONLY_WIDENED + ")";
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
    // own, and among those left whose parameter isn't a supertype of another's, the first by name comes next. The
    // primitive setters are in Widening.order(), so a wrapper's own primitive comes first, and then those it widens
    // to, the narrowest first. The primitives a value widens to lie on one chain, byte, short, int, long,
    // float, double or char, int, long, float, double, so the first of them that accepts it is the one Java picks.
    // Two setters can share a parameter type, such as a setter that overrides with a narrower return type, and its
    // bridge; neither is more specific. A bridge is placed by its own parameter, not the narrower types it accepts, so
    // it comes after the override it bridges to.
    private static List<Setter> mostSpecificFirst(List<Setter> overloads) {
        List<Setter> references = new ArrayList<>(overloads);
        references.removeIf(setter -> parameter(setter.method()).isPrimitive());
        List<Setter> ordered = new ArrayList<>(overloads.size());
        while (!references.isEmpty()) {
            Setter next = references.stream()
                    .filter(setter -> references.stream()
                            .noneMatch(other -> moreSpecific(other.method(), setter.method())))
                    .findFirst().orElseThrow();
            references.remove(next);
            ordered.add(next);
        }
        overloads.stream().filter(setter -> parameter(setter.method()).isPrimitive())
                .sorted(Comparator.comparingInt((Setter setter) -> Widening.order(parameter(setter.method()))))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    // A setter, worked out before resolving it. A bridge method is a generic setter's when it overrides a generic
    // setter and its class has setters of its name with a narrower parameter for it to call: the override, or the
    // bridge that makes the override callable where it's inherited from a class that isn't public. A bridge that
    // calls a method with its own parameter isn't: one that makes a public setter of a class that isn't public
    // callable through a public subclass, even one declared with a type variable, or one for an override with a
    // narrower return type. It accepts its own parameter, as the method it calls does.
    private static Setter setter(Class<?> type, Method method, Map<Class<?>, Method[]> methods) {
        List<Class<?>> bridged = method.isBridge() && overridesGeneric(method) ? accepted(method, methods) : List.of();
        boolean generic = !bridged.isEmpty();
        return new Setter(Accessors.callable(type, method), generic, generic ? bridged : List.of(parameter(method)));
    }

    // Whether a bridge overrides a generic setter: the first method with its name and parameter, of its class or a
    // superclass, that isn't a bridge is declared with a type variable, or there's none, as where only an interface
    // declares it. A bridge in an interface overrides a generic setter.
    private static boolean overridesGeneric(Method bridge) {
        Class<?> type = bridge.getDeclaringClass();
        while (type != null) {
            Method target;
            try {
                target = type.getMethod(bridge.getName(), bridge.getParameterTypes());
            } catch (NoSuchMethodException e) {
                return true;
            }
            if (!target.isBridge()) {
                return declaredWithATypeVariable(target);
            }
            type = type.getSuperclass();
        }
        return true;
    }

    // Whether a method's parameter is declared with a type variable, such as T or T[], which erases to another type
    // in an override. A parameterized type, such as List<String>, erases the same in the override, which needs no
    // bridge. A parameter whose declared type can't be read, as it names a class that's missing, counts as not. The
    // method has one parameter: it has a setter's name and parameter, as only setters are asked about.
    private static boolean declaredWithATypeVariable(Method method) {
        Type declared;
        try {
            declared = method.getGenericParameterTypes()[0];
        } catch (TypeNotPresentException | MalformedParameterizedTypeException e) {
            return false;
        }
        return declared instanceof TypeVariable<?> || declared instanceof GenericArrayType;
    }

    // The types of value a generic setter's bridge accepts: those of the methods it may bridge to, which are the
    // methods of its class, declared there or inherited, that have its name and one parameter narrower than its own,
    // as the override it calls has, and that are public, not static and not a generic setter's bridge. Any other
    // bridge counts: one that makes a method of a class that isn't public callable, as it may be how the override
    // reaches the class, or one for an override with a narrower return type, whose parameter is the override's.
    // getMethods() gives only public methods, so a private or protected one never widens a bridge, but it gives
    // static ones too. Nothing says which of those methods a bridge calls, so a bridge beside such an overload,
    // declared in its class or inherited from a superclass, even one that isn't public, also accepts the overload's
    // type. A value of that type reaches the bridge only where the engine can't reach the overload, and the bridge
    // then fails to cast it, or calls the override where the overload's parameter is narrower than the override's: a
    // known limit. None, for a bridge that makes a setter declared with a type variable callable through a public
    // subclass. The parameter count is checked first, as any bridge, even one of the setter's name, may have none.
    private static List<Class<?>> accepted(Method bridge, Map<Class<?>, Method[]> methods) {
        return Arrays.stream(methods.computeIfAbsent(bridge.getDeclaringClass(), Class::getMethods))
                .filter(other -> other.getParameterCount() == 1 && other.getName().equals(bridge.getName())
                        && moreSpecific(other, bridge) && !Modifier.isStatic(other.getModifiers())
                        && !(other.isBridge() && overridesGeneric(other)))
                .<Class<?>>map(BeansAndMapsWriter::parameter)
                .toList();
    }

    private static boolean moreSpecific(Method setter, Method than) {
        return !parameter(setter).equals(parameter(than)) && parameter(than).isAssignableFrom(parameter(setter));
    }

    private static Class<?> parameter(Method setter) {
        return setter.getParameterTypes()[0];
    }

    private static boolean accepts(Setter setter, @Nullable Object value) {
        for (Class<?> type : setter.accepts()) {
            if (accepts(type, value)) {
                return true;
            }
        }
        return false;
    }

    // A value is accepted as it is, or, by a primitive parameter, where Java widens the value's primitive to it.
    // Method.invoke unboxes and widens it the same way.
    private static boolean accepts(Class<?> parameter, @Nullable Object value) {
        if (value == null) {
            return !parameter.isPrimitive();
        }
        return Widening.wrap(parameter).isInstance(value) || Widening.widens(value, parameter);
    }
}
