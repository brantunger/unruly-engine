package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #410: a condition's value, and the detail its language explains it with. */
@DisplayName("ConditionResult: a condition's value, and the detail that explains it")
class ConditionResultTest {

    @Test
    @DisplayName("a boolean without a detail is one of two shared results, so returning it allocates nothing")
    void booleansShared() {
        assertSame(ConditionResult.TRUE, ConditionResult.of(true));
        assertSame(ConditionResult.FALSE, ConditionResult.of(false));
        assertSame(ConditionResult.TRUE, ConditionResult.of(true, null));
        assertSame(ConditionResult.FALSE, ConditionResult.of(Boolean.FALSE, null));
        assertEquals(Boolean.TRUE, ConditionResult.TRUE.value());
        assertEquals(Boolean.FALSE, ConditionResult.FALSE.value());
        assertNull(ConditionResult.TRUE.detail());
        assertNull(ConditionResult.FALSE.detail());
    }

    @Test
    @DisplayName("a value that isn't a boolean is kept as it is, for the engine to reject")
    void otherValuesKept() {
        for (Object notBoolean : Arrays.asList(null, "true", 1)) {
            ConditionResult result = ConditionResult.of(notBoolean);

            assertEquals(notBoolean, result.value());
            assertNull(result.detail());
        }
    }

    @Test
    @DisplayName("a detail is kept with its value, whatever the value is")
    void detailKept() {
        List<Object> operands = List.of(750, 700);

        ConditionResult matched = ConditionResult.of(true, operands);
        ConditionResult wrong = ConditionResult.of("yes", Map.of("why", "a string"));

        assertNotSame(ConditionResult.TRUE, matched);
        assertEquals(Boolean.TRUE, matched.value());
        assertSame(operands, matched.detail());
        assertEquals("yes", wrong.value());
        assertEquals(Map.of("why", "a string"), wrong.detail());
    }
}
