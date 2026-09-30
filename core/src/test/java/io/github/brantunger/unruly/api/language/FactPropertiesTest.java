package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.hidden.HiddenFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a fact's properties are read the same way whether it's a record, a bean or a map")
class FactPropertiesTest {

    public record Applicant(int creditScore, String name, Address address) {
    }

    public record Address(String city) {
    }

    /** A bean, whose properties come from its getters. */
    public static class Loan {

        public int getAmount() {
            return 25_000;
        }

        public boolean isApproved() {
            return true;
        }

        /** Not a property: it returns a String, so it isn't a boolean getter. */
        public String isNotAProperty() {
            return "no";
        }

        /** Not a property: it takes an argument. */
        public int getScaled(int factor) {
            return factor;
        }

        /** Not a property: static. */
        public static int getStatic() {
            return 1;
        }

        /** A property whose second letter is upper case keeps its case, as java.beans does. */
        public String getURL() {
            return "https://example.com";
        }

        /** A boxed boolean getter, which is a property just as a primitive one is. */
        public Boolean isBoxed() {
            return Boolean.TRUE;
        }

        /** A one-letter property, whose name is lower-cased. */
        public int getX() {
            return 1;
        }

        /** Not a property: it returns nothing. */
        public void getNothing() {
            // Nothing to do: it exists to show a void method isn't a getter.
        }

        /** Not a property: "get" with no name after it. */
        public String get() {
            return "bare";
        }

        /** Not a property: "is" with no name after it. */
        public boolean is() {
            return true;
        }

        /** Not a property: a boolean getter that doesn't start with is or get. */
        public boolean hasSomething() {
            return true;
        }
    }

    /** A bean declaring both accessors for one property, which reflection reports in no particular order. */
    public static class TwoAccessors {

        public boolean isActive() {
            return true;
        }

        public Boolean getActive() {
            return false;
        }
    }

    /** A record that computes a property its components don't hold. */
    public record Order(int quantity, int unitPrice) {

        public int getTotal() {
            return quantity * unitPrice;
        }
    }

    /** A record with a getter named after one of its components, which the component wins over. */
    public record Boxed(int quantity) {

        public int getQuantity() {
            return quantity * 100;
        }
    }

    /** A fact that holds a lambda, which is an implementation rather than data. */
    public record Holder(IntSupplier score) {
    }

    /** A collection with getters of its own, which still isn't a thing with properties. */
    public static class LineItems extends ArrayList<String> {

        @java.io.Serial
        private static final long serialVersionUID = 1L;
    }

    /** A class outside the platform packages with no getters at all, so it's a value rather than data. */
    public static class Opaque {

        @Override
        public String toString() {
            return "opaque";
        }
    }



    /** A bean whose getter fails. */
    public static class Broken {

        public int getBoom() {
            throw new IllegalStateException("boom");
        }

        public int getChecked() throws Exception {
            throw new Exception("checked");
        }

        public int getValidated() {
            throw new IllegalArgumentException("amount not set");
        }
    }

    /**
     * A lazily loaded map whose backend fails, so its lookups and its iteration throw, a checked exception too,
     * which the map doesn't declare.
     */
    static final class FailingMap extends AbstractMap<String, Object> {

        private final Exception failure;
        private final boolean failsOnContainsKey;

        FailingMap(Exception failure, boolean failsOnContainsKey) {
            this.failure = failure;
            this.failsOnContainsKey = failsOnContainsKey;
        }

        @Override
        public boolean containsKey(Object key) {
            if (failsOnContainsKey) {
                throw undeclared(failure);
            }
            return true;
        }

        @Override
        public Object get(Object key) {
            throw undeclared(failure);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            throw undeclared(failure);
        }
    }

    /**
     * Throws any exception without declaring it, as code compiled from another JVM language can throw a checked one.
     *
     * @param failure What to throw
     * @param <E>     What the compiler takes it for
     * @return Nothing: it always throws, so a caller can write {@code throw undeclared(failure)}
     * @throws E {@code failure}, whatever it is
     */
    @SuppressWarnings("unchecked")
    static <E extends Exception> RuntimeException undeclared(Exception failure) throws E {
        throw (E) failure;
    }

    /** A map key whose {@code toString()} throws. */
    static final class BadKey {

        @Override
        public String toString() {
            throw new IllegalArgumentException("no name");
        }
    }

    /**
     * A lazily loaded collection over a cursor, read only through {@code forEach}: its iterator isn't there. Its own
     * calls may throw, a checked exception too: its {@code size()}, or its {@code forEach} before the first line or
     * after it.
     */
    static final class Cursor extends AbstractCollection<Object> {

        private final Exception failure;
        private final String failsOn;

        Cursor(Exception failure, String failsOn) {
            this.failure = failure;
            this.failsOn = failsOn;
        }

        @Override
        public int size() {
            if ("size".equals(failsOn)) {
                throw undeclared(failure);
            }
            return 2;
        }

        @Override
        public void forEach(Consumer<? super Object> action) {
            if ("forEach".equals(failsOn)) {
                throw undeclared(failure);
            }
            action.accept("line 1");
            if ("forEach after a line".equals(failsOn)) {
                throw undeclared(failure);
            }
            action.accept("line 2");
        }

        @Override
        public Iterator<Object> iterator() {
            throw new UnsupportedOperationException("a cursor is read with forEach");
        }
    }

