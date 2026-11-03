package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.core.EngineCompileContext;

import java.util.Set;

/**
 * Run by {@link CaseTableFirstUseTest} in a JVM that logs every class it initializes, and builds no engine. Before it
 * prints {@link #BEFORE}, it reads facts and writes a bean output through the default output writer with names whose
 * characters asked about are U+00FF or below, U+00FF itself among them, so everything those paths initialize is
 * initialized, and none of them may check the stack's room or read the JDK's table of the first plane,
 * {@code java.lang.CharacterData00}. Between {@link #BEFORE} and {@link #AFTER} it takes one path with a character
 * above U+00FF, the one {@link #FIRST} names, which is the first in the JVM to ask the table about such a character,
 * so the JVM builds the table there: a getter whose first character after {@code get} is above U+00FF, one whose
 * second is, or a property, written with its setter, whose first is (#1297). Before {@link #BEFORE} it also reads a
 * character of a string with one above U+00FF, so the JDK's code for such strings, {@code java.lang.StringUTF16}, is
 * initialized before the calls, as it is at startup on Windows but not on Linux or macOS (#1295). Between the markers
 * it concatenates no string, and it keeps the answers in an array. Then it reads the type of a character above
 * U+00FF, which a table left broken would fail, and prints each answer after {@link #ANSWER}.
 *
 * <p>
 * #1366: before {@link #BEFORE} it also makes a compile context, as a language's tests do with no engine built, with
 * a package import whose code points are U+00FF or below, U+00FF itself among them, and none of that may check the
 * room or read a table above Latin-1 either. Two more paths may be taken between the markers: a context made with a
 * package import that has a code point above U+00FF in the first plane, or one with a code point outside it, the
 * check of whose name reads the JDK's table of its plane.
 * </p>
 */
final class CaseTableFirstUseScenario {

    static final String BEFORE = "SCENARIO case table start";
    static final String AFTER = "SCENARIO case table ended";
    static final String ANSWER = "SCENARIO answer: ";
    /** The system property that names the path taken between the markers. */
    static final String FIRST = "scenario.first";
    /** A getter whose first character after {@code get} is above U+00FF. */
    static final String FIRST_CHARACTER = "firstCharacter";
    /** A getter whose second character after {@code get} is above U+00FF, and whose first isn't. */
    static final String SECOND_CHARACTER = "secondCharacter";
    /** A property, written with its setter, whose first character is above U+00FF. */
    static final String SETTER = "setter";
    /** A compile context made with a package import that has a code point above U+00FF, in the first plane. */
    static final String PACKAGE_IMPORT = "packageImport";
    /** A compile context made with a package import that has a code point outside the first plane, U+10000. */
    static final String WIDE_PACKAGE_IMPORT = "widePackageImport";

    private CaseTableFirstUseScenario() {
    }

    /** Getters whose characters asked about are U+00FF or below. */
    public static final class Latin {

        public String getÉcole() {
            return "école";
        }

        public String getÿx() {
            return "ÿx";
        }

        public String getAÿ() {
            return "aÿ";
        }
    }

    /** A getter whose first character after {@code get} is above U+00FF: it reads {@code πx}. */
    public static final class FirstWide {

        public String getΠx() {
            return "pi-x";
        }
    }

    /** A getter whose second character after {@code get} is above U+00FF: it reads {@code xπ}. */
    public static final class SecondWide {

        public String getXπ() {
            return "x-pi";
        }
    }

    /** A bean output, with setters for properties whose first character is U+00FF or below, and one above. */
    public static final class Output {

        private Object written;

        public Object getWritten() {
            return written;
        }

        public void setÉcole(Object value) {
            written = value;
        }

        public void setŸz(Object value) {
            written = value;
        }

        public void setΠy(Object value) {
            written = value;
        }
    }

    public static void main(String[] args) throws Exception {
        String first = System.getProperty(FIRST);
        // The JDK's code for strings with a character above U+00FF, initialized by the first read of one (#1295).
        int wide = "aπ".codePointAt(1);
        OutputWriter<Output> writer = OutputWriter.beansAndMaps();
        Latin latin = new Latin();
        Output output = new Output();
        FirstWide firstWide = new FirstWide();
        SecondWide secondWide = new SecondWide();
        Object[] answers = new Object[7];
        answers[0] = FactProperties.read(latin, "école");
        answers[1] = FactProperties.read(latin, "ÿx");
        answers[2] = FactProperties.read(latin, "aÿ");
        writer.set(output, "école", "latin");
        answers[3] = output.getWritten();
        // U+00FF upper-cases to U+0178, which the table the JVM builds at startup answers.
        writer.set(output, "ÿz", "boundary");
        answers[4] = output.getWritten();
        ClassLoader loader = CaseTableFirstUseScenario.class.getClassLoader();
        // U+00E9 and U+00FF, which the table the JVM builds at startup answers (#1366).
        new EngineCompileContext(Set.of("caf\u00e9.\u00ffx"), Set.of(), loader);
        System.out.println(BEFORE);
        if (FIRST_CHARACTER.equals(first)) {
            answers[5] = FactProperties.read(firstWide, "πx");
        } else if (SECOND_CHARACTER.equals(first)) {
            answers[5] = FactProperties.read(secondWide, "xπ");
        } else if (PACKAGE_IMPORT.equals(first)) {
            answers[5] = imported(new EngineCompileContext(Set.of("com.\u03c0x"), Set.of(), loader));
        } else if (WIDE_PACKAGE_IMPORT.equals(first)) {
            // U+10000, a letter of the Linear B syllabary, written as its surrogate pair.
            answers[5] = imported(new EngineCompileContext(Set.of("com.\ud800\udc00x"), Set.of(), loader));
        } else {
            writer.set(output, "πy", "wide");
            answers[5] = output.getWritten();
        }
        System.out.println(AFTER);
        // The table works after the calls: 2 for a lower-case letter.
        answers[6] = Character.getType(wide);
        for (Object answer : answers) {
            System.out.println(ANSWER + answer);
        }
    }

    private static String imported(EngineCompileContext context) {
        return context.packageImports().size() == 1 ? "imported" : "lost";
    }
}
