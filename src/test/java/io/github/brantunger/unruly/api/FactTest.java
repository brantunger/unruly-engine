package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Fact")
class FactTest {

    @Nested
    @DisplayName("Constructor with name and value")
    class NameValueConstructor {

        @Test
        @DisplayName("should set name and value correctly")
        void shouldSetNameAndValue() {
            Fact<String> fact = new Fact<>("greeting", "hello");

            assertEquals("greeting", fact.getName());
            assertEquals("hello", fact.getValue());
        }

        @Test
        @DisplayName("should allow null value")
        void shouldAllowNullValue() {
            Fact<String> fact = new Fact<>("key", null);

            assertEquals("key", fact.getName());
            assertNull(fact.getValue());
        }
    }

    @Nested
    @DisplayName("Constructor with object only")
    class ObjectConstructor {

        @Test
        @DisplayName("should use toString as name")
        void shouldUseToStringAsName() {
            Fact<Integer> fact = new Fact<>(42);

            assertEquals("42", fact.getName());
            assertEquals(42, fact.getValue());
        }

        @Test
        @DisplayName("should throw NullPointerException for null")
        void shouldThrowForNull() {
            assertThrows(NullPointerException.class, () -> new Fact<>(null));
        }
    }

    @Nested
    @DisplayName("Constructor with FactReference")
    class CopyConstructor {

        @Test
        @DisplayName("should copy name and value from another fact")
        void shouldCopyFromFactReference() {
            Fact<String> original = new Fact<>("key", "value");
            Fact<String> copy = new Fact<>(original);

            assertEquals("key", copy.getName());
            assertEquals("value", copy.getValue());
        }
    }

    @Nested
    @DisplayName("Mutability")
    class Mutability {

        @Test
        @DisplayName("setName should update name and return this")
        void setNameShouldUpdateAndReturnSelf() {
            Fact<String> fact = new Fact<>("old", "value");

            FactReference<String> result = fact.setName("new");

            assertEquals("new", fact.getName());
            assertSame(fact, result);
        }

        @Test
        @DisplayName("setValue should update value and return this")
        void setValueShouldUpdateAndReturnSelf() {
            Fact<String> fact = new Fact<>("key", "old");

            FactReference<String> result = fact.setValue("new");

            assertEquals("new", fact.getValue());
            assertSame(fact, result);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("equal facts should be equal")
        void equalFactsShouldBeEqual() {
            Fact<String> a = new Fact<>("key", "value");
            Fact<String> b = new Fact<>("key", "value");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different names should not be equal")
        void differentNamesShouldNotBeEqual() {
            Fact<String> a = new Fact<>("key1", "value");
            Fact<String> b = new Fact<>("key2", "value");

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("different values should not be equal")
        void differentValuesShouldNotBeEqual() {
            Fact<String> a = new Fact<>("key", "value1");
            Fact<String> b = new Fact<>("key", "value2");

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("should not equal null or different type")
        void shouldNotEqualNullOrDifferentType() {
            Fact<String> fact = new Fact<>("key", "value");

            assertNotEquals(null, fact);
            assertNotEquals("not a fact", fact);
        }

        @SuppressWarnings("EqualsWithItself")
        @Test
        @DisplayName("reflexive: fact equals itself")
        void reflexiveEquals() {
            Fact<String> fact = new Fact<>("key", "value");

            assertEquals(fact, fact);
        }

        @Test
        @DisplayName("not equal to non-Fact object via fact.equals()")
        void notEqualToNonFactType() {
            Fact<String> fact = new Fact<>("key", "value");

            // Explicitly call fact.equals() to cover the instanceof false-branch
            assertNotEquals(fact, "a string");
            assertNotEquals(fact, 42);
        }
    }

    @Test
    @DisplayName("toString should include name and value")
    void toStringShouldIncludeNameAndValue() {
        Fact<String> fact = new Fact<>("myFact", "myValue");

        String result = fact.toString();

        assertTrue(result.contains("myFact"));
        assertTrue(result.contains("myValue"));
    }

    @Test
    @DisplayName("copy constructor throws NullPointerException with message for null FactReference")
    void copyConstructorThrowsForNullFactReference() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new Fact<Object>((FactReference<Object>) null));
        assertTrue(ex.getMessage().contains("fact must not be null"));
    }
}
