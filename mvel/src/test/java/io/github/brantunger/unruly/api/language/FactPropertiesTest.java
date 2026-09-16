package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.hidden.HiddenFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.TreeMap;
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
    @DisplayName("what a getter throws reaches the caller, and a checked exception is wrapped")
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
    @DisplayName("a collection that holds itself still ends, because a container costs a level too")
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
    @DisplayName("depth stops a cycle between two facts")
    void cycleStopsAtTheDepthLimit() {
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
    @DisplayName("a depth below 1, and a value with no properties, are rejected")
    void rejectedConversions() {
        assertThrows(IllegalArgumentException.class, () -> FactProperties.toData(new Loan(), 0));
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
    @DisplayName("a property nothing public declares says it can't be read, not that it isn't there")
    void anUnreachableGetter() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(HiddenFacts.withASecret(), "secret"));

        assertTrue(thrown.getMessage().contains("has a property 'secret', but its accessor on"),
                thrown.getMessage());
        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
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
    @DisplayName("an accessor a public interface only inherits from a hidden one still can't be read")
    void aPublicInterfaceThatOnlyInheritsTheAccessor() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(HiddenFacts.boxed(), "boxed"));

        assertTrue(thrown.getMessage().contains("can't be reached from here"), thrown.getMessage());
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
        // nothing public declares it and reading it says so.
        assertThrows(IllegalStateException.class, () -> FactProperties.read(HiddenFacts.deeplyHidden(), "deep"));
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
        // ignore the fact and return the interface's value. The property is refused rather than answered wrongly.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> FactProperties.read(HiddenFacts.labelled(), "label"));
        assertFalse(String.valueOf(thrown.getMessage()).contains("the interface's own"), thrown.getMessage());

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
