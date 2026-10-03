package io.github.brantunger.unruly;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.lang.runtime.ObjectMethods;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #996: a record's own {@code equals}, {@code hashCode} and {@code toString} each link a call site to
 * {@link ObjectMethods} when first called, and a first call deep in the stack can fail for good, or leave
 * {@code ObjectMethods} or the method-handle classes it needs unusable for the whole JVM, the application's own
 * records included. So the library's records write the three out. One check reads every class file of each project's
 * main classes, as {@link StringConcatenationTest} does, and fails if one names {@code ObjectMethods}, or if it finds
 * none to read. The other compares each record's three methods with those {@code ObjectMethods} makes for it, so they
 * stay the record's own: the same text, the same hash codes and the same equality, a component added later included.
 */
@DisplayName("record methods")
class RecordMethodsTest {

    private static final byte[] OBJECT_METHODS = "java/lang/runtime/ObjectMethods".getBytes(StandardCharsets.UTF_8);

    // The records whose equals is identity, as their own javadoc says: their methods aren't the record's on purpose.
    private static final Set<String> NOT_RECORD_METHODS = Set.of(
            "io.github.brantunger.unruly.core.EngineActionContext",
            "io.github.brantunger.unruly.core.EngineEvaluationContext");

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {RulesEngine.class, MvelExpressionLanguage.class, ExpressionLanguageContractTest.class})
    @DisplayName("the library's classes link no record method to ObjectMethods")
    void noObjectMethodsLinked(Class<?> type) throws IOException, URISyntaxException {
        Map<String, byte[]> classFiles = classFiles(type);
        List<String> linking = classFiles.entrySet().stream().filter(file -> contains(file.getValue(), OBJECT_METHODS))
                .map(Map.Entry::getKey).toList();

        assertFalse(classFiles.isEmpty(), "no class files read for " + type.getName());
        assertEquals(List.of(), linking, "classes that name ObjectMethods");
    }

    @Test
    @DisplayName("the records whose methods aren't the record's are still records of the library")
    void notRecordMethodsStillRecords() throws IOException, URISyntaxException {
        Set<String> records = new HashSet<>();
        for (Class<?> type : anchors()) {
            for (String name : classFiles(type).keySet()) {
                if (type(type, name).isRecord()) {
                    records.add(type(type, name).getName());
                }
            }
        }

        assertTrue(records.containsAll(NOT_RECORD_METHODS), "records found: " + records);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("records")
    @DisplayName("a record's equals, hashCode and toString are those ObjectMethods makes for it")
    void sameAsObjectMethods(Class<?> record) throws Throwable {
        RecordComponent[] components = record.getRecordComponents();
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(record, MethodHandles.lookup());
        MethodHandle[] getters = new MethodHandle[components.length];
        for (int i = 0; i < components.length; i++) {
            getters[i] = lookup.findGetter(record, components[i].getName(), components[i].getType());
        }
        String names = Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.joining(";"));
        MethodHandle equals = bootstrap(lookup, "equals", MethodType.methodType(boolean.class, record, Object.class),
                names, getters);
        MethodHandle hashCode = bootstrap(lookup, "hashCode", MethodType.methodType(int.class, record), names,
                getters);
        MethodHandle toString = bootstrap(lookup, "toString", MethodType.methodType(String.class, record), names,
                getters);

        Object[] first = new Object[components.length];
        Object[] second = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            first[i] = value(components[i].getGenericType(), 0, new HashSet<>());
            second[i] = value(components[i].getGenericType(), 1, new HashSet<>());
            assertNotEquals(first[i], second[i], "the values of " + components[i].getName());
        }
        Object one = construct(record, first);
        // The same components, not equal ones: a component whose equals is identity, such as an exception, would
        // otherwise differ.
        List<Object> others = new ArrayList<>(List.of(one, construct(record, first.clone()), construct(record, second),
                new Object()));
        others.add(null);
        for (int i = 0; i < components.length; i++) {
            Object[] changed = first.clone();
            // An equal value that isn't the same object, which == would tell from it.
            changed[i] = distinct(value(components[i].getGenericType(), 0, new HashSet<>()));
            others.add(construct(record, changed));
            changed[i] = second[i];
            Object other = construct(record, changed);
            assertFalse((boolean) equals.invoke(one, other), "a copy with another " + components[i].getName());
            others.add(other);
            if (!components[i].getType().isPrimitive()) {
                changed[i] = null;
                try {
                    others.add(construct(record, changed));
                } catch (InvocationTargetException e) {
                    // The record doesn't take null for it.
                }
            }
        }

        for (Object other : others) {
            assertEquals((boolean) equals.invoke(one, other), one.equals(other), "equals " + other);
            if (record.isInstance(other)) {
                assertEquals((int) hashCode.invoke(other), other.hashCode(), "hashCode of " + other);
                assertEquals((String) toString.invoke(other), other.toString());
                assertEquals((boolean) equals.invoke(other, one), other.equals(one), other + " equals");
            }
        }
    }

    /**
     * ObjectMethods' equals compares the components last first, and stops at the first that differs, so a component
     * whose equals throws is only reached if every component after it is equal. Each component that can be given such
     * a value gets one in both records, and each other component in turn differs; the outcome, a result or what was
     * thrown, must be the same. On JDK 26 ObjectMethods calls a component's equals even for the same object, which
     * Objects.equals doesn't, so the two records never share the throwing value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("records")
    @DisplayName("a record's equals compares the components in the order ObjectMethods does")
    void sameOrderAsObjectMethods(Class<?> record) throws Throwable {
        RecordComponent[] components = record.getRecordComponents();
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(record, MethodHandles.lookup());
        MethodHandle[] getters = new MethodHandle[components.length];
        for (int i = 0; i < components.length; i++) {
            getters[i] = lookup.findGetter(record, components[i].getName(), components[i].getType());
        }
        String names = Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.joining(";"));
        MethodHandle equals = bootstrap(lookup, "equals", MethodType.methodType(boolean.class, record, Object.class),
                names, getters);

        Object[] first = new Object[components.length];
        Object[] second = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            first[i] = value(components[i].getGenericType(), 0, new HashSet<>());
            second[i] = value(components[i].getGenericType(), 1, new HashSet<>());
        }
        for (int i = 0; i < components.length; i++) {
            for (int j = 0; j < components.length; j++) {
                Object[] left = first.clone();
                Object[] right = first.clone();
                left[i] = throwingEquals(components[i].getType());
                right[i] = throwingEquals(components[i].getType());
                if (left[i] == null || j == i) {
                    continue;
                }
                right[j] = second[j];
                Object one;
                Object other;
                try {
                    one = construct(record, left);
                    other = construct(record, right);
                } catch (InvocationTargetException e) {
                    // The record doesn't take the value.
                    continue;
                }
                assertEquals(outcome(() -> equals.invoke(one, other)), outcome(() -> one.equals(other)),
                        components[i].getName() + " throwing and " + components[j].getName() + " different");
            }
        }
    }

    static Stream<Class<?>> records() throws IOException, URISyntaxException {
        List<Class<?>> records = new ArrayList<>();
        for (Class<?> type : anchors()) {
            for (String name : classFiles(type).keySet()) {
                Class<?> found = type(type, name);
                if (found.isRecord() && !NOT_RECORD_METHODS.contains(found.getName())) {
                    records.add(found);
                }
            }
        }
        return records.stream().sorted(Comparator.comparing(Class::getName));
    }

    private static List<Class<?>> anchors() {
        return List.of(RulesEngine.class, MvelExpressionLanguage.class, ExpressionLanguageContractTest.class);
    }

    // Every class file of the directory or jar the class was loaded from, by binary name.
    private static Map<String, byte[]> classFiles(Class<?> type) throws IOException, URISyntaxException {
        Path classes = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, byte[]> read = new LinkedHashMap<>();
        if (Files.isDirectory(classes)) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    read.put(binaryName(classes.relativize(file).toString()), Files.readAllBytes(file));
                }
            }
        } else {
            try (ZipFile jar = new ZipFile(classes.toFile())) {
                for (ZipEntry entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        read.put(binaryName(entry.getName()), in.readAllBytes());
                    }
                }
            }
        }
        read.keySet().removeIf(name -> name.endsWith("module-info"));
        return read;
    }

    private static String binaryName(String file) {
        return file.substring(0, file.length() - ".class".length()).replace('\\', '.').replace('/', '.');
    }

    private static Class<?> type(Class<?> anchor, String name) {
        try {
            return Class.forName(name, false, anchor.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(name, e);
        }
    }

    private static MethodHandle bootstrap(MethodHandles.Lookup lookup, String method, MethodType type, String names,
                                          MethodHandle... getters) throws Throwable {
        return ((CallSite) ObjectMethods.bootstrap(lookup, method, type, lookup.lookupClass(), names, getters))
                .dynamicInvoker();
    }

    private static Object construct(Class<?> record, Object... components) throws ReflectiveOperationException {
        Constructor<?> canonical = record.getDeclaredConstructor(
                Arrays.stream(record.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new));
        canonical.setAccessible(true);
        return canonical.newInstance(components);
    }

    /**
     * Returns one of two different values of a type: {@code which} is 0 or 1. A long is 1, then {@code 1L << 32},
     * whose hash code a cast to int gets wrong. A float or double is NaN, then -0.0,
     * which {@code ==} gets wrong both ways; no record has one yet. A record is built from the values of its
     * components. A class with nothing simpler, or a record that doesn't take those, is {@code null}, then one built
     * with its first constructor that takes the second values, {@code null} for a class already being built.
     */
    private static Object value(Type type, int which, Set<Class<?>> building) throws ReflectiveOperationException {
        Class<?> raw = raw(type);
        if (raw == boolean.class) {
            return which == 1;
        } else if (raw == int.class) {
            return which + 1;
        } else if (raw == long.class) {
            // Long.hashCode(1L << 32) is 1, and (int) (1L << 32) is 0.
            return which == 0 ? 1L : 1L << 32;
        } else if (raw == float.class) {
            return which == 0 ? Float.NaN : -0.0f;
        } else if (raw == double.class) {
            return which == 0 ? Double.NaN : -0.0;
        } else if (raw == String.class) {
            return which == 0 ? "a" : "b";
        } else if (raw.isEnum()) {
            return raw.getEnumConstants()[which];
        } else if (raw == Class.class) {
            return which == 0 ? Object.class : String.class;
        } else if (raw == ClassLoader.class) {
            return which == 0 ? ClassLoader.getSystemClassLoader() : ClassLoader.getPlatformClassLoader();
        } else if (raw == Instant.class) {
            return Instant.ofEpochSecond(which);
        } else if (raw == Duration.class) {
            return Duration.ofSeconds(which + 1L);
        } else if (raw == Clock.class) {
            return Clock.fixed(Instant.ofEpochSecond(which), ZoneOffset.UTC);
        } else if (raw == java.lang.reflect.Method.class) {
            return Object.class.getMethod(which == 0 ? "toString" : "hashCode");
        } else if (raw == Rule.class) {
            return Rule.builder().ruleName(which == 0 ? "a" : "b").condition("true").action("x = 1").build();
        } else if (raw == List.class) {
            return which == 0 ? List.of() : List.of(value(argument(type, 0), 1, building));
        } else if (raw == Set.class) {
            return which == 0 ? Set.of() : Set.of(value(argument(type, 0), 1, building));
        } else if (raw == Map.class) {
            return which == 0 ? Map.of()
                    : Map.of(value(argument(type, 0), 1, building), value(argument(type, 1), 1, building));
        } else if (raw.isArray()) {
            return Array.newInstance(raw.getComponentType(), which);
        } else if (raw.isSealed()) {
            return value(raw.getPermittedSubclasses()[0], which, building);
        } else if (raw.isInterface()) {
            String name = raw.getSimpleName() + " " + which;
            return Proxy.newProxyInstance(raw.getClassLoader(), new Class<?>[] {raw}, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> name;
                        default -> null;
                    });
        }
        if (raw.isRecord()) {
            RecordComponent[] components = raw.getRecordComponents();
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = value(components[i].getGenericType(), which, building);
            }
            try {
                return construct(raw, values);
            } catch (InvocationTargetException e) {
                // It doesn't take these values, so it's built as another class is.
            }
        }
        if (which == 0 || !building.add(raw)) {
            return null;
        }
        List<Constructor<?>> constructors = Arrays.stream(raw.getDeclaredConstructors())
                .sorted(Comparator.comparingInt(Constructor::getParameterCount)).toList();
        for (Constructor<?> constructor : constructors) {
            Object[] arguments = new Object[constructor.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = value(constructor.getGenericParameterTypes()[i], 1, building);
            }
            constructor.setAccessible(true);
            try {
                return constructor.newInstance(arguments);
            } catch (InvocationTargetException e) {
                // Another constructor may take these values.
            }
        }
        throw new AssertionError("no value of " + type + "; give one in RecordMethodsTest.value");
    }

    // An equal value that isn't the same object, where the type has one: the samples are interned or shared.
    private static Object distinct(Object value) {
        return switch (value) {
            case String text -> new String(text.toCharArray());
            case List<?> list -> new ArrayList<>(list);
            case Set<?> set -> new HashSet<>(set);
            case Map<?, ?> map -> new HashMap<>(map);
            case null, default -> value;
        };
    }

    // A value of the type whose equals throws, or null if the test can't make one. A record's throws if one of its
    // components' does.
    private static Object throwingEquals(Class<?> type) throws ReflectiveOperationException {
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            for (int i = 0; i < components.length; i++) {
                Object throwing = throwingEquals(components[i].getType());
                if (throwing != null) {
                    Object[] values = new Object[components.length];
                    for (int k = 0; k < components.length; k++) {
                        values[k] = k == i ? throwing : value(components[k].getGenericType(), 0, new HashSet<>());
                    }
                    try {
                        return construct(type, values);
                    } catch (InvocationTargetException e) {
                        // The record doesn't take it: try another component.
                    }
                }
            }
            return null;
        }
        if (type.isInterface() && !type.isSealed()) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "equals" -> throw new EqualsCalled();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "throwing equals";
                        default -> null;
                    });
        }
        return type.isAssignableFrom(ThrowingError.class) ? new ThrowingError() : null;
    }

    private static Object outcome(Comparison comparison) {
        try {
            return comparison.equal();
        } catch (Throwable e) {
            return e.getClass();
        }
    }

    @FunctionalInterface
    private interface Comparison {
        Object equal() throws Throwable;
    }

    private static final class EqualsCalled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // An error whose equals throws, for a component that is an Error or a Throwable.
    private static final class ThrowingError extends Error {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean equals(Object other) {
            throw new EqualsCalled();
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private static Class<?> raw(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> raw(p.getRawType());
            case TypeVariable<?> v -> raw(v.getBounds()[0]);
            case WildcardType w -> raw(w.getUpperBounds()[0]);
            default -> throw new AssertionError("no raw type for " + type);
        };
    }

    private static Type argument(Type type, int index) {
        return type instanceof ParameterizedType p ? p.getActualTypeArguments()[index] : Object.class;
    }

    // Whether the class file holds the name, as the constant pool does for a call site ObjectMethods links.
    private static boolean contains(byte[] bytes, byte[] name) {
        for (int i = 0; i <= bytes.length - name.length; i++) {
            int at = 0;
            while (at < name.length && bytes[i + at] == name[at]) {
                at++;
            }
            if (at == name.length) {
                return true;
            }
        }
        return false;
    }
}
