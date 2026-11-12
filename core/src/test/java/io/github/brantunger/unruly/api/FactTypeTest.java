package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.CompileContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@link FactType} keeps the type argument it was written with, so a fact can be declared as a {@code List<String>}
 * (#1390), stands for a class or a parameterized type only, and {@link CompileContext#rawClass(Type)} gives the
 * class of every type a fact can be declared with and names any other.
 */
@DisplayName("a FactType keeps a fact's type with its type arguments")
class FactTypeTest {

    /** A token of a named class, which extends {@code FactType} directly as an anonymous one does. */
    private static final class Tags extends FactType<List<String>> {
    }

    /** A class between a token and {@code FactType}, which hides the type argument from it. */
    private abstract static class Indirect<T> extends FactType<T> {
    }

    /** A parameterized type as another library's type token makes one, with whatever raw type it is given. */
    private record Parameterized(Type rawType, Type... arguments) implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public String getTypeName() {
            return "Parameterized(" + rawType.getTypeName() + ")";
        }
    }

    /** A method whose parameters have the kinds of type no fact can be declared with. */
    @SuppressWarnings("unused")
    private static <T> void kinds(T variable, List<? extends Number> wildcard, List<String>[] genericArray) {
    }

    private static Type[] kinds() throws NoSuchMethodException {
        return FactTypeTest.class.getDeclaredMethod("kinds", Object.class, List.class, List[].class)
                .getGenericParameterTypes();
    }

    private static TypeVariable<?> typeVariable() throws NoSuchMethodException {
        return (TypeVariable<?>) kinds()[0];
    }

    private static WildcardType wildcard() throws NoSuchMethodException {
        return (WildcardType) ((ParameterizedType) kinds()[1]).getActualTypeArguments()[0];
    }

    private static GenericArrayType genericArray() throws NoSuchMethodException {
        return (GenericArrayType) kinds()[2];
    }

    private static <T> FactType<T> ofTheCallersVariable() {
        return new FactType<T>() {
        };
    }

    @Test
    @DisplayName("an anonymous subclass stands for the parameterized type it was written with")
    void keepsTypeArguments() {
        FactType<Map<String, List<Integer>>> token = new FactType<>() {
        };

        ParameterizedType type = assertInstanceOf(ParameterizedType.class, token.type());
        assertEquals(Map.class, type.getRawType());
        assertEquals(String.class, type.getActualTypeArguments()[0]);
        assertEquals("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>", token.getTypeName());
        assertEquals(token.getTypeName(), token.toString());
    }

    @Test
    @DisplayName("a token of a class, an array of a class included, stands for that class")
    void standsForAClass() {
        assertSame(String.class, new FactType<String>() {
        }.type());
        assertSame(String[].class, new FactType<String[]>() {
        }.type());
        assertEquals("java.lang.String", new FactType<String>() {
        }.toString());
    }

    @Test
    @DisplayName("a named class that extends FactType directly is a token too")
    void namedSubclass() {
        assertEquals("java.util.List<java.lang.String>", new Tags().getTypeName());
    }

    @Test
    @DisplayName("a type argument's own arguments can be wildcards")
    void nestedWildcard() {
        FactType<List<? extends Number>> token = new FactType<>() {
        };

        assertEquals("java.util.List<? extends java.lang.Number>", token.getTypeName());
        assertSame(List.class, CompileContext.rawClass(token.type()));
    }

    @Test
    @DisplayName("a raw FactType is rejected, saying how to write one")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rawTokenRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> new FactType() {
        });

        assertEquals("A FactType must be created as a direct subclass with its type argument, such as"
                + " new FactType<List<String>>() { }, not as " + FactType.class.getName(), thrown.getMessage());
    }

    @Test
    @DisplayName("a subclass of a subclass is rejected: FactType can't see its type argument")
    void indirectSubclassRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Indirect<List<String>>() {
                });

        assertTrue(thrown.getMessage().startsWith("A FactType must be created as a direct subclass"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().endsWith("Indirect<java.util.List<java.lang.String>>"), thrown.getMessage());
    }

    @Test
    @DisplayName("a token of a type variable is rejected, naming it")
    void typeVariableTokenRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                FactTypeTest::<String>ofTheCallersVariable);

        assertEquals("A fact's type must be a class or a parameterized type, such as List<String>, not the type"
                + " variable T", thrown.getMessage());
    }

    @Test
    @DisplayName("a token of an array of a parameterized type is rejected, naming it")
    void genericArrayTokenRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new FactType<List<String>[]>() {
                });

        assertEquals("A fact's type must be a class or a parameterized type, such as List<String>, not the generic"
                + " array type java.util.List<java.lang.String>[]", thrown.getMessage());
    }

    @Test
    @DisplayName("CompileContext.rawClass gives a class itself and a parameterized type's raw class, and not a"
            + " token's, which no language is given")
    void rawClass() {
        assertSame(int.class, CompileContext.rawClass(int.class));
        assertSame(String.class, CompileContext.rawClass(String.class));
        assertSame(List.class, CompileContext.rawClass(new Tags().type()));
        assertThrows(IllegalArgumentException.class, () -> CompileContext.rawClass(new Tags()));
        assertSame(Map.class, CompileContext.rawClass(new Parameterized(Map.class, String.class, String.class)));
        assertEquals("type must not be null",
                assertThrows(NullPointerException.class, () -> CompileContext.rawClass(null)).getMessage());
    }

    @Test
    @DisplayName("rawClass rejects a type variable, a wildcard and a generic array type, naming each")
    void rawClassRejectsTheOtherKinds() throws NoSuchMethodException {
        String start = "A fact's type must be a class or a parameterized type, such as List<String>, not ";

        assertEquals(start + "the type variable T", assertThrows(IllegalArgumentException.class,
                () -> CompileContext.rawClass(typeVariable())).getMessage());
        assertEquals(start + "the wildcard ? extends java.lang.Number", assertThrows(IllegalArgumentException.class,
                () -> CompileContext.rawClass(wildcard())).getMessage());
        assertEquals(start + "the generic array type java.util.List<java.lang.String>[]",
                assertThrows(IllegalArgumentException.class,
                        () -> CompileContext.rawClass(genericArray())).getMessage());
    }

    @Test
    @DisplayName("rawClass rejects a parameterized type whose raw type isn't a class, and any other Type, naming it")
    void rawClassRejectsOtherImplementations() throws NoSuchMethodException {
        String start = "A fact's type must be a class or a parameterized type, such as List<String>, not ";
        Type noRawClass = new Parameterized(typeVariable());
        Type other = new Type() {
            @Override
            public String getTypeName() {
                return "com.example.JavaType";
            }
        };

        assertEquals(start + "a parameterized type whose raw type isn't a class or an interface, Parameterized(T)",
                assertThrows(IllegalArgumentException.class, () -> CompileContext.rawClass(noRawClass)).getMessage());
        assertEquals(start + "a parameterized type whose raw type isn't a class or an interface, Parameterized(int)",
                assertThrows(IllegalArgumentException.class,
                        () -> CompileContext.rawClass(new Parameterized(int.class, String.class))).getMessage());
        assertEquals(start + "a " + other.getClass().getName() + ", com.example.JavaType",
                assertThrows(IllegalArgumentException.class, () -> CompileContext.rawClass(other)).getMessage());
    }

    @Test
    @DisplayName("two tokens are equal when their types are, and a token never equals the type it stands for")
    void equality() {
        FactType<List<String>> token = new FactType<>() {
        };
        FactType<List<String>> same = new Tags();
        FactType<List<Integer>> other = new FactType<>() {
        };

        assertTrue(token.equals(token));
        assertTrue(token.equals(same));
        assertEquals(token.hashCode(), same.hashCode());
        assertEquals(token.type().hashCode(), token.hashCode());
        assertFalse(token.equals(other));
        assertFalse(token.equals(token.type()));
        assertFalse(token.type().equals(token));
        assertFalse(token.equals(null));
    }
}
