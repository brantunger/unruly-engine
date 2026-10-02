package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.core.Accessors;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    // isX methods. Methods declared by Object or Enum are left out, so getClass() and an enum's getDeclaringClass()
    // aren't properties, and a class isRuntimeType() matches has none.
    private static final ClassValue<Map<String, Method>> ACCESSORS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            return accessorsOf(type);
        }
    };

    // The JVM's own types that have no properties, with their subclasses, besides the classes in java.lang.reflect:
    // through them a rule could walk from a fact to the class loaders, the modules and the application's files.
    private static final List<Class<?>> RUNTIME_TYPES = List.of(Class.class, ClassLoader.class, Module.class,
            ModuleLayer.class, Package.class, ProtectionDomain.class, CodeSource.class, Thread.class);

    /** The fewest levels {@link #toData} converts: the target itself. */
    private static final int MIN_DEPTH = 1;

    /**
     * The most levels {@link #toData} converts. Each level is a stack frame, and a limit a real object graph never
     * reaches is part of what keeps a conversion bounded; the other part is that a value already being converted
     * higher up the same path is left as it is rather than converted again.
     */
    private static final int MAX_DEPTH = 20;

    private static final String NULL_TARGET = "target must not be null";

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
     *     {@code boolean} or a {@link Boolean}. A class declaring both resolves to {@code getProperty()}, so the choice
     *     never depends on the order reflection reports methods in. Public fields aren't read.</li>
     * </ul>
     *
     * <p>
     * A fact whose own class isn't public is read through a public type above it that declares the accessor, which
     * is what makes a fact from a factory, or an anonymous implementation of a public interface, readable. When
     * nothing public declares it, the accessor is called directly if the class's package is open to this module:
     * every package on the class path is, and on the module path the application opens it with {@code opens}.
     * Exporting the package isn't enough, because calling a method of a class that isn't public is deep reflection.
     * </p>
     *
     * <p>
     * <b>Rules can't read their way into the JVM through a fact.</b> An enum's {@code getDeclaringClass()} isn't a
     * property, as {@code getClass()} isn't one. And a {@link Class}, a {@link ClassLoader}, a {@link Module}, a
     * {@link ModuleLayer}, a {@link Package}, a {@link java.security.ProtectionDomain}, a
     * {@link java.security.CodeSource}, a {@link Thread}, an application's own subclass of one of them, or an object
     * of a class in {@code java.lang.reflect}, such as a {@link Method}, has no properties here at all, so
     * {@link #toData} leaves one as it is too. A fact's own property may still return one of them as its value; only
     * reading a property of that value is refused.
     * </p>
     *
     * @param target   The fact, which must not be {@code null}
     * @param property The property's name, as the rule wrote it
     * @return The property's value, which may be {@code null}
     * @throws NullPointerException     if {@code target} or {@code property} is {@code null}
     * @throws IllegalArgumentException if the fact has no such property, or is one of the JVM's own objects whose
     *                                  properties aren't read. A language should let this reach the engine, which
     *                                  fails the rule with it: a missing property is a mistake in the rule, and
     *                                  evaluating it to {@code false} or to undefined hides it
     * @throws IllegalStateException    if the property exists but can't be read, because nothing public declares its
     *                                  accessor and its package isn't open to this module; or if the accessor
     *                                  threw, with what it threw as the cause. The message, such as
     *                                  {@code Reading 'price' on a com.example.Item failed: price service down},
     *                                  ends with a colon and the message of what the accessor threw, whole, however
     *                                  long, for the engine to shorten when it logs it. That is left out when what
     *                                  it threw has no message or reading it throws, and when it's a failure the
     *                                  engine has already logged and names in the rule's failure as
     *                                  {@code a nested run() failed: } and its text, or as its text and
     *                                  {@code (already logged)} when no run the code around the read started
     *                                  logged it: a nested run's failure, whichever thread
     *                                  the read is on, or a fatal {@link Error} a run nested in the one in progress
     *                                  on this thread logged, in either case with nothing of its own around it.
     *                                  The cause still holds it. What a getter throws is always wrapped, so that
     *                                  one throwing {@link IllegalArgumentException} isn't read as a missing
     *                                  property. A map's own {@code containsKey} and {@code get} are its accessors,
     *                                  and an exception from them is wrapped too, with one exception: a
     *                                  {@link ClassCastException} or {@link NullPointerException} from
     *                                  {@code containsKey} is taken for the map refusing a key of that type, as a
     *                                  sorted map refuses a string key, and reported as no such property, though
     *                                  a map that fails that way for a reason of its own reads the same. Once
     *                                  {@code containsKey} has found the key, whatever {@code get} throws is the
     *                                  map's own failure
     */
    public static @Nullable Object read(Object target, String property) {
        Objects.requireNonNull(target, NULL_TARGET);
        Objects.requireNonNull(property, "property must not be null");
        if (target instanceof Map<?, ?> map) {
            // Anything get() throws for a key the map said it has is its own failure, wrapped as a getter's is; the
            // missing key is reported outside the try, so that isn't wrapped. Exception, not RuntimeException: a map
            // can throw a checked exception it doesn't declare.
            if (!containsKey(map, property)) {
                throw new IllegalArgumentException(noSuchProperty(target, property));
            }
            try {
                return map.get(property);
            } catch (Exception e) {
                throw readFailed(target, property, e);
            }
        }
        Method accessor = ACCESSORS.get(target.getClass()).get(property);
        if (accessor == null) {
            // Only once the read has failed, so a fact that is read costs nothing more.
            if (isRuntimeType(target.getClass())) {
                throw new IllegalArgumentException("A " + target.getClass().getName() + " has no property '"
                        + property + "'. The properties of a class, a class loader, a module, a package, a thread,"
                        + " or a reflection or security object aren't read.");
            }
            throw new IllegalArgumentException(noSuchProperty(target, property));
        }
        return invoke(accessor, target, property);
    }

    /**
     * Says whether a fact has a property, without reading it: whether {@link #read} would find the property rather
     * than throw {@link IllegalArgumentException}. No getter is called, and of a map only its own {@code containsKey},
     * so a getter that is slow, has side effects or throws doesn't change the answer, which is what a language needs
     * for its own "is it there" questions, such as JavaScript's {@code in} or Python's {@code hasattr}.
     *
     * <p>
     * A map has the property when its {@code containsKey} says so, a record or a bean when it has an accessor of that
     * name, by the same rules as {@link #read}, a fact whose class isn't public included. A property whose accessor
     * throws, or can't be called, is still there: {@link #read} fails for it with {@link IllegalStateException},
     * not as a missing property. A {@link String}, a number or a collection has the properties its public getters
     * give it, as {@link #read} reads them, such as a string's {@code empty}; one of the JVM's own objects whose
     * properties {@link #read} refuses has none.
     * </p>
     *
     * @param target   The fact, which must not be {@code null}
     * @param property The property's name, as the rule wrote it
     * @return {@code true} if {@link #read} would read the property, {@code false} if it would report it missing
     * @throws NullPointerException  if {@code target} or {@code property} is {@code null}
     * @throws IllegalStateException if {@code target} is a map whose {@code containsKey} throws, with what it threw
     *                               as the cause, as {@link #read} wraps it. A {@link ClassCastException} or
     *                               {@link NullPointerException} from it is taken for the map refusing a key of
     *                               that type, as {@link #read} takes it, and answers {@code false}
     */
    public static boolean has(Object target, String property) {
        Objects.requireNonNull(target, NULL_TARGET);
        Objects.requireNonNull(property, "property must not be null");
        if (target instanceof Map<?, ?> map) {
            try {
                return containsKey(map, property);
            } catch (IllegalArgumentException e) {
                // The map refused a key of that type: containsKey wraps everything else the map throws in an
                // IllegalStateException, so this can only be that.
                return false;
            }
        }
        return ACCESSORS.get(target.getClass()).containsKey(property);
    }

    /**
     * Asks a map fact whether it has a key, for {@link #read} and {@link #has} alike, so the two can't disagree. A
     * sorted or otherwise restricted map can refuse a key of the wrong type outright, which is the same answer as not
     * having it: rules name properties with strings. Anything else the map throws is its own failure, wrapped as a
     * getter's is. Exception, not RuntimeException: a map can throw a checked exception it doesn't declare.
     *
     * @param map      The map fact
     * @param property The property's name
     * @return Whether the map has the key
     * @throws IllegalArgumentException if {@code containsKey} threw a {@link ClassCastException} or a
     *                                  {@link NullPointerException}, with it as the cause
     * @throws IllegalStateException    if {@code containsKey} threw anything else, with it as the cause
     */
    private static boolean containsKey(Map<?, ?> map, String property) {
        try {
            return map.containsKey(property);
        } catch (ClassCastException | NullPointerException e) {
            throw new IllegalArgumentException(noSuchProperty(map, property), e);
        } catch (Exception e) {
            throw readFailed(map, property, e);
        }
    }

    /**
     * Lists the properties {@link #read} can read on a fact, without reading any of them, for a language that
     * enumerates an object's properties, such as JavaScript's {@code Object.keys} or Python's {@code dir}. No getter
     * is called, and of a map only its own {@code keySet}, through its {@code forEach}, so a synchronized map lists
     * its keys under its lock.
     *
     * <p>
     * A map's properties are its {@link String} keys, in its iteration order. A key of another type, or
     * {@code null}, isn't one {@link #read} can reach, so it's left out, though {@link #toData} gives it a string
     * form. A record's are its components in the order it declares them, then any other getters it has, and a bean's
     * are sorted by name: the names, in the order, of {@code toData(target, 1).keySet()}, with no getter called.
     * </p>
     *
     * <p>
     * Unlike {@link #toData}, which takes apart only a record, a bean or a map, this answers for any fact
     * {@link #read} reads: a {@link String}, a number or a collection has the properties its public getters give it,
     * such as a string's {@code empty}. One of the JVM's own objects whose properties {@link #read} refuses has none.
     * {@link #has} is {@code true} for every name listed; a map whose {@code containsKey} finds keys it doesn't
     * list, such as one that ignores case, has more.
     * </p>
     *
     * @param target The fact, which must not be {@code null}
     * @return The names, unmodifiable, in the same order every time for the same class, or for a map, in its
     *         iteration order
     * @throws NullPointerException  if {@code target} is {@code null}
     * @throws IllegalStateException if {@code target} is a map whose {@code keySet} or its {@code forEach} throws,
     *                               with what it threw as the cause, as {@code Reading the keys of a ... failed}
     */
    public static Set<String> propertyNames(Object target) {
        Objects.requireNonNull(target, NULL_TARGET);
        if (!(target instanceof Map<?, ?> map)) {
            // The accessors are an unmodifiable map in the order toData reads them, so its keys are the answer.
            return ACCESSORS.get(target.getClass()).keySet();
        }
        Set<String> names = new LinkedHashSet<>();
        // Through forEach, not an iterator, as a collection's elements are read: a synchronized map's key set holds
        // the map's lock for the whole of it. Exception, not RuntimeException: a map can throw a checked exception it
        // doesn't declare.
        try {
            map.keySet().forEach(key -> {
                if (key instanceof String name) {
                    names.add(name);
                }
            });
        } catch (Exception e) {
            throw containerFailed("keys", map, e);
        }
        return Collections.unmodifiableSet(names);
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
     * key as it is, so a converted map can show a property that reading the fact directly can't reach. A map's
     * entries are copied through its entry set's {@code forEach}, so a synchronized map's are copied under its lock,
     * each entry's {@code getKey()} and {@code getValue()} and each key's {@code toString()} included. Its values are
     * converted after the lock is released, so their getters don't run under it.
     * </p>
     *
     * <p>
     * A value is converted when it's a map, a collection, an array of objects, a record, or an application class
     * with at least one getter. Everything the JVM itself provides is left as it is, which is what keeps a
     * {@link String}, a {@link java.nio.file.Path}, a {@link java.time.LocalDate}, an {@link java.util.Optional}, an
     * enum, a lambda or an array of primitives from being taken apart into the properties of its implementation. A
     * proxy over an interface is data, because that's a shape facts take: a projection, a lazy association, a test
     * double. A lambda or an anonymous class is converted when it's the {@code target}, because then the caller
     * asked for its properties, and left alone when it's met as a value. An object whose properties {@link #read}
     * doesn't read, such as an application's own class loader or thread, is left as it is too, and can't be the
     * {@code target}.
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
     * @throws IllegalStateException    if an accessor can't be called, with what it threw as the cause, and its
     *                                  message after a colon as {@link #read} appends it. Reading a map's entries (its
     *                                  {@code entrySet()}, the entry set's {@code forEach}, each entry's
     *                                  {@code getKey()} and {@code getValue()} and each key's {@code toString()}) and a
     *                                  collection's {@code size()} and {@code forEach} count as accessors, so what they
     *                                  throw is wrapped too, as
     *                                  {@code Reading the entries of a ... failed} or
     *                                  {@code Reading the elements of a ... failed}
     */
    public static Map<String, @Nullable Object> toData(Object target, int depth) {
        Objects.requireNonNull(target, NULL_TARGET);
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

    // Through forEach, not an iterator, as a collection's elements are read: a synchronized map's entry set holds the
    // map's lock for the whole of it. The entries are copied under the lock, keys' toString() included, and their
    // values converted after it's released, so no nested getter runs under the map's lock, and only the map's own
    // calls are wrapped: a nested value's failure isn't wrapped a second time. Exception, not RuntimeException: a map
    // can throw a checked exception it doesn't declare.
    private static Map<String, @Nullable Object> entriesOf(Map<?, ?> map, int depth, Set<Object> path) {
        List<Map.Entry<String, @Nullable Object>> entries = new ArrayList<>();
        try {
            map.entrySet().forEach(entry -> entries.add(
                    new AbstractMap.SimpleImmutableEntry<>(String.valueOf(entry.getKey()), entry.getValue())));
        } catch (Exception e) {
            throw containerFailed("entries", map, e);
        }
        Map<String, @Nullable Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, @Nullable Object> entry : entries) {
            converted.put(entry.getKey(), convert(entry.getValue(), depth - 1, path));
        }
        return converted;
    }

    private static IllegalStateException containerFailed(String what, Object container, Exception cause) {
        return Accessors.readFailed("Reading the " + what + " of a " + container.getClass().getName() + " failed",
                cause);
    }

    // Through forEach, not an iterator: a synchronized collection holds its lock for the whole of it, and a collection
    // may override forEach alone. An element's conversion can't be moved out of the collection's call, as a map
    // entry's is, so its failure leaves forEach inside an ElementFailed, and only what the collection itself throws
    // is wrapped: a nested element's failure isn't wrapped a second time. Exception, not RuntimeException: a
    // collection can throw a checked exception it doesn't declare.
    private static List<@Nullable Object> elementsOf(Collection<?> collection, int depth, Set<Object> path) {
        List<@Nullable Object> converted;
        try {
            converted = new ArrayList<>(collection.size());
        } catch (Exception e) {
            throw containerFailed("elements", collection, e);
        }
        try {
            collection.forEach(element -> {
                try {
                    converted.add(convert(element, depth - 1, path));
                } catch (RuntimeException e) {
                    throw new ElementFailed(e);
                }
            });
        } catch (Exception e) {
            throw elementOrCollectionFailure(collection, e);
        }
        return converted;
    }

    /**
     * Tells what a collection's {@code forEach} threw: an element's own failure, which is thrown on as it came, or the
     * collection's, which is wrapped. The element's is found in the {@link ElementFailed} itself, or in one a
     * collection wrapped in an exception of its own.
     *
     * @param collection The collection
     * @param thrown     What its {@code forEach} threw
     * @return The element's failure, or the collection's wrapped
     */
    private static RuntimeException elementOrCollectionFailure(Collection<?> collection, Exception thrown) {
        ElementFailed carried = Accessors.inCauses(thrown, ElementFailed.class);
        return carried != null ? carried.failure : containerFailed("elements", collection, thrown);
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
        // An enum is a value, even one with getters of its own. instanceof, not Class.isEnum(): an enum constant with
        // a body is an anonymous subclass, for which isEnum() is false.
        if (value instanceof Enum) {
            return false;
        }
        return !isPlatformValue(type) && !ACCESSORS.get(type).isEmpty();
    }

    /**
     * Whether a class is one of the JVM's own that lead into it, rather than a value a rule reads: a class, a class
     * loader, a module, a package, a thread, or a reflection or security object, or an application's subclass of one
     * of them. A class in {@code java.lang.reflect} is matched by its package, not as a subtype: a proxy is a
     * {@link Proxy}, and a proxy is data.
     *
     * @param type The value's class
     * @return {@code true} if it has no properties
     */
    private static boolean isRuntimeType(Class<?> type) {
        for (Class<?> runtimeType : RUNTIME_TYPES) {
            if (runtimeType.isAssignableFrom(type)) {
                return true;
            }
        }
        return "java.lang.reflect".equals(type.getPackageName());
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
        if (isRuntimeType(type)) {
            // Not Map.of(), whose key set throws when asked whether it holds null: propertyNames hands that set out.
            return Collections.emptyMap();
        }
        Map<String, Method> accessors = new LinkedHashMap<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                accessors.put(component.getName(), Accessors.callable(type, component.getAccessor()));
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
            String property = Accessors.property(method);
            if (property != null) {
                getters.putIfAbsent(property, method);
            }
        }
        getters.forEach((property, method) -> accessors.putIfAbsent(property, Accessors.callable(type, method)));
        return Collections.unmodifiableMap(accessors);
    }

    private static @Nullable Object invoke(Method accessor, Object target, String property) {
        try {
            return accessor.invoke(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("A " + target.getClass().getName() + " has a property '" + property
                    + "', but its accessor on " + Accessors.unreachable("accessor", accessor), e);
        } catch (InvocationTargetException e) {
            // Always wrapped, never rethrown as it came: IllegalArgumentException is how this class says a fact has
            // no such property, so a getter that validates its state must not be mistaken for a misspelled rule.
            throw readFailed(target, property, e.getCause());
        }
    }

    // Unlike noSuchProperty's, this message carries what the accessor threw, which may quote a fact's value; the
    // engine escapes it when it logs it, as it does every message it didn't write.
    private static IllegalStateException readFailed(Object target, String property, Throwable cause) {
        return Accessors.readFailed("Reading '" + property + "' on a " + target.getClass().getName() + " failed",
                cause);
    }

    // The property name comes from the rule's text, and the message carries no fact value, so there is nothing here
    // that a run's data could forge a log line with.
    private static String noSuchProperty(Object target, String property) {
        return "A " + target.getClass().getName() + " has no property '" + property
                + "'. A fact's properties are a record's components, a bean's getters, or a map's keys.";
    }

    /**
     * Carries an element's own failure out of a collection's {@code forEach}, so it isn't taken for the collection's.
     */
    private static final class ElementFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final RuntimeException failure;

        // No stack trace: it's never seen, only unwrapped.
        private ElementFailed(RuntimeException failure) {
            super(null, failure, false, false);
            this.failure = failure;
        }
    }
}