    /** A collection whose {@code forEach} runs in parallel, on the common pool's threads as well as the caller's. */
    static final class ParallelLines extends AbstractCollection<Object> {

        private final List<Object> lines;

        ParallelLines(List<Object> lines) {
            this.lines = lines;
        }

        @Override
        public void forEach(Consumer<? super Object> action) {
            lines.parallelStream().forEach(action);
        }

        @Override
        public Iterator<Object> iterator() {
            return lines.iterator();
        }

        @Override
        public int size() {
            return lines.size();
        }
    }

    /** A collection whose {@code forEach} wraps whatever the action throws in an exception of its own. */
    static final class Wrapping extends AbstractCollection<Object> {

        private final List<Object> lines;

        Wrapping(List<Object> lines) {
            this.lines = lines;
        }

        @Override
        public void forEach(Consumer<? super Object> action) {
            try {
                lines.forEach(action);
            } catch (RuntimeException e) {
                throw new IllegalStateException("iteration failed", e);
            }
        }

        @Override
        public Iterator<Object> iterator() {
            return lines.iterator();
        }

        @Override
        public int size() {
            return lines.size();
        }
    }

    /** An exception whose causes never end: each is a new one. */
    static final class EndlessCause extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        EndlessCause() {
            super("cursor lost");
        }

