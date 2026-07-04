package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactMap")
class FactMapTest {

    private FactMap<Object> factMap;

    @BeforeEach
    void setUp() {
        factMap = new FactMap<>();
    }

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("default constructor creates empty map")
        void defaultConstructorCreatesEmptyMap() {
            assertTrue(factMap.isEmpty());
            assertEquals(0, factMap.size());
        }

        @Test
        @DisplayName("varargs constructor populates map")
        void varargsConstructorPopulatesMap() {
            Fact<Object> fact1 = new Fact<>("a", "valueA");
            Fact<Object> fact2 = new Fact<>("b", "valueB");

            FactMap<Object> map = new FactMap<>(fact1, fact2);

            assertEquals(2, map.size());
            assertEquals("valueA", map.getValue("a"));
            assertEquals("valueB", map.getValue("b"));
        }
    }

    @Nested
    @DisplayName("getValue and setValue")
    class GetSetValue {

        @Test
        @DisplayName("getValue returns null for missing key")
        void getValueReturnsNullForMissingKey() {
            assertNull(factMap.getValue("nonexistent"));
        }

        @Test
        @DisplayName("setValue creates new fact if absent")
        void setValueCreatesNewFactIfAbsent() {
            factMap.setValue("claim", "myData");

            assertEquals("myData", factMap.getValue("claim"));
            assertEquals(1, factMap.size());
        }

        @Test
        @DisplayName("setValue updates existing fact value")
        void setValueUpdatesExistingFact() {
            factMap.setValue("claim", "original");
            factMap.setValue("claim", "updated");

            assertEquals("updated", factMap.getValue("claim"));
            assertEquals(1, factMap.size());
        }
    }

    @Nested
    @DisplayName("put and get")
    class PutAndGet {

        @Test
        @DisplayName("put by FactReference inserts by name")
        void putByFactReferenceInsertsByName() {
            Fact<Object> fact = new Fact<>("key", "value");

            factMap.put(fact);

            assertNotNull(factMap.get("key"));
            assertEquals("value", factMap.get("key").getValue());
        }

        @Test
        @DisplayName("put returns previous value")
        void putReturnsPreviousValue() {
            Fact<Object> first = new Fact<>("key", "first");
            Fact<Object> second = new Fact<>("key", "second");

            FactReference<Object> prev1 = factMap.put("key", first);
            FactReference<Object> prev2 = factMap.put("key", second);

            assertNull(prev1);
            assertNotNull(prev2);
            assertEquals("first", prev2.getValue());
        }

        @Test
        @DisplayName("get returns null for missing key")
        void getReturnsNullForMissingKey() {
            assertNull(factMap.get("missing"));
        }
    }

    @Nested
    @DisplayName("Map operations")
    class MapOperations {

        @Test
        @DisplayName("containsKey returns true for existing key")
        void containsKeyForExisting() {
            factMap.setValue("present", "val");

            assertTrue(factMap.containsKey("present"));
            assertFalse(factMap.containsKey("absent"));
        }

        @Test
        @DisplayName("remove returns removed fact")
        void removeReturnsRemovedFact() {
            factMap.setValue("key", "value");

            FactReference<Object> removed = factMap.remove("key");

            assertNotNull(removed);
            assertEquals("value", removed.getValue());
            assertTrue(factMap.isEmpty());
        }

        @Test
        @DisplayName("remove returns null for missing key")
        void removeReturnsNullForMissingKey() {
            assertNull(factMap.remove("nonexistent"));
        }

        @Test
        @DisplayName("clear empties the map")
        void clearEmptiesMap() {
            factMap.setValue("a", "1");
            factMap.setValue("b", "2");

            factMap.clear();

            assertTrue(factMap.isEmpty());
        }

        @Test
        @DisplayName("keySet returns all keys")
        void keySetReturnsAllKeys() {
            factMap.setValue("x", "1");
            factMap.setValue("y", "2");

            assertEquals(2, factMap.keySet().size());
            assertTrue(factMap.keySet().contains("x"));
            assertTrue(factMap.keySet().contains("y"));
        }

        @Test
        @DisplayName("entrySet returns all entries")
        void entrySetReturnsAllEntries() {
            factMap.setValue("a", "1");

            assertEquals(1, factMap.entrySet().size());
        }

        @Test
        @DisplayName("values returns all fact references")
        void valuesReturnsAllFactReferences() {
            factMap.setValue("a", "1");
            factMap.setValue("b", "2");

            assertEquals(2, factMap.values().size());
        }

        @Test
        @DisplayName("containsValue returns true for existing value")
        void containsValueForExisting() {
            Fact<Object> fact = new Fact<>("key", "val");
            factMap.put(fact);

            assertTrue(factMap.containsValue(fact));
        }

        @Test
        @DisplayName("putAll adds all entries from another map")
        void putAllAddsAllEntries() {
            java.util.Map<String, FactReference<Object>> other = new java.util.HashMap<>();
            other.put("x", new Fact<>("x", "valX"));
            other.put("y", new Fact<>("y", "valY"));

            factMap.putAll(other);

            assertEquals(2, factMap.size());
            assertEquals("valX", factMap.getValue("x"));
            assertEquals("valY", factMap.getValue("y"));
        }
    }

    @Nested
    @DisplayName("Map constructor")
    class MapConstructor {

        @Test
        @DisplayName("constructor from Map creates defensive copy")
        void constructorFromMapCreatesDefensiveCopy() {
            java.util.Map<String, FactReference<Object>> map = new java.util.HashMap<>();
            map.put("key", new Fact<>("key", "value"));

            FactMap<Object> factMap = new FactMap<>(map);

            // Mutate the original map
            map.put("extra", new Fact<>("extra", "extraValue"));
            map.remove("key");

            // FactMap should be unaffected
            assertEquals(1, factMap.size());
            assertEquals("value", factMap.getValue("key"));
            assertNull(factMap.getValue("extra"));
        }
    }

    @Nested
    @DisplayName("put null-name fact")
    class PutNullNameFact {

        @Test
        @DisplayName("put(FactReference) with null name throws IllegalArgumentException")
        void putFactWithNullNameThrows() {
            Fact<Object> nullNameFact = new Fact<>(null, "value");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> factMap.put(nullNameFact));
            assertTrue(ex.getMessage().contains("fact name must not be null"));
        }
    }
}
