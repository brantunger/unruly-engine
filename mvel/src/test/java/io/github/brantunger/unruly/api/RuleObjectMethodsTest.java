package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what {@code equals}, {@code hashCode} and {@code toString} return, so a change to users' logs, hashed
 * collections and equality checks is deliberate. 2.0 added {@code enabled}, {@code validFrom}, {@code validTo} and
 * {@code tags} to all three, which changed every rule's {@code toString} and {@code hashCode} from 1.x.
 */
@DisplayName("Rule's equals, hashCode and toString return pinned values")
class RuleObjectMethodsTest {

    private static Rule full() {
        return Rule.builder()
                .ruleName("prime-rate").condition("applicant.score >= 750").action("output.rate = 4.5")
                .priority(10).description("Prime").language("mvel").build();
    }

    @Test
    @DisplayName("toString lists every field in declaration order")
    void toStringFormat() {
        assertEquals("Rule(ruleName=prime-rate, condition=applicant.score >= 750, action=output.rate = 4.5, "
                + "priority=10, description=Prime, language=mvel, enabled=true, validFrom=null, validTo=null, "
                + "tags=[])", full().toString());
        assertEquals("Rule(ruleName=r, condition=true, action=x, priority=null, description=null, language=null, "
                        + "enabled=false, validFrom=2027-06-01T00:00:00Z, validTo=2027-09-01T00:00:00Z, "
                        + "tags=[eu, retail])",
                Rule.builder().ruleName("r").condition("true").action("x").enabled(false)
                        .validFrom(Instant.parse("2027-06-01T00:00:00Z")).validTo(Instant.parse("2027-09-01T00:00:00Z"))
                        .tags(List.of("retail", "eu")).build().toString(),
                "the tags are listed in String order, whatever order they were given in");
    }

    @Test
    @DisplayName("the builder's toString lists every field set so far")
    void builderToStringFormat() {
        assertEquals("Rule.RuleBuilder(ruleName=prime-rate, condition=applicant.score >= 750, "
                + "action=output.rate = 4.5, priority=10, description=Prime, language=mvel, enabled=true, "
                + "validFrom=null, validTo=null, tags=[])", full().toBuilder().toString());
        assertEquals("Rule.RuleBuilder(ruleName=null, condition=null, action=null, priority=null, description=null, "
                + "language=null, enabled=true, validFrom=null, validTo=null, tags=[])", Rule.builder().toString());
    }

    @Test
    @DisplayName("hashCode returns the values an independent computation of its documented formula gives")
    // Computed outside Java from the formula in Rule.hashCode: 1.x's six fields, then enabled (Boolean.hashCode),
    // validFrom and validTo (43 for null) and the tags' Set.hashCode. The same computation gives 1.x's values,
    // -1798543528 and -144318294, for 1.x's six fields alone.
    void hashCodeValues() {
        assertEquals(-587241311, full().hashCode());
        assertEquals(-550219821, Rule.builder().ruleName("r").condition("true").action("x").build().hashCode());
        assertEquals(-1483400424, Rule.builder().ruleName("r").condition("true").action("x").enabled(false)
                .tags(Set.of("eu", "retail")).build().hashCode());
    }

    @Test
    @DisplayName("rules that differ in any one field, including one null and one not, aren't equal")
    void differentInOneField() {
        Map<String, UnaryOperator<Rule.RuleBuilder>> changes = new LinkedHashMap<>();
        changes.put("ruleName", builder -> builder.ruleName("other"));
        changes.put("condition", builder -> builder.condition("false"));
        changes.put("action", builder -> builder.action("output.rate = 5"));
        changes.put("priority", builder -> builder.priority(11));
        changes.put("priority null", builder -> builder.priority(null));
        changes.put("description", builder -> builder.description("other"));
        changes.put("description null", builder -> builder.description(null));
        changes.put("language", builder -> builder.language("toy"));
        changes.put("language null", builder -> builder.language(null));
        changes.put("enabled", builder -> builder.enabled(false));
        changes.put("validFrom", builder -> builder.validFrom(Instant.EPOCH));
        changes.put("validTo", builder -> builder.validTo(Instant.EPOCH));
        changes.put("tags", builder -> builder.tags(Set.of("eu")));

        changes.forEach((name, change) -> {
            Rule changed = change.apply(full().toBuilder()).build();

            assertNotEquals(full(), changed, name);
            assertNotEquals(changed, full(), name);
        });
        assertEquals(full(), full());
        assertEquals(full().hashCode(), full().hashCode());
    }

    @Test
    @DisplayName("a rule isn't equal to null or to an object of another type")
    void notEqualToNullOrOtherType() {
        // Called directly: assertNotEquals(null, rule) and assertNotEquals("text", rule) never call Rule.equals.
        assertFalse(full().equals(null));
        assertFalse(full().equals("prime-rate"));
    }
}
