package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a property of a fact the way rules expect: a record component, a JavaBean getter or a {@link Map} key.
 *
 * <p>
 * Rules everywhere are written {@code applicant.creditScore}, where the fact may be a record, a bean or a map. The
 * engine hands a language the fact objects as they are, so making that work is each language's job, and every
 * language that does it by hand does it slightly differently: one reads a record's component as a method and
 * silently evaluates the condition to {@code false}, another fails only for records, a third converts every fact to
 * a map on every evaluation. A language may use this instead.
 * </p>
 *
 * <p>
 * Writing a property is the other direction, and the engine already has it:
 * {@link io.github.brantunger.unruly.api.OutputWriter#beansAndMaps()} puts into a map or calls a bean setter. There
 * is deliberately no {@code write} here, so there's only one implementation to keep correct. Records can't be
 * written to at all.
 * </p>
 *
 * <p>
 * Accessors are looked up once for each class and shared by every thread.
 * </p>
 */
public final class FactProperties {

    // The readable properties of each class, by name: a record's components, or a bean's public no-argument getX and
    // isX methods. Methods declared by Object are left out, so getClass() isn't a property.
    private static final ClassValue<Map<String, Method>> ACCESSORS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            return accessorsOf(type);
        }
    };

    /** The fewest levels {@link #toData} converts: the target itself. */
    private static final int MIN_DEPTH = 1;

    /**
     * The most levels {@link #toData} converts. Each level is a stack frame, and a limit a real object graph never
     * reaches is part of what keeps a conversion bounded; the other part is that a value already being converted
     * higher up the same path is left as it is rather than converted again.
     */
    private static final int MAX_DEPTH = 20;

    private FactProperties() {
    }

    /**
     * Reads a property of a fact.
     *
     * <ul>
     *     <li>A {@link Map}: the value under that <b>exact</b> key. A key that isn't there is an error, and a key
     *     whose value is {@code null} isn't. A map whose keys aren't strings has no property a rule can name, even
     *     though {@link #toData} gives those keys a string form.</li>
     *     <li>A record: the component of that name, or one of its own getters when no component matches.</li>
     *     <li>Anything else: the public no-argument {@code getProperty()}, or {@code isProperty()} when it returns a
     *     {@code boolean} or a {@link Boolean}. A class declaring both resolves to {@code getProperty()}, so the choice never depends on
     *     the order reflection reports methods in. Public fields aren't read.</li>
     * </ul>
     *
     * <p>
     * A fact whose own class isn't public is read through a public type above it that declares the accessor, which
     * is what makes a fact from a factory, or an anonymous implementation of a public interface, readable.
     * </p>
     *
     * @param target   The fact, which must not be {@code null}
     * @param property The property's name, as the rule wrote it
     * @return The property's value, which may be {@code null}
     * @throws NullPointerException     if {@code target} or {@code property} is {@code null}
     * @throws IllegalArgumentException if the fact has no such property. A language should let this reach the
     *                                  engine, which fails the rule with it: a missing property is a mistake in the
     *                                  rule, and evaluating it to {@code false} or to undefined hides it
     * @throws IllegalStateException    if the property exists but can't be read, because nothing public declares its
     *                                  accessor; or if the accessor threw, with what it threw as the cause. What an
     *                                  accessor throws is always wrapped, so that a getter throwing
     *                                  {@link IllegalArgumentException} isn't read as a missing property
     */
    public static @Nullable Object read(Object target, String property) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(property, "property must not be null");
        if (target instanceof Map<?, ?> map) {
            // A sorted or otherwise restricted map can refuse a key of the wrong type outright, which is the same
            // answer as not having it: rules name properties with strings.
            try {
                if (!map.containsKey(property)) {
                    throw new IllegalArgumentException(noSuchProperty(target, property));
                }
                return map.get(property);
            } catch (ClassCastException | NullPointerException e) {
                throw new IllegalArgumentException(noSuchProperty(target, property), e);
            }
        }
        Method accessor = ACCESSORS.get(target.getClass()).get(property);
        if (accessor == null) {
            throw new IllegalArgumentException(noSuchProperty(target, property));
        }
        return invoke(accessor, target, property);
    }

    /**
     * Converts a record, a bean or a map into a map of its properties, for a language that reads only maps.
     *
     * <p>
     * <b>Every step down costs one level</b>, whether it's into a property, into a map's value or into a collection's
     * element. At {@code 1} the values are whatever the properties hold; at {@code 2} a value that is itself
     * convertible becomes a map, and a list becomes a list of the elements as they are; at {@code 3} the elements of
     * that list are converted too.
     * </p>
     *
     * <p>
     * A value already being converted higher up the same path is left as it is rather than converted again, so a
     * fact that holds itself, or two facts that hold each other, cost no more than a tree of the same depth. That
     * stops a value repeating down one path; it doesn't make every graph cheap. A value reachable by several
     * <b>different</b> paths is converted once for each of them, so an object graph that fans out and rejoins can
     * still cost far more than it holds. {@code depth} is what bounds that, and it's chosen by the caller, not by
     * the facts: convert as few levels as the language needs.
     * </p>
     *
     * <p>
     * A map's values keep their keys, with a key that isn't a {@link String} converted by {@link String#valueOf}.
     * Two keys that read the same then collapse into one entry and the later wins, and a {@code null} key becomes
     * the entry {@code "null"}, which a real {@code "null"} key can't be told apart from. {@link #read} takes the
     * key as it is, so a converted map can show a property that reading the fact directly can't reach.
     * </p>
     *
     * <p>
     * A value is converted when it's a map, a collection, an array of objects, a record, or an application class
     * with at least one getter. Everything the JVM itself provides is left as it is, which is what keeps a
     * {@link String}, a {@link java.nio.file.Path}, a {@link java.time.LocalDate}, an {@link java.util.Optional}, an
     * enum, a lambda or an array of primitives from being taken apart into the properties of its implementation. A
     * proxy over an interface is data, because that's a shape facts take: a projection, a lazy association, a test
     * double. A lambda or an anonymous class is converted when it's the {@code target}, because then the caller
     * asked for its properties, and left alone when it's met as a value.
     * </p>
     *
     * <p>
     * <b>The rule is "has getters", so it can't tell data from a library type that merely looks like it.</b> A JSON
     * tree node or a multimap from a third-party library has {@code isEmpty()}, {@code isArray()} and the like, so
     * converting a fact that holds one returns those flags instead of its contents. Read such a fact with
     * {@link #read}, or have the language convert it itself.
     * </p>
     *
     * @param target The record, bean or map to convert, which must not be {@code null}
     * @param depth  How many levels to convert, from {@code 1} to {@value #MAX_DEPTH}
     * @return The properties by name: a record's components in the order it declares them, then any other getters it
     *         has, and a bean's sorted by name, because reflection reports a class's methods in no particular order.
     *         A new modifiable map
     * @throws NullPointerException     if {@code target} is {@code null}
     * @throws IllegalArgumentException if {@code depth} is outside {@code 1} to {@value #MAX_DEPTH}, or
     *                                  {@code target} is a value this doesn't take apart, such as a {@link String},
     *                                  a number or a collection
     * @throws IllegalStateException    if an accessor can't be called, with what it threw as the cause
     */
    public static Map<String, @Nullable Object> toData(Object target, int depth) {
        Objects.requireNonNull(target, "target must not be null");
        if (depth < MIN_DEPTH || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("depth must be between " + MIN_DEPTH + " and " + MAX_DEPTH
                    + ", but was " + depth);
        }
        Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());
        path.add(target);
        if (target instanceof Map<?, ?> map) {
            return entriesOf(map, depth, path);
        }
        // A collection or an array converts to a list, not to a map, so it can't be the target however many getters
        // it has of its own.
        if (target instanceof Collection || target.getClass().isArray() || !hasProperties(target)) {
            throw new IllegalArgumentException(target.getClass().getName()
                    + " isn't a record, a bean or a map, so it has no properties to convert");
        }
        return propertiesOf(target, depth, path);
    }

    private static @Nullable Object convert(@Nullable Object value, int depth, Set<Object> path) {
        if (value == null || depth < MIN_DEPTH || !isData(value)) {
            return value;
        }
        // Already being converted higher up this path, so descending again would repeat work that a bidirectional
        // graph makes exponential, and would never end on a value that holds itself.
        if (!path.add(value)) {
            return value;
        }
        try {
            if (value instanceof Map<?, ?> map) {
                return entriesOf(map, depth, path);
            }
            if (value instanceof Collection<?> collection) {
                return elementsOf(collection, depth, path);
            }
            if (value.getClass().isArray()) {
                return arrayElementsOf(value, depth, path);
            }
            return propertiesOf(value, depth, path);
        } finally {
            path.remove(value);
        }
    }

    private static Map<String, @Nullable Object> entriesOf(Map<?, ?> map, int depth, Set<Object> path) {
        Map<String, @Nullable Object> converted = new LinkedHashMap<>();
        map.forEach((key, value) -> converted.put(String.valueOf(key), convert(value, depth - 1, path)));
        return converted;
    }

    private static List<@Nullable Object> elementsOf(Collection<?> collection, int depth, Set<Object> path) {
        List<@Nullable Object> converted = new ArrayList<>(collection.size());
        collection.forEach(element -> converted.add(convert(element, depth - 1, path)));
        return converted;
    }

    private static List<@Nullable Object> arrayElementsOf(Object array, int depth, Set<Object> path) {
        int length = Array.getLength(array);
        List<@Nullable Object> converted = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            converted.add(convert(Array.get(array, i), depth - 1, path));
        }
        return converted;
    }

    private static Map<String, @Nullable Object> propertiesOf(Object value, int depth, Set<Object> path) {
        Map<String, @Nullable Object> properties = new LinkedHashMap<>();
        ACCESSORS.get(value.getClass()).forEach(
                (name, accessor) -> properties.put(name, convert(invoke(accessor, value, name), depth - 1, path)));
        return properties;
    }

    /** Whether a value met inside a conversion is taken apart, rather than left as it is. */
    private static boolean isData(Object value) {
        Class<?> type = value.getClass();
        if (value instanceof Map || value instanceof Collection) {
            return true;
        }
        if (type.isArray()) {
            // An array of bytes holding an image is a value the rules pass around, not a structure: converting it
            // would make one object for every element.
            return !type.getComponentType().isPrimitive();
        }
        // A lambda or an anonymous class is an implementation, not data: IntSupplier's getAsInt() would otherwise
        // read as a property named asInt, and converting a fact that holds one would call it.
        return !type.isSynthetic() && !type.isAnonymousClass() && hasProperties(value);
    }

    /** Whether a value has properties of its own to convert, rather than being one of the platform's values. */
    private static boolean hasProperties(Object value) {
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            return true;
        }
        // instanceof, not Class.isEnum(): an enum constant with a body is an anonymous subclass, for which
        // isEnum() is false, and it would otherwise convert to the junk property declaringClass.
        if (value instanceof Enum) {
            return false;
        }
        return !isPlatformValue(type) && !ACCESSORS.get(type).isEmpty();
    }

    /**
     * Whether a class is one of the platform's own values rather than an application's data: the JVM defines those,
     * so they come from the boot or platform class loader, while an application's classes come from its own. A proxy
     * is the exception: the JVM defines it, but it's a shape facts really take (a projection, a lazy association, a
     * test double) and it is the interfaces it implements, so it counts as data.
     *
     * @param type The value's class
     * @return {@code true} if the value is left as it is rather than taken apart
     */
    // getClassLoader() on the value's own class is the point: which loader DEFINED it, not which one a caller
    // would look a name up with. Loaders are identities, so == is how they're compared.
    @SuppressWarnings({"PMD.UseProperClassLoader", "PMD.CompareObjectsWithEquals"})
    private static boolean isPlatformValue(Class<?> type) {
        if (Proxy.isProxyClass(type)) {
            return false;
        }
        // By the loader that defined it, not by its name: most of the platform's values are implemented by a class
        // called something else, so a name rule dismantles them. Path.of("x") is a sun.nio.fs.WindowsPath, and
        // converting it by its getters returns the file system's root directories instead of the path.
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    private static Map<String, Method> accessorsOf(Class<?> type) {
        Map<String, Method> accessors = new LinkedHashMap<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                accessors.put(component.getName(), callable(type, component.getAccessor()));
            }
        }
        // Sorted, because Class.getMethods() promises no order, and a conversion whose keys moved between runs of
        // the same program would be a poor thing to hand a language. A record's components keep their declared
        // order, and its other getters follow: a record may compute a property its components don't hold.
        Map<String, Method> getters = new TreeMap<>();
        // By name, so that a class declaring both isActive() and getActive() always resolves to the same one:
        // keeping whichever reflection listed first would let the same rule read a different value on another JVM.
        List<Method> methods = new ArrayList<>(List.of(type.getMethods()));
        methods.sort(Comparator.comparing(Method::getName));
        for (Method method : methods) {
            // Declared by Object, so getClass() is never a property.
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                continue;
            }
            String property = propertyName(method);
            if (property != null) {
                getters.putIfAbsent(property, method);
            }
        }
        getters.forEach((property, method) -> accessors.putIfAbsent(property, callable(type, method)));
        return Collections.unmodifiableMap(accessors);
    }

    /**
     * Returns an accessor that can be called from here. A public method declared on a class that isn't itself public
     * can't be invoked from another package, so the same method is looked up on a public type above <b>the fact's
     * class</b>: the accessor may be declared on a base class that is no more public than the fact's own, while the
     * interface that makes it public is implemented by the fact's class alone.
     *
     * @param type   The fact's class, which the accessor was found on
     * @param method The method found there
     * @return The same method, or the one a public supertype declares
     */
    private static Method callable(Class<?> type, Method method) {
        if (reachable(method.getDeclaringClass())) {
            return method;
        }
        for (Class<?> supertype : supertypesOf(type)) {
            if (!reachable(supertype)) {
                continue;
            }
            try {
                Method declared = supertype.getMethod(method.getName());
                // A reachable type can inherit the method from one that isn't, and invoking it would fail the same
                // way, so what matters is where the method we found is declared. An interface may also declare a
                // static method of the same name, which isn't the fact's accessor at all: invoking it would ignore
                // the fact and return something else.
                if (reachable(declared.getDeclaringClass()) && !Modifier.isStatic(declared.getModifiers())) {
                    return declared;
                }
            } catch (NoSuchMethodException e) {
                continue;
            }
        }
        // Nothing public declares it. It stays here so that reading it reports why, rather than claiming the fact
        // has no such property.
        return method;
    }

    /**
     * Whether a method declared on this type can be invoked from here. Being public isn't enough: a public class in
     * a package its module doesn't export isn't reflectively reachable either, which is how {@code TimeZone}'s own
     * {@code sun.util.calendar.ZoneInfo} behaves, and how an application's internal package behaves on the module
     * path. Both are read through a type that is reachable, such as {@code java.util.TimeZone} itself.
     *
     * <p>
     * A package a module only {@code opens}, rather than {@code exports}, isn't counted: reading it would mean
     * calling {@code setAccessible}, and this class never does. An application whose facts live in such a package
     * either exports it or gives the fact a public interface to be read through.
     * </p>
     *
     * @param type The type declaring the accessor
     * @return {@code true} if this class can invoke its methods
     */
    private static boolean reachable(Class<?> type) {
        return Modifier.isPublic(type.getModifiers())
                && type.getModule().isExported(type.getPackageName(), FactProperties.class.getModule());
    }

    /**
     * Every type above {@code type}, nearest first: its interfaces, the interfaces those extend, its superclasses,
     * and their interfaces. Walking the whole graph is what finds the public interface behind one that isn't.
     *
     * @param type The class to walk up from
     * @return Its supertypes, each once
     */
    private static List<Class<?>> supertypesOf(Class<?> type) {
        List<Class<?>> supertypes = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> next = queue.remove();
            if (!seen.add(next)) {
                continue;
            }
            if (next != type) {
                supertypes.add(next);
            }
            queue.addAll(List.of(next.getInterfaces()));
            if (next.getSuperclass() != null) {
                queue.add(next.getSuperclass());
            }
        }
        return supertypes;
    }

    /** The property a getter reads, or {@code null} if the method isn't one. */
    private static @Nullable String propertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        // Only an isX() returning boolean or Boolean is a getter: isNotAProperty() returning a String isn't one. A
        // list's isEmpty() does qualify, so a list read directly has a property named empty; what keeps a list from
        // contributing one to a conversion is that convert() turns a collection into a list, and that
        // isPlatformValue() leaves the platform's classes alone, not this.
        boolean returnsBoolean = method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class;
        if (returnsBoolean && name.startsWith("is") && name.length() > 2) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            // As java.beans does: URL stays URL, so getURL() is the property URL.
            return name;
        }
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }

    private static @Nullable Object invoke(Method accessor, Object target, String property) {
        try {
            return accessor.invoke(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("A " + target.getClass().getName() + " has a property '" + property
                    + "', but its accessor on " + accessor.getDeclaringClass().getName() + " can't be reached from"
                    + " here, and no public supertype declares it. The type that declares the accessor has to be"
                    + " public, or implement a public interface that declares it; on the module path its package"
                    + " must be exported as well.", e);
        } catch (InvocationTargetException e) {
            // Always wrapped, never rethrown as it came: IllegalArgumentException is how this class says a fact has
            // no such property, so a getter that validates its state must not be mistaken for a misspelled rule.
            throw new IllegalStateException("Reading '" + property + "' on a " + target.getClass().getName()
                    + " failed", e.getCause());
        }
    }

    // The property name comes from the rule's text, and the message carries no fact value, so there is nothing here
    // that a run's data could forge a log line with.
    private static String noSuchProperty(Object target, String property) {
        return "A " + target.getClass().getName() + " has no property '" + property
                + "'. A fact's properties are a record's components, a bean's getters, or a map's keys.";
    }
}
