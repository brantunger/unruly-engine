package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConditionAssignments")
class ConditionAssignmentsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "claim.approved = true                   | '=' at position 15",
            "approved=true                           | '=' at position 8",
            "claim.amount += 5; true                 | '+=' at position 13",
            "claim.amount -= 5; true                 | '-=' at position 13",
            "claim.amount *= 2; true                 | '*=' at position 13",
            "claim.amount /= 2; true                 | '/=' at position 13",
            "x <<= 1; true                           | '<<=' at position 2",
            "x >>= 1; true                           | '>>=' at position 2",
            "x >>>= 1; true                          | '>>>=' at position 2",
            "claim.amount++; true                    | '++' at position 12",
            "--x > 0                                 | '--' at position 0",
            "x == (y = 3)                            | '=' at position 8",
            "claim['k'] = 3; true                    | '=' at position 11",
            "int q = 5; q > 1                        | '=' at position 6",
            "claim.check(claim.approved = true)      | '=' at position 27",
            "if (x > 1) { claim.a = 1 }; true        | '=' at position 21",
            "with (claim) { status = 'X' }; true     | 'with' at position 0",
            "def f() { true }; f()                   | 'def' at position 0",
            "function f() { true }; f()              | 'function' at position 0",
            "x > 1 && with (claim) { a = 1 } == null | 'with' at position 9",
            "a?with (b) { c = 1 }                    | 'with' at position 2",
            "a ? with (b) { c = 1 }                  | 'with' at position 4",
            "claim.a =                               | '=' at position 8",
            "import_static java.lang.Math.max; max(x, 1) == 5 | 'import_static' at position 0",
    })
    void findsAssignments(String condition, String expected) {
        assertEquals(expected, String.valueOf(ConditionAssignments.find(condition)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "claim.approved == true",
            "x != 1 && x <= 3 && x >= 0",
            "name ~= 'a=b.*'",
            "claim.name == 'a=b'",
            "claim.name == \"x++ = y\"",
            "s == 'it\\'s = ok'",
            "claim['a=b'] == 1",
            "// x = 1\ntrue",
            "/* a = b */ true",
            "x / 2 > 1",
            "x - -1 > 0 && x + 1 > 0",
            "x == -1",
            "claim.withdrawal > 0 && claim.define == 1",
            "claim.with == 1 && claim.?def == null",
            "order.\n    with(1) == 1",
            "m. with == 2",
            "m .\tfunction == 2 && m.? def == null",
            "claim.import_static == 1",
            "isdef x && x > 1",
            "status in ['A=1', 'B']",
            "($ in list if $ > 1).size() > 0",
            "claim.getItems().size() > 0 ? true : false",
            "['a' : 1].a == 1",
            "x instanceof String",
            "1e5 > x",
    })
    void acceptsComparisons(String condition) {
        assertNull(ConditionAssignments.find(condition));
    }

    @Test
    @DisplayName("an unterminated block comment or literal ends the scan without a false match")
    void unterminatedCommentOrLiteral() {
        assertNull(ConditionAssignments.find("true /* x = 1"));
        assertNull(ConditionAssignments.find("name == 'x = 1"));
    }
}
