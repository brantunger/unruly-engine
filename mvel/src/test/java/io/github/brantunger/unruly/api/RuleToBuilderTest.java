package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rule.toBuilder()")
class RuleToBuilderTest {

    private static Rule source() {
        return Rule.builder()
                .ruleName("r").condition("true").action("output.put('k', 1)").priority(1).description("d")
                .language("toy").build();
    }

    @Test
    @DisplayName("builds a separate rule with every field copied")
    void copiesEveryField() {
        Rule source = source();

        Rule copy = source.toBuilder().build();

        assertNotSame(source, copy);
        assertEquals("r", copy.getRuleName());
        assertEquals("true", copy.getCondition());
        assertEquals("output.put('k', 1)", copy.getAction());
        assertEquals(1, copy.getPriority());
        assertEquals("d", copy.getDescription());
        assertEquals("toy", copy.getLanguage());
    }

    @Test
    @DisplayName("changes only the fields set on the builder, and leaves the original rule alone")
    void changesOnlyTheCopy() {
        Rule source = source();

        Rule copy = source.toBuilder().priority(5).language(null).build();

        assertEquals(Rule.builder()
                .ruleName("r").condition("true").action("output.put('k', 1)").priority(5).description("d")
                .build(), copy);
        assertEquals(source(), source);
    }
}
