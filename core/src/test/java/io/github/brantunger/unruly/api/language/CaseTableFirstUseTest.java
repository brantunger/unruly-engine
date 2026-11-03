package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1297: a fact's getter whose first or second character after {@code get} or {@code is} is above U+00FF, and a bean
 * output's property whose first character is, are named with the JDK's case rules, which for such a character read
 * the JDK's table of the first plane, {@code java.lang.CharacterData00}. The JVM builds that table on its first use,
 * in its static initializer, and {@code FactProperties} and the default output writer can be that first use with no
 * engine built, deep in a stack, where an overflow would leave the table unusable for the life of the JVM. So the first
 * such read must check the stack's room first, as the engine's first build does before it reads the table, and a
 * name whose characters asked about are U+00FF or below must check nothing. Overflowing at every depth finds a broken
 * table only on some JDKs and stacks, so this runs {@link CaseTableFirstUseScenario} in a new JVM, once for each of the
 * three paths, and reads the {@code Initializing '...'} lines {@code -Xlog:class+init} prints: the engine's
 * {@code StackHeadroom}, which holds the check and which nothing else in the scenario initializes, must be initialized
 * between the markers before the table is, and neither may be before the first marker, where the scenario reads and
 * writes names of U+00FF and below.
 *
 * <p>
 * #1366: the check of a compile context's package import, made with no engine built as a language's tests make one,
 * reads the JDK's table of the plane of each code point it asks about, so its first code point above U+00FF must check
 * the room first too, then build every plane's table, so a later one outside the first plane can't be the first read of
 * its own: for a package import above U+00FF in the first plane, and for one outside it, every plane's table must be
 * initialized between the markers after the check, and a context made before the first marker with a package import
 * of U+00FF and below must initialize none of them.
 * </p>
 */
@DisplayName("#1297, #1366: the first case read of a name, or check of a package import, above U+00FF checks the"
        + " stack's room before the JDK builds the table")
class CaseTableFirstUseTest {

    private static final String STACK_HEADROOM = "Initializing 'io/github/brantunger/unruly/core/StackHeadroom'";
    private static final String CASE_TABLE = "Initializing 'java/lang/CharacterData00'";
    // The JDK's tables of the other planes, which the check of a package import's name builds with the first's.
    private static final List<String> OTHER_PLANES = List.of("Initializing 'java/lang/CharacterData01'",
            "Initializing 'java/lang/CharacterData02'", "Initializing 'java/lang/CharacterData03'",
            "Initializing 'java/lang/CharacterDataUndefined'", "Initializing 'java/lang/CharacterData0E'",
            "Initializing 'java/lang/CharacterDataPrivateUse'");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {CaseTableFirstUseScenario.FIRST_CHARACTER, CaseTableFirstUseScenario.SECOND_CHARACTER,
            CaseTableFirstUseScenario.SETTER, CaseTableFirstUseScenario.PACKAGE_IMPORT,
            CaseTableFirstUseScenario.WIDE_PACKAGE_IMPORT})
    @DisplayName("the room is checked before the table is built, and not for names of U+00FF and below")
    void checksTheRoomBeforeTheTable(String first, @TempDir Path dir) throws IOException, InterruptedException {
        String output = ChildJvm.run(dir, CaseTableFirstUseScenario.class, "-Xlog:class+init=info:stdout",
                "-D" + CaseTableFirstUseScenario.FIRST + "=" + first);
        List<String> lines = output.lines().toList();
        int before = lines.indexOf(CaseTableFirstUseScenario.BEFORE);
        int after = lines.indexOf(CaseTableFirstUseScenario.AFTER);
        assertTrue(before >= 0 && after > before, "markers missing:\n" + output);

        assertEquals(-1, find(lines, STACK_HEADROOM, 0, before), "names of U+00FF and below checked the room:\n"
                + output);
        assertEquals(-1, find(lines, CASE_TABLE, 0, before), "the table was built before the calls, so they couldn't"
                + " have been the first to read it:\n" + output);
        int table = find(lines, CASE_TABLE, before, after);
        assertTrue(table >= 0, "the call didn't read the table:\n" + output);
        int check = find(lines, STACK_HEADROOM, before, after);
        assertTrue(check >= 0 && check < table, "the table was built before the room was checked:\n" + output);
        boolean packageImport = CaseTableFirstUseScenario.PACKAGE_IMPORT.equals(first)
                || CaseTableFirstUseScenario.WIDE_PACKAGE_IMPORT.equals(first);
        for (String plane : OTHER_PLANES) {
            assertEquals(-1, find(lines, plane, 0, before), "a table was built before the calls:\n" + output);
            if (packageImport) {
                int built = find(lines, plane, before, after);
                assertTrue(built > check, "the import's check didn't build " + plane + " after the room was checked:\n"
                        + output);
            }
        }

        List<String> expected = List.of("école", "ÿx", "aÿ", "latin", "boundary",
                CaseTableFirstUseScenario.FIRST_CHARACTER.equals(first) ? "pi-x"
                        : CaseTableFirstUseScenario.SECOND_CHARACTER.equals(first) ? "x-pi"
                        : packageImport ? "imported" : "wide",
                String.valueOf(Character.LOWERCASE_LETTER));
        assertEquals(expected, answers(lines), output);
    }

    // The index of the first line from start, up to end, that starts what the JVM logs for a class, or -1.
    private static int find(List<String> lines, String initializing, int start, int end) {
        for (int i = start; i < end; i++) {
            if (lines.get(i).contains(initializing)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> answers(List<String> lines) {
        List<String> answers = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(CaseTableFirstUseScenario.ANSWER)) {
                answers.add(line.substring(CaseTableFirstUseScenario.ANSWER.length()));
            }
        }
        return answers;
    }
}
