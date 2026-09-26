package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.ParserConfiguration;
import org.mvel2.util.MethodStub;

import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #651: MVEL casts whatever is imported as a name an expression calls like a method to a static method, which failed
 * for a class with a {@link ClassCastException} that said nothing of the expression.
 */
@DisplayName("a configuration's static import is MVEL's, unless the name is an imported class")
class StaticImportTest {

    private static ParserConfiguration configuration() {
        return new Imports(Set.of(), Set.of(ArrayList.class), StaticImportTest.class.getClassLoader())
                .newConfiguration();
    }

    @Test
    @DisplayName("an imported class called like a method is reported as such, as a ClassCastException still")
    void importedClass() {
        ClassCastException thrown = assertThrows(Imports.ClassCalledLikeMethod.class,
                () -> configuration().getStaticImport("ArrayList"));

        assertEquals("ArrayList is an imported class, not a method", thrown.getMessage());
    }

    @Test
    @DisplayName("an imported static method is MVEL's")
    void importedMethod() throws NoSuchMethodException {
        ParserConfiguration configuration = configuration();
        configuration.addImport("abs", Math.class.getMethod("abs", int.class));

        MethodStub stub = configuration.getStaticImport("abs");

        assertEquals(Math.class, stub.getClassReference());
        assertEquals("abs", stub.getMethod().getName());
    }

    @Test
    @DisplayName("a name that isn't imported is MVEL's: none")
    void nothingImported() {
        assertNull(configuration().getStaticImport("nothing"));
    }
}
