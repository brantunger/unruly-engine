package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FactMapNullNameTest {
    @Test
    @DisplayName("Constructor throws when fact name is null")
    void testConstructorThrowsOnNullName() {
        FactReference<Object> badFact = new Fact<>(null, "value");
        assertThrows(IllegalArgumentException.class, () -> new FactMap<>(badFact));
    }
}