        @Override
        public synchronized Throwable getCause() {
            return new EndlessCause();
        }
    }

    /** An exception whose {@code getCause()} throws: an unchecked exception, a checked one undeclared, or an error. */
    static final class UnreadableCause extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final transient Throwable thrown;

        UnreadableCause(Throwable thrown) {
            super("cursor lost");
            this.thrown = thrown;
        }

        @Override
        public synchronized Throwable getCause() {
            if (thrown instanceof Error error) {
                throw error;
            }
            throw undeclared((Exception) thrown);
        }
    }

    /** A bean whose getter runs out of memory. */
    public static final class Exhausted {

        private final OutOfMemoryError error;

        Exhausted(OutOfMemoryError error) {
            this.error = error;
        }

        public int getMemory() {
            throw error;
        }
    }

    /** A bean that tells whether the thread reading it holds a lock. */
    public static final class LockProbe {

        private final Object lock;

        LockProbe(Object lock) {
            this.lock = lock;
        }

        public boolean isLocked() {
            return Thread.holdsLock(lock);
        }
    }

    /** A map that has the key, and computes its value from a source that fails. */
    static final class LazyMap extends AbstractMap<String, Object> {

        private final RuntimeException failure;

        LazyMap(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public boolean containsKey(Object key) {
            return "total".equals(key);
        }

        @Override
        public Object get(Object key) {
            throw failure;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return Set.of();
        }
    }

    /** An exception whose {@code getMessage()} throws, as one built from a field that is {@code null} does. */
    static final class UnreadableMessage extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new NullPointerException("no detail");
        }
    }

    public enum Status {
        /** A constant with a body, which the compiler makes an anonymous subclass of the enum. */
        OPEN {
            @Override
            public String toString() {
                return "open";
            }
        },
        CLOSED
    }

    /** An enum with a getter of its own. */
    public enum Tier {
        GOLD;

        public String getLabel() {
            return "Gold";
        }
    }

    /** A fact whose own property holds a class. */
    public record Plugin(Class<?> type) {
    }

    /** An application's own class loader, which has getters of its own through URLClassLoader. */
    public static class AppLoader extends URLClassLoader {
        public AppLoader() {
            super(new URL[0], FactPropertiesTest.class.getClassLoader());
        }
    }

    /** An application's own thread, with a getter it declares itself. */
    public static class Worker extends Thread {
        public int getJobs() {
            return 3;
        }
    }

    /** A fact that holds any object. */
    public record Carrier(Object value) {
    }

    /** Two objects that hold each other, so a conversion would never end without a depth limit. */
    public static class Loop {

        private final String name;
        private Loop other;

        Loop(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public Loop getOther() {
            return other;
        }

        void link(Loop link) {
            this.other = link;
        }
    }

    @Test
    @DisplayName("a record's component, a bean's getter and a map's key are all read by name")
    void readsEveryShape() {
        assertEquals(750, FactProperties.read(new Applicant(750, "Alex", new Address("Leeds")), "creditScore"));
        assertEquals(25_000, FactProperties.read(new Loan(), "amount"));
        assertEquals(true, FactProperties.read(new Loan(), "approved"));
        assertEquals("https://example.com", FactProperties.read(new Loan(), "URL"));
        assertEquals(750, FactProperties.read(Map.of("creditScore", 750), "creditScore"));
    }

    @Test
    @DisplayName("a map key whose value is null is read; a key that isn't there fails")
    void nullValueAndMissingKey() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("coapplicant", null);

        assertNull(FactProperties.read(facts, "coapplicant"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> FactProperties.read(facts, "applicant"));
        assertTrue(thrown.getMessage().contains("'applicant'"), thrown.getMessage());
    }

    @Test
    @DisplayName("a property the fact doesn't have fails, naming the property and the class")
    void missingProperty() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> FactProperties.read(new Applicant(750, "Alex", new Address("Leeds")), "creditScor"));

        assertTrue(thrown.getMessage().contains("'creditScor'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Applicant.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("getClass, a getter with arguments, a static getter and a non-boolean isX aren't properties")
    void whatIsntAProperty() {
        for (String notAProperty : List.of("class", "scaled", "static", "notAProperty")) {
            assertThrows(IllegalArgumentException.class, () -> FactProperties.read(new Loan(), notAProperty),
                    notAProperty);
        }
    }

    @Test
    @DisplayName("what a getter throws is always wrapped as its cause, so it's never mistaken for a missing property")
    void anAccessorThatThrows() {
        IllegalStateException unchecked = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new Broken(), "boom"));
        assertEquals("boom", unchecked.getCause().getMessage());
        assertTrue(unchecked.getMessage().contains("Reading 'boom'"), unchecked.getMessage());

        // A getter that rejects its own state must not read as "the fact has no such property", which is what an
        // IllegalArgumentException from here means.
        IllegalStateException validating = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new Broken(), "validated"));
        assertInstanceOf(IllegalArgumentException.class, validating.getCause());

        IllegalStateException checked = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new Broken(), "checked"));
        assertEquals("checked", checked.getCause().getMessage());
    }

    @Test
    @DisplayName("a null fact or property is rejected")
    void nullArguments() {
        assertThrows(NullPointerException.class, () -> FactProperties.read(null, "x"));
        assertThrows(NullPointerException.class, () -> FactProperties.read(new Loan(), null));
        assertThrows(NullPointerException.class, () -> FactProperties.toData(null, 1));
    }

    @Test
    @DisplayName("toData at depth 1 makes one map, leaving the values as they are")
    void oneLevel() {
        Address address = new Address("Leeds");

        Map<String, Object> data = FactProperties.toData(new Applicant(750, "Alex", address), 1);

        assertEquals(List.of("creditScore", "name", "address"), List.copyOf(data.keySet()));
        assertEquals(750, data.get("creditScore"));
        assertSame(address, data.get("address"));
        // A new modifiable map, as the method promises: the caller may add to what it was given, and a second
        // conversion isn't affected by that.
        data.put("nickname", "Al");
        assertFalse(FactProperties.toData(new Applicant(750, "Alex", address), 1).containsKey("nickname"));
    }

    @Test
    @DisplayName("a record's component is read, not a getter of the same name")
    void aComponentWinsOverAGetterOfTheSameName() {
        assertEquals(2, FactProperties.read(new Boxed(2), "quantity"));
        assertEquals(Map.of("quantity", 2), FactProperties.toData(new Boxed(2), 1));
    }

    @Test
    @DisplayName("a value two sibling properties hold is converted in both of them")
    void aValueOnTwoSiblingPaths() {
        Address shared = new Address("Oslo");
        Map<String, Object> places = new LinkedHashMap<>();
        places.put("home", shared);
        places.put("work", shared);

        Map<String, Object> data = FactProperties.toData(places, 2);

        assertEquals(Map.of("city", "Oslo"), data.get("home"));
        // The first path is finished before the second starts, so the value isn't still on the path that would
        // leave it as it is: only a value that holds itself, further up the same path, is.
        assertEquals(Map.of("city", "Oslo"), data.get("work"));
    }

    @Test
    @DisplayName("a deeper conversion turns nested records, collections, arrays and maps into data too")
    void deeperLevels() {
        Map<String, Object> data = FactProperties.toData(new Applicant(750, "Alex", new Address("Leeds")), 2);

        assertEquals(Map.of("city", "Leeds"), data.get("address"));

        Map<Object, Object> nested = new LinkedHashMap<>();
        nested.put("addresses", List.of(new Address("Leeds")));
        nested.put("more", new Address[]{new Address("York")});
        nested.put(7, "a key that isn't a string");

        // Three levels: the map, then the list, then the record inside it.
        Map<String, Object> converted = FactProperties.toData(nested, 3);

        assertEquals(List.of(Map.of("city", "Leeds")), converted.get("addresses"));
        assertEquals(List.of(Map.of("city", "York")), converted.get("more"));
        assertEquals("a key that isn't a string", converted.get("7"));

        // Two levels reach the list but not the records in it, because the list is a level of its own.
        Map<String, Object> shallow = FactProperties.toData(nested, 2);
        assertEquals(List.of(new Address("Leeds")), shallow.get("addresses"));
    }

    @Test
    @DisplayName("a collection that holds itself still ends, because it's left as it is when met again on its own path")
    void aSelfReferencingCollection() {
        List<Object> loop = new ArrayList<>();
        loop.add(loop);

        Map<String, Object> data = FactProperties.toData(Map.of("loop", loop), 4);

        assertSame(loop, ((List<?>) data.get("loop")).get(0));
    }

    @Test
    @DisplayName("a public class in a package its module hides is read through a type that is exported")
    void aPublicClassInAHiddenPackage() {
        // TimeZone.getDefault() is a sun.util.calendar.ZoneInfo: public, but its package isn't exported, so its own
        // getRawOffset() can't be invoked from here. java.util.TimeZone declares the same accessor and is exported.
        TimeZone zone = TimeZone.getDefault();

        assertEquals(zone.getRawOffset(), FactProperties.read(zone, "rawOffset"));
    }

    @Test
    @DisplayName("the platform's own values are left alone however their implementation is named")
    void platformValuesArentTakenApart() {
        Path path = Path.of("x");
        Charset charset = StandardCharsets.UTF_8;
        Timestamp timestamp = new Timestamp(0L);

        // None of these classes is called java.*: Path.of returns a sun.nio.fs.WindowsPath, UTF_8 is a sun.nio.cs
        // class, and Timestamp comes from the platform class loader rather than the boot one.
        Map<String, Object> data = FactProperties.toData(
                Map.of("path", path, "charset", charset, "stamp", timestamp), 5);

        assertSame(path, data.get("path"));
        assertSame(charset, data.get("charset"));
        assertSame(timestamp, data.get("stamp"));
    }

    @Test
    @DisplayName("a proxy over an interface is data, and an enum constant with a body is still a value")
    void proxiesAreDataAndEnumsArent() {
        Object proxy = Proxy.newProxyInstance(HiddenFacts.class.getClassLoader(),
                new Class<?>[]{HiddenFacts.Named.class}, (self, method, args) -> "proxied");

        // A proxy is defined in a jdk.proxyN package, which the platform-package rule would otherwise exclude.
        assertEquals("proxied", FactProperties.read(proxy, "name"));
        assertEquals(Map.of("name", "proxied"), FactProperties.toData(proxy, 1));

        // Class.isEnum() is false for a constant with a body, so both constants need the instanceof rule.
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(Status.OPEN, 1));
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(Status.CLOSED, 1));
        Map<String, Object> data = FactProperties.toData(Map.of("a", Status.OPEN, "b", Status.CLOSED), 3);
        assertSame(Status.OPEN, data.get("a"));
        assertSame(Status.CLOSED, data.get("b"));
    }

    @Test
    @DisplayName("an enum's declaring class isn't a property, so an enum fact doesn't lead to its class")
    void enumDeclaringClassIsntAProperty() {
        for (Status status : Status.values()) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> FactProperties.read(status, "declaringClass"));
            assertEquals("A " + status.getClass().getName() + " has no property 'declaringClass'. A fact's properties"
                    + " are a record's components, a bean's getters, or a map's keys.", thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a class, a class loader, a module, a thread, or a reflection or security object isn't read")
    void reflectionAndRuntimeObjectsArentRead() throws NoSuchMethodException {
        Map<Object, String> refused = new LinkedHashMap<>();
        refused.put(String.class, "name");
        refused.put(FactPropertiesTest.class.getClassLoader(), "parent");
        refused.put(String.class.getModule(), "name");
        refused.put(ModuleLayer.boot(), "configuration");
        refused.put(String.class.getPackage(), "name");
        refused.put(FactPropertiesTest.class.getProtectionDomain(), "codeSource");
        refused.put(FactPropertiesTest.class.getProtectionDomain().getCodeSource(), "location");
        refused.put(Object.class.getMethod("toString"), "name");
        refused.put(Thread.currentThread(), "name");

        refused.forEach((target, property) -> {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> FactProperties.read(target, property), target.getClass().getName());
            assertEquals("A " + target.getClass().getName() + " has no property '" + property + "'. The properties"
                    + " of a class, a class loader, a module, a package, a thread, or a reflection or security object"
                    + " aren't read.", thrown.getMessage());
        });
    }

    @Test
    @DisplayName("an application's own class loader or thread has no properties, so toData leaves it as it is too")
    void applicationRuntimeSubclassesArentTakenApart() throws IOException {
        try (AppLoader loader = new AppLoader()) {
            Worker worker = new Worker();
            worker.setContextClassLoader(loader);

            for (Object runtime : List.of(loader, worker)) {
                String property = runtime == loader ? "parent" : "jobs";
                IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                        () -> FactProperties.read(runtime, property));
                assertEquals("A " + runtime.getClass().getName() + " has no property '" + property + "'. The"
                        + " properties of a class, a class loader, a module, a package, a thread, or a reflection or"
                        + " security object aren't read.", thrown.getMessage());
                assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(runtime, 2));
                assertSame(runtime, FactProperties.toData(new Carrier(runtime), 3).get("value"));
            }
        }
    }

    @Test
    @DisplayName("a fact's own property may hold a class, and the platform's values and an enum's getters still read")
    void ordinaryValuesStillRead() {
        assertSame(String.class, FactProperties.read(new Plugin(String.class), "type"));
        assertSame(String.class, FactProperties.toData(new Plugin(String.class), 5).get("type"));
        assertEquals(2026, FactProperties.read(LocalDate.of(2026, 9, 16), "year"));
        assertEquals(false, FactProperties.read("abc", "empty"));
        assertEquals(90L, FactProperties.read(java.time.Duration.ofSeconds(90), "seconds"));
        assertEquals("Gold", FactProperties.read(Tier.GOLD, "label"));
        assertSame(Tier.GOLD, FactProperties.toData(Map.of("tier", Tier.GOLD), 3).get("tier"));
    }

    @Test
    @DisplayName("a map inside a map is converted too, with its keys kept")
    void aNestedMap() {
        Map<String, Object> facts = Map.of("address", Map.of("city", "Leeds"));

        assertEquals(Map.of("address", Map.of("city", "Leeds")), FactProperties.toData(facts, 2));
        // One level short: the nested map is the value, as it was given.
        assertSame(facts.get("address"), FactProperties.toData(facts, 1).get("address"));
    }

    @Test
    @DisplayName("library values and enums are left alone, so a String isn't taken apart")
    void leavesValuesAlone() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("text", "Alex");
        values.put("date", LocalDate.of(2026, 9, 16));
        values.put("status", Status.OPEN);
        values.put("maybe", Optional.of("x"));
        values.put("number", 5);

        Map<String, Object> data = FactProperties.toData(values, 5);

        assertEquals(values, data);
    }

    @Test
    @DisplayName("a cycle between two facts stops where it comes back to a fact already being converted")
    void cycleStopsWhereItComesBack() {
        Loop first = new Loop("first");
        Loop second = new Loop("second");
        first.link(second);
        second.link(first);

        Map<String, Object> data = FactProperties.toData(first, 5);

        assertEquals("first", data.get("name"));
        Map<?, ?> other = (Map<?, ?>) data.get("other");
        assertEquals("second", other.get("name"));
        // Back at a value already being converted higher up this path, so it's left as it is rather than unrolled
        // again: that's what keeps a graph that points back at itself from costing more the deeper it's converted.
        assertSame(first, other.get("other"));
    }

    @Test
    @DisplayName("a depth below 1 or above 20, and a value with no properties, are rejected")
    void rejectedConversions() {
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(new Loan(), 0));
        assertDoesNotThrow(() -> FactProperties.toData(new Loan(), 20), "20 levels are documented as the most");
        assertEquals("depth must be between 1 and 20, but was 21",
                assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(new Loan(), 21))
                        .getMessage());
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData("Alex", 1));
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(Status.OPEN, 1));
    }

    @Test
    @DisplayName("a one-letter property is lower-cased, and get(), is(), void and hasX aren't properties")
    void getterNamingEdges() {
        assertEquals(1, FactProperties.read(new Loan(), "x"));
        assertEquals(Boolean.TRUE, FactProperties.read(new Loan(), "boxed"));
        for (String notAProperty : List.of("", "nothing", "something")) {
            assertThrows(IllegalArgumentException.class, () -> FactProperties.read(new Loan(), notAProperty),
                    notAProperty);
        }
    }

    @Test
    @DisplayName("a class with no getters is a value, and converting one is rejected")
    void aClassWithNoGetters() {
        Opaque opaque = new Opaque();

        assertSame(opaque, FactProperties.toData(Map.of("thing", opaque), 3).get("thing"));
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(opaque, 1));
    }

    @Test
    @DisplayName("a getter nothing public declares is read directly on the class path, where every package is open")
    void aGetterNothingPublicDeclares() {
        assertEquals(1, FactProperties.read(HiddenFacts.withASecret(), "secret"));
        assertEquals(Map.of("secret", 1), FactProperties.toData(HiddenFacts.withASecret(), 1));
    }

    @Test
    @DisplayName("a record that isn't public and implements no interface is read directly on the class path")
    void aHiddenRecordWithNoInterface() {
        assertEquals(7, FactProperties.read(HiddenFacts.bareRecord(), "score"));
        assertEquals(Map.of("score", 7), FactProperties.toData(HiddenFacts.bareRecord(), 1));
    }

    @Test
    @DisplayName("a fact whose class isn't public is read through the public interface that declares the accessor")
    void aGetterOnAPublicSupertype() {
        assertEquals("hidden", FactProperties.read(HiddenFacts.named(), "name"));
        assertEquals("anonymous", FactProperties.read(HiddenFacts.anonymousName(), "name"));
    }

    @Test
    @DisplayName("a record that isn't public is read through the public interface it implements, as a bean is")
    void aHiddenRecordWithAPublicInterface() {
        assertEquals("record", FactProperties.read(HiddenFacts.namedRecord(), "name"));
        assertEquals(Map.of("name", "record"), FactProperties.toData(HiddenFacts.namedRecord(), 1));
    }

    @Test
    @DisplayName("a public interface is found through one that isn't public, and reached only once")
    void publicInterfaceBehindAHiddenOne() {
        assertEquals("through an inner interface",
                FactProperties.read(HiddenFacts.namedThroughAnInnerInterface(), "name"));
        assertEquals("twice", FactProperties.read(HiddenFacts.namedTwice(), "name"));
    }

    @Test
    @DisplayName("an accessor a public interface only inherits from a hidden one is read directly on the class path")
    void aPublicInterfaceThatOnlyInheritsTheAccessor() {
        assertEquals("boxed", FactProperties.read(HiddenFacts.boxed(), "boxed"));
    }

    @Test
    @DisplayName("a map that refuses a string key has no such property, rather than failing its own way")
    void aMapThatRejectsTheKey() {
        Map<Integer, String> byNumber = new TreeMap<>();
        byNumber.put(1, "one");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> FactProperties.read(byNumber, "creditScore"));

        assertTrue(thrown.getMessage().contains("has no property 'creditScore'"), thrown.getMessage());
        assertInstanceOf(ClassCastException.class, thrown.getCause());
    }

    @Test
    @DisplayName("what a map fact's containsKey or get throws is wrapped, never read as a missing property")
    void aMapThatThrows() {
        IllegalArgumentException backendDown = new IllegalArgumentException("backend down");

        IllegalStateException onGet = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(backendDown, false), "score"));
        assertSame(backendDown, onGet.getCause());
        assertTrue(onGet.getMessage().contains("Reading 'score' on a " + FailingMap.class.getName()),
                onGet.getMessage());

        IllegalStateException onContainsKey = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(backendDown, true), "score"));
        assertSame(backendDown, onContainsKey.getCause());

        // An IllegalStateException gets the context too, rather than escaping as it came.
        IllegalStateException unavailable = new IllegalStateException("unavailable");
        IllegalStateException wrapped = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(unavailable, false), "score"));
        assertSame(unavailable, wrapped.getCause());
    }

    @Test
    @DisplayName("what a map's iteration or a key's toString() throws in toData is wrapped, and a nested failure once")
    void aMapThatThrowsWhileConverting() {
        IllegalArgumentException backendDown = new IllegalArgumentException("backend down");
        IllegalStateException onIteration = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new FailingMap(backendDown, false), 1));
        assertSame(backendDown, onIteration.getCause());
        assertTrue(onIteration.getMessage().contains(FailingMap.class.getName()), onIteration.getMessage());

        Map<Object, Object> badKey = new LinkedHashMap<>();
        badKey.put(new BadKey(), 1);
        IllegalStateException onKey = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(badKey, 1));
        assertEquals("no name", onKey.getCause().getMessage());

        // A nested bean's getter is wrapped by the read of that getter, not again by the map holding it.
        IllegalStateException nested = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(Map.of("broken", new Broken()), 2));
        assertTrue(nested.getMessage().contains("Reading 'boom'"), nested.getMessage());
        assertEquals("boom", nested.getCause().getMessage());
    }

    @Test
    @DisplayName("what an accessor threw is named in the message after a colon, so the reason isn't only in the trace")
    void anAccessorsMessageIsKept() {
        IllegalStateException getter = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new Broken(), "boom"));
        assertEquals("Reading 'boom' on a " + Broken.class.getName() + " failed: boom", getter.getMessage());

        IllegalStateException converted = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new Broken(), 1));
        assertEquals("Reading 'boom' on a " + Broken.class.getName() + " failed: boom", converted.getMessage());

        IllegalArgumentException backendDown = new IllegalArgumentException("backend down");
        IllegalStateException onGet = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(backendDown, false), "score"));
        assertEquals("Reading 'score' on a " + FailingMap.class.getName() + " failed: backend down",
                onGet.getMessage());
        IllegalStateException onContainsKey = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(backendDown, true), "score"));
        assertEquals("Reading 'score' on a " + FailingMap.class.getName() + " failed: backend down",
                onContainsKey.getMessage());
        IllegalStateException onEntries = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new FailingMap(backendDown, false), 1));
        assertEquals("Reading the entries of a " + FailingMap.class.getName() + " failed: backend down",
                onEntries.getMessage());
    }

    @Test
    @DisplayName("what an accessor threw is kept whole, for the engine to shorten, and left out when it has none")
    void anAccessorsMessageIsKeptWholeOrLeftOut() {
        String what = "Reading 'score' on a " + FailingMap.class.getName() + " failed";
        IllegalStateException longMessage = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(new IllegalStateException("x".repeat(1_100)), false),
                        "score"));
        assertEquals(what + ": " + "x".repeat(1_100), longMessage.getMessage());

        IllegalStateException noMessage = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(new IllegalStateException(), false), "score"));
        assertEquals(what, noMessage.getMessage());

        UnreadableMessage unreadable = new UnreadableMessage();
        IllegalStateException unreadableMessage = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(unreadable, false), "score"));
        assertEquals(what, unreadableMessage.getMessage());
        assertSame(unreadable, unreadableMessage.getCause());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"size", "forEach", "forEach after a line"})
    @DisplayName("what a collection's own calls throw in toData is wrapped, never mistaken for a bad argument")
    void aCollectionThatThrowsWhileConverting(String failsOn) {
        IllegalArgumentException closed = new IllegalArgumentException("cursor is closed");
        Cursor lines = new Cursor(closed, failsOn);

        IllegalStateException inRecord = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new Carrier(lines), 2));
        assertSame(closed, inRecord.getCause());
        assertEquals("Reading the elements of a " + Cursor.class.getName() + " failed: cursor is closed",
                inRecord.getMessage());

        IllegalStateException inMap = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(Map.of("lines", lines), 2));
        assertSame(closed, inMap.getCause());
    }

    @Test
    @DisplayName("a collection is read through its forEach, so one that overrides only forEach is read the way it says")
    void aCollectionIsReadThroughItsForEach() {
        Map<String, Object> data = FactProperties.toData(new Carrier(new Cursor(null, "nothing")), 2);

        assertEquals(List.of("line 1", "line 2"), data.get("value"));
    }

    @Test
    @DisplayName("a synchronized collection is converted under its lock, as its forEach holds it")
    void aSynchronizedCollectionIsConvertedUnderItsLock() {
        List<Object> probes = new ArrayList<>();
        List<Object> synchronizedProbes = Collections.synchronizedList(probes);
        probes.add(new LockProbe(synchronizedProbes));

        Map<String, Object> data = FactProperties.toData(new Carrier(synchronizedProbes), 3);

        assertEquals(List.of(Map.of("locked", true)), data.get("value"));
    }

    @Test
    @DisplayName("an element's own failure in toData is wrapped by the read that failed, not again by its collection")
    void aCollectionsElementThatThrows() {
        IllegalStateException nested = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new Carrier(List.of(new Broken())), 3));

        assertEquals("Reading 'boom' on a " + Broken.class.getName() + " failed: boom", nested.getMessage());
        assertEquals("boom", nested.getCause().getMessage());
        assertNull(nested.getCause().getCause());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a failing collection", "a failing map"})
    @DisplayName("a collection or a map inside a collection that fails is wrapped once, by the one that failed")
    void aFailingContainerInsideACollection(String inside) {
        IllegalArgumentException closed = new IllegalArgumentException("cursor is closed");
        boolean collection = "a failing collection".equals(inside);
        Object failing = collection ? new Cursor(closed, "forEach") : new FailingMap(closed, false);

        IllegalStateException wrapped = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new Carrier(List.of(List.of(failing))), 4));

        assertSame(closed, wrapped.getCause());
        assertEquals("Reading the " + (collection ? "elements" : "entries") + " of a " + failing.getClass().getName()
                + " failed: cursor is closed", wrapped.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"in parallel", "wrapping what it's given"})
    @DisplayName("an element's failure out of a forEach that runs in parallel, or wraps it, is still the element's")
    void anElementsFailureThroughAnotherForEach(String how) {
        List<Object> broken = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            broken.add(new Broken());
        }
        Collection<Object> elements = "in parallel".equals(how) ? new ParallelLines(broken) : new Wrapping(broken);

        IllegalStateException nested = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new Carrier(elements), 3));

        assertEquals("Reading 'boom' on a " + Broken.class.getName() + " failed: boom", nested.getMessage());
        assertEquals("boom", nested.getCause().getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"endless", "unchecked", "checked", "an error"})
    @DisplayName("a forEach failure whose causes never end, or whose getCause() throws, is the collection's own")
    void aForEachFailureWithCausesThatCantBeRead(String causes) {
        IllegalStateException failure = switch (causes) {
            case "endless" -> new EndlessCause();
            case "unchecked" -> new UnreadableCause(new IllegalStateException("no cause"));
            case "checked" -> new UnreadableCause(new IOException("no cause"));
            default -> new UnreadableCause(new StackOverflowError("no cause"));
        };

        IllegalStateException wrapped = assertThrows(IllegalStateException.class,
                () -> FactProperties.toData(new Carrier(new Cursor(failure, "forEach")), 2));

        assertSame(failure, wrapped.getCause());
        assertEquals("Reading the elements of a " + Cursor.class.getName() + " failed: cursor lost",
                wrapped.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"map containsKey", "map get", "map entries", "collection size", "collection forEach"})
    @DisplayName("a checked exception a map or a collection throws without declaring it is wrapped too")
    void anUndeclaredCheckedException(String where) {
        IOException disk = new IOException("disk gone");

        IllegalStateException wrapped = assertThrows(IllegalStateException.class, () -> {
            switch (where) {
                case "map containsKey" -> FactProperties.read(new FailingMap(disk, true), "score");
                case "map get" -> FactProperties.read(new FailingMap(disk, false), "score");
                case "map entries" -> FactProperties.toData(new FailingMap(disk, false), 1);
                case "collection size" -> FactProperties.toData(new Carrier(new Cursor(disk, "size")), 2);
                default -> FactProperties.toData(new Carrier(new Cursor(disk, "forEach")), 2);
            }
        });

        assertSame(disk, wrapped.getCause());
        assertTrue(wrapped.getMessage().endsWith(" failed: disk gone"), wrapped.getMessage());
    }

    @Test
    @DisplayName("a fatal error an accessor throws keeps its message, which names what ran out")
    void aFatalErrorKeepsItsMessage() {
        IllegalStateException wrapped = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new Exhausted(new OutOfMemoryError("Metaspace")), "memory"));
        assertEquals("Reading 'memory' on a " + Exhausted.class.getName() + " failed: Metaspace", wrapped.getMessage());
        assertInstanceOf(OutOfMemoryError.class, wrapped.getCause());

        IllegalStateException unreadable = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new Exhausted(new OutOfMemoryError() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String getMessage() {
                        throw new IllegalStateException("no message");
                    }
                }), "memory"));
        assertEquals("Reading 'memory' on a " + Exhausted.class.getName() + " failed", unreadable.getMessage());

        // Wrapped in an exception with no words of its own, it's read as any other cause is.
        IllegalStateException wrappedError = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new FailingMap(new IllegalStateException(new OutOfMemoryError("Metaspace")),
                        false), "score"));
        assertEquals("Reading 'score' on a " + FailingMap.class.getName() + " failed: java.lang.OutOfMemoryError: "
                + "Metaspace", wrappedError.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NullPointerException", "ClassCastException"})
    @DisplayName("a map whose get() fails for a key its containsKey() found fails its own way, not as a missing key")
    void aMapWhoseGetFailsForAKeyItHas(String thrown) {
        RuntimeException failure = "NullPointerException".equals(thrown)
                ? new NullPointerException("price source is null")
                : new ClassCastException("price source is a String");

        IllegalStateException wrapped = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(new LazyMap(failure), "total"));

        assertSame(failure, wrapped.getCause());
        assertEquals("Reading 'total' on a " + LazyMap.class.getName() + " failed: " + failure.getMessage(),
                wrapped.getMessage());
        // A key it doesn't have is still missing.
        assertThrows(IllegalArgumentException.class, () -> FactProperties.read(new LazyMap(failure), "count"));
    }

    @Test
    @DisplayName("a lambda or an anonymous class is a value, so a fact holding one still converts")
    void anImplementationIsntData() {
        IntSupplier score = () -> 1;

        Map<String, Object> data = FactProperties.toData(new Holder(score), 3);

        assertSame(score, data.get("score"));
    }

    @Test
    @DisplayName("an anonymous class is a value too, and a non-public supertype is skipped when looking for one")
    void anonymousAndDeeplyHiddenFacts() {
        Object anonymous = HiddenFacts.anonymousName();

        assertSame(anonymous, FactProperties.toData(Map.of("named", anonymous), 3).get("named"));
        // getDeep() is declared on a class that isn't public, under a superclass that isn't public either, so
        // nothing public declares it, and it's read directly because the class path opens every package.
        assertEquals("deep", FactProperties.read(HiddenFacts.deeplyHidden(), "deep"));
    }

    @Test
    @DisplayName("one accessor wins for a property however reflection orders them, so a rule reads one value")
    void oneAccessorPerProperty() {
        // getActive() rather than isActive(): which one matters far less than it being the same one every time.
        assertEquals(false, FactProperties.read(new TwoAccessors(), "active"));
    }

    @Test
    @DisplayName("a record's own getters are properties too, after its components")
    void aRecordWithAnExtraGetter() {
        Order order = new Order(3, 5);

        assertEquals(15, FactProperties.read(order, "total"));
        assertEquals(List.of("quantity", "unitPrice", "total"),
                List.copyOf(FactProperties.toData(order, 1).keySet()));
    }

    @Test
    @DisplayName("a primitive array is a value, so converting a fact that holds a blob doesn't box it")
    void primitiveArraysArentTakenApart() {
        byte[] avatar = {1, 2, 3};

        assertSame(avatar, FactProperties.toData(Map.of("avatar", avatar), 3).get("avatar"));
    }

    @Test
    @DisplayName("a depth past the limit is refused, rather than ending in a stack overflow")
    void depthIsBounded() {
        List<Object> loop = new ArrayList<>();
        loop.add(loop);
        Map<String, Object> facts = Map.of("loop", loop);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> FactProperties.toData(facts, Integer.MAX_VALUE));

        assertTrue(thrown.getMessage().contains("depth must be between 1 and 20"), thrown.getMessage());
        assertDoesNotThrow(() -> FactProperties.toData(facts, 20));
    }

    @Test
    @DisplayName("a public interface on the fact's own class reaches an accessor declared on a hidden base class")
    void aPublicInterfaceOnTheLeafClass() {
        assertEquals("savings-1", FactProperties.read(HiddenFacts.account(), "id"));
        assertEquals(Map.of("id", "savings-1"), FactProperties.toData(HiddenFacts.account(), 1));
    }

    @Test
    @DisplayName("a static method named like an accessor isn't the fact's accessor")
    void aStaticMethodIsntAnAccessor() {
        // Labelled.getLabel() is static, so it isn't a way to reach the fact's own getLabel(): calling it would
        // ignore the fact and return the interface's value. The fact's own method is read instead.
        assertEquals("the fact's own too", FactProperties.read(HiddenFacts.labelled(), "label"));

        // The instance accessor the same interface declares is found as usual.
        assertEquals("the fact's own", FactProperties.read(HiddenFacts.labelled(), "ownLabel"));
    }

    @Test
    @DisplayName("an anonymous fact converts when it's the target, as it reads when it's the target")
    void anAnonymousTargetConverts() {
        Object anonymous = HiddenFacts.anonymousName();

        assertEquals("anonymous", FactProperties.read(anonymous, "name"));
        assertEquals(Map.of("name", "anonymous"), FactProperties.toData(anonymous, 1));
    }

    @Test
    @DisplayName("a graph that points back at itself costs no more than a tree of the same depth")
    void aBidirectionalGraphStaysCheap() {
        Loop parent = new Loop("parent");
        List<Loop> children = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Loop child = new Loop("child" + i);
            child.link(parent);
            children.add(child);
        }
        parent.link(children.get(0));

        // Without stopping at a repeat this is items^(depth/3) work, which ran out of heap at this depth.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> assertNotNull(FactProperties.toData(Map.of("parent", parent, "children", children), 20)));
    }

    @Test
    @DisplayName("a collection is converted to a list, so it can't be the thing converted to a map")
    void aCollectionIsntAMap() {
        LineItems items = new LineItems();
        items.add("one");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> FactProperties.toData(items, 2));

        assertTrue(thrown.getMessage().contains("isn't a record, a bean or a map"), thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(new String[]{"one"}, 2));
    }

    @Test
    @DisplayName("a bean's properties are sorted, because reflection reports methods in no particular order")
    void beanPropertiesAreSorted() {
        Map<String, Object> data = FactProperties.toData(new Loan(), 1);

        assertEquals(List.of("URL", "amount", "approved", "boxed", "x"), List.copyOf(data.keySet()));
    }

    @Test
    @DisplayName("a property whose value is null converts to null")
    void aNullPropertyValue() {
        Map<String, Object> data = FactProperties.toData(new Applicant(750, null, null), 3);

        assertNull(data.get("name"));
        assertNull(data.get("address"));
    }

    @Test
    @DisplayName("an empty collection converts to an empty list")
    void emptyCollection() {
        Map<String, Object> data = FactProperties.toData(Map.of("none", new ArrayList<>()), 2);

        assertEquals(List.of(), data.get("none"));
    }
}
