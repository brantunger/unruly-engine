package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MvelFactNames quotes a fact name in its messages as MessageText quotes it")
class MvelFactNamesQuoteTest {

    @Test
    @DisplayName("a reserved name is quoted in its message too")
    void reservedNameQuoted() {
        MvelFactNames names = new MvelFactNames(new Imports(java.util.Set.of(), java.util.Set.of(),
                MvelFactNamesQuoteTest.class.getClassLoader()));
        String reserved = "empty";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> names.check(reserved));

        assertTrue(ex.getMessage().startsWith("'empty' cannot be used as a fact name"), ex.getMessage());
    }

    @Test
    @DisplayName("a Hangul filler at the end of a fact name is escaped, so the name can't read as one without it")
    void hangulFillerInFactNameEscaped() {
        MvelFactNames names = new MvelFactNames(new Imports(java.util.Set.of(), java.util.Set.of(),
                MvelFactNamesQuoteTest.class.getClassLoader()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> names.check("a-" + (char) 0x3164));

        assertEquals("'a-\\u3164' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier", ex.getMessage());
    }
}
