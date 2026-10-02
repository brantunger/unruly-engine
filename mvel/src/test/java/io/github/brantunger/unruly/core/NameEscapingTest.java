package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.TestSupport;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.brantunger.unruly.core.EngineLogs.ENGINE_LOGGER;
import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine's messages that don't depend on the language, such as a list of languages, a rejected import and a
 * unique-match failure, are tested once, with the toy language, in core's {@code NameEscapingWithoutMvelTest}.
 */
@DisplayName("names in the engine's messages are escaped and shortened, so they can't forge log lines")
class NameEscapingTest {

    private static final String FORGED = "[main] INFO com.example.Audit - forged entry";

    // 199 control characters, each escaped to six, so a name of one more character quotes to 1,195 characters: more
    // than the engine keeps of a message, so a message that quoted it whole would be cut, inside an escape (#875).
    private static final String CONTROLS = String.valueOf((char) 1).repeat(Failures.MAX_NAME_LENGTH - 1);
    private static final String ESCAPED_CONTROL = "\\u0001";
    // A backslash that doesn't start a whole escape: an escape cut in half, in a message with no backslash of its own.
    private static final Pattern CUT_ESCAPE = Pattern.compile("\\\\(?!u[0-9a-f]{4}|[nrt])");
    private static final Pattern LEFT_OUT = Pattern.compile("\\.\\.\\. \\((\\d+) more characters\\)'");

    private final RulesEngine<Map<String, Object>> engine =
            RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();

    /**
     * Returns the lines logged while {@code failure} happened, split at every line break {@code \R} matches, not only
     * at the ones {@link String#lines()} splits at.
     */
    private static List<String> logLines(Outcome<RuntimeException> failure) {
        return Arrays.asList(failure.logs().split("\\R"));
    }

    private static void assertNoForgedLine(Outcome<RuntimeException> failure) {
        assertTrue(logLines(failure).stream().noneMatch(line -> line.startsWith("[main] INFO com.example")),
                String.join("\n", logLines(failure)));
    }

    @Test
    @DisplayName("a fact name with \\n or \\r\\n is logged on one line, with the line break escaped")
    void factNameWithLineBreak() {
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
        for (String lineBreak : List.of("\n", "\r\n")) {
            FactStore<Object> facts = new FactMap<>();
            facts.setValue("a" + lineBreak + FORGED, 1);

            Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> engine.run(facts));

            String escaped = lineBreak.equals("\n") ? "a\\n" : "a\\r\\n";
            assertEquals("'" + escaped + FORGED + "' is not a valid fact name: rules can only refer to a fact named "
                    + "with a Java identifier", failure.thrown().getMessage());
            assertTrue(logLines(failure).stream()
                            .anyMatch(line -> line.endsWith("ERROR " + ENGINE_LOGGER + failure.thrown().getMessage())),
                    String.join("\n", logLines(failure)));
            assertNoForgedLine(failure);
        }
    }

    @Test
    @DisplayName("a 10,000-character fact name is shortened in the message")
    void longFactName() {
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("-".repeat(10_000), 1);

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> engine.run(facts));

        assertEquals("'" + "-".repeat(Failures.MAX_NAME_LENGTH) + "... (9800 more characters)' is not a valid fact "
                + "name: rules can only refer to a fact named with a Java identifier", failure.thrown().getMessage());
    }

    @Test
    @DisplayName("a rule name with a line break is escaped in run-time and compile-time failures")
    void ruleNameWithLineBreak() {
        engine.load(List.of(Rule.builder().ruleName("bad\n" + FORGED).condition("missing > 1").action("x")
                .build()));
        Outcome<RuntimeException> run = capture(RuntimeException.class, () -> engine.run(new FactMap<>()));
        assertTrue(run.thrown().getMessage().startsWith("Failed to evaluate condition for rule 'bad\\n" + FORGED
                + "': "), run.thrown().getMessage());
        assertNoForgedLine(run);

        Outcome<RuntimeException> duplicate = capture(RuntimeException.class, () -> engine.load(List.of(
                Rule.builder().ruleName("dup\r\n" + FORGED).condition("true").action("x").build(),
                Rule.builder().ruleName("dup\r\n" + FORGED).condition("true").action("x").build())));
        assertInstanceOf(RuleCompilationException.class, duplicate.thrown());
        assertEquals("Duplicate rule name 'dup\\r\\n" + FORGED + "'", duplicate.thrown().getMessage());
        assertNoForgedLine(duplicate);

        Outcome<RuntimeException> blank = capture(RuntimeException.class, () -> engine.load(List.of(
                Rule.builder().ruleName("blank\n" + FORGED).condition(" ").action("x").build())));
        assertEquals("Rule 'blank\\n" + FORGED + "' has a blank condition expression",
                blank.thrown().getMessage());

        Outcome<RuntimeException> language = capture(RuntimeException.class, () -> engine.load(List.of(
                Rule.builder().ruleName("r").language("lang\n" + FORGED).condition("true").action("x").build())));
        assertTrue(language.thrown().getMessage().startsWith("Rule 'r' is written in 'lang\\n" + FORGED + "', "),
                language.thrown().getMessage());
        assertNoForgedLine(language);
    }

    @Test
    @DisplayName("quote escapes control characters and line separators, and shortens names over the limit")
    void quote() {
        String controls = "a\tb" + (char) 0 + "c" + (char) 0x2028 + "d" + (char) 0x2029 + "e" + (char) 0x7f + "f"
                + (char) 0x85;

        assertEquals("a\\tb\\u0000c\\u2028d\\u2029e\\u007ff\\u0085", Failures.quote(controls));
        assertEquals("plain-name_1 é", Failures.quote("plain-name_1 é"));
        String limit = "x".repeat(Failures.MAX_NAME_LENGTH);
        assertEquals(limit, Failures.quote(limit));
        assertEquals(limit + "... (1 more characters)", Failures.quote(limit + "y"));
    }

    @Test
    @DisplayName("a lone surrogate in a fact name is escaped, so the log can't show it as ? and differ from it")
    void loneSurrogateInFactName() {
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("output").build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("a" + (char) 0xd800, 1);

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> engine.run(facts));

        assertEquals("'a\\ud800' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier", failure.thrown().getMessage());
    }

    @Test
    @DisplayName("the rule name on the exception stays unescaped")
    void exceptionKeepsRawName() {
        engine.load(List.of(Rule.builder().ruleName("raw\nname").condition("missing > 1").action("x").build()));

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> engine.run(new FactMap<>()));

        assertEquals("raw\nname", assertInstanceOf(RuleExecutionException.class, failure.thrown()).getRuleName());
    }

    @Test
    @DisplayName("a long fact name of control characters fits in the message whole, with no escape cut in half")
    void longFactNameOfControlCharacters() {
        engine.load(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
        String name = "-" + CONTROLS;
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, 1);

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> engine.run(facts));

        String message = failure.thrown().getMessage();
        assertTrue(message.endsWith("' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier"), message);
        assertLoggedWhole(failure, message);
        assertFitsWhole(message, name);
    }

    @Test
    @DisplayName("a long declared fact name of control characters fits in the message load() reports, whole")
    void longDeclaredFactNameOfControlCharacters() {
        String name = "-" + CONTROLS;
        RulesEngine<Map<String, Object>> declared = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .fact(name, Integer.class).build();

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> declared.load(List.of(
                Rule.builder().ruleName("r").condition("true").action("x").build())));

        String message = failure.thrown().getCause().getMessage();
        assertTrue(message.endsWith("' is not a valid fact name: rules can only refer to a fact named with a Java "
                + "identifier"), message);
        assertLoggedWhole(failure, message);
        assertFitsWhole(message, name);
        assertTrue(failure.thrown().getMessage().endsWith("' can't be used: " + message),
                failure.thrown().getMessage());
    }

    @Test
    @DisplayName("a long class name of control characters, rejected as a fact name, fits in the message whole")
    void longClassNameOfControlCharacters() {
        // Control characters are identifier-ignorable, so the name is an identifier, and the class that has it is
        // the reason MVEL can't see the fact.
        String name = "X" + CONTROLS;
        String pkg = NameEscapingTest.class.getPackageName();
        ClassLoader loader = new OneClassLoader(pkg + "." + name);
        RulesEngine<Map<String, Object>> imported = TestSupport.withContextClassLoader(loader, () -> {
            RulesEngine<Map<String, Object>> built = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                    .imports(pkg).build();
            built.load(List.of(Rule.builder().ruleName("r").condition("true").action("x").build()));
            return built;
        });
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, 1);

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> imported.run(facts));

        String message = failure.thrown().getMessage();
        assertTrue(message.endsWith("' cannot be used as a fact name: MVEL reads it as a keyword or class name, so "
                + "rules would never see the fact"), message);
        assertLoggedWhole(failure, message);
        assertFitsWhole(message, name);
    }

    @Test
    @DisplayName("a long dynamic declared fact's name of control characters fits in strongTyping's message, whole")
    void longDynamicFactNameOfControlCharacters() {
        String name = "X" + CONTROLS;
        assertCompilerRejects(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).fact(name, HashMap.class)
                .requireDeclaredFacts().option("mvel", "strongTyping", "true"), name, "' is declared as "
                + "java.util.HashMap, whose members MVEL can't check");
    }

    @Test
    @DisplayName("a long option name of control characters fits in the message MVEL rejects it with, whole")
    void longOptionNameOfControlCharacters() {
        String name = "X" + CONTROLS;
        assertCompilerRejects(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .option("mvel", name, "true"), name, "'; its only option is strongTyping");
    }

    @Test
    @DisplayName("a long strongTyping value of control characters fits in the message MVEL rejects it with, whole")
    void longOptionValueOfControlCharacters() {
        String value = "X" + CONTROLS;
        assertCompilerRejects(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .option("mvel", "strongTyping", value), value, "'");
    }

    // #913: the class name was part of the text around the fact's name, so 1,100 characters of it left the fact's name
    // no room: it showed as "... (5 more characters)", and the line break in the class name was left raw.
    @Test
    @DisplayName("a long dynamic declared type's class name leaves the fact's name room, and is escaped and shortened")
    void longDeclaredClassNameKeepsFactName() {
        Class<?> type = hashMapNamed("Big\nMap" + "M".repeat(1100));

        String message = assertClassNameFits(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .fact("order", type).requireDeclaredFacts().option("mvel", "strongTyping", "true"),
                "fact 'order' is declared as ", type.getName(), ", whose members MVEL can't check");

        assertTrue(message.contains("Big\\nMap"), message);
    }

    @Test
    @DisplayName("a dynamic declared type's class name with a tab and format characters is escaped, and fits")
    void declaredClassNameEscaped() {
        Class<?> type = hashMapNamed("Z\t" + "​".repeat(199));

        assertClassNameFits(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .fact("order", type).requireDeclaredFacts().option("mvel", "strongTyping", "true"),
                "fact 'order' is declared as ", type.getName(), ", whose members MVEL can't check");
    }

    @Test
    @DisplayName("a dynamic output type's class name with a tab and format characters is escaped, and fits")
    void outputClassNameEscaped() {
        Class<?> type = hashMapNamed("Out\tType" + "​".repeat(300));

        assertClassNameFits(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .outputType(mapType(type)).fact("applicant", Integer.class).requireDeclaredFacts()
                .option("mvel", "strongTyping", "true"), "the output type is ", type.getName(),
                ", and an action writes to the output; build the engine with outputType(...)");
    }

    // The fact's name gets the room first, but the class name keeps room for its first 100 characters.
    @Test
    @DisplayName("when a dynamic declared fact's name and its class name are both long, both are shown, cut to fit")
    void longFactAndClassNames() {
        String name = "X" + CONTROLS;
        Class<?> type = hashMapNamed("Big\nMap" + "M".repeat(1100));

        String message = assertClassNameFits(RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .fact(name, type).requireDeclaredFacts().option("mvel", "strongTyping", "true"),
                "' is declared as ", type.getName(), ", whose members MVEL can't check");

        assertFitsWhole(message, name);
    }

    /** Returns a class by the simple name given, in this test's package, that extends {@link HashMap}. */
    private static Class<?> hashMapNamed(String simpleName) {
        String className = NameEscapingTest.class.getPackageName() + "." + simpleName;
        try {
            return Class.forName(className, false, new OneClassLoader(className, "java/util/HashMap"));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? super Map<String, Object>> mapType(Class<?> type) {
        return (Class<? super Map<String, Object>>) type;
    }

    /**
     * Asserts that loading a rule list fails because MVEL rejects a type, with a message that names the class escaped,
     * cut between escapes, and fits whole, and whose count of what was left out of the class name counts its own
     * characters. The class name is long enough to be cut.
     *
     * @return The message
     */
    private static String assertClassNameFits(RulesEngineBuilder<Map<String, Object>> builder, String before,
                                              String className, String after) {
        RulesEngine<Map<String, Object>> rejecting = builder.build();

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> rejecting.load(List.of(
                Rule.builder().ruleName("r").condition("true").action("x").build())));

        assertInstanceOf(RuleCompilationException.class, failure.thrown());
        String message = failure.thrown().getCause().getMessage();
        assertTrue(message.length() <= Failures.MAX_DESCRIPTION_LENGTH, message.length() + ": " + message);
        assertEquals(Failures.escape(message), message);
        assertFalse(CUT_ESCAPE.matcher(message).find(), message);
        assertLoggedWhole(failure, message);
        assertTrue(failure.thrown().getMessage().endsWith("failed to create a compiler: " + message),
                failure.thrown().getMessage());
        Matcher shown = Pattern.compile(Pattern.quote(before) + "(.+)\\.\\.\\. \\((\\d+) more characters\\)"
                + Pattern.quote(after) + "$").matcher(message);
        assertTrue(shown.find(), message);
        int shownLength = shown.group(1).replaceAll("\\\\(u[0-9a-f]{4}|[nrt])", "-").length();
        assertTrue(shownLength >= 100, message);
        assertEquals(className.length() - shownLength, Integer.parseInt(shown.group(2)), message);
        return message;
    }

    /** Asserts that loading a rule list fails because MVEL rejects a name, with a message that fits, whole. */
    private static void assertCompilerRejects(RulesEngineBuilder<Map<String, Object>> builder, String name,
                                              String after) {
        RulesEngine<Map<String, Object>> rejecting = builder.build();

        Outcome<RuntimeException> failure = capture(RuntimeException.class, () -> rejecting.load(List.of(
                Rule.builder().ruleName("r").condition("true").action("x").build())));

        assertInstanceOf(RuleCompilationException.class, failure.thrown());
        String message = failure.thrown().getCause().getMessage();
        assertTrue(message.endsWith(after), message);
        assertLoggedWhole(failure, message);
        assertFitsWhole(message, name);
        assertTrue(failure.thrown().getMessage().endsWith("failed to create a compiler: " + message),
                failure.thrown().getMessage());
    }

    /**
     * Asserts that a message quoting a long name of control characters is no longer than the engine keeps of a message,
     * so the engine reports it whole, that no escape in it is cut in half, and that the count of what was left out
     * counts the name's own characters, not escaped ones: the name's first character and each escape shown are one.
     */
    private static void assertFitsWhole(String message, String name) {
        assertTrue(message.length() <= Failures.MAX_DESCRIPTION_LENGTH, message.length() + ": " + message);
        assertFalse(CUT_ESCAPE.matcher(message).find(), message);
        Matcher leftOut = LEFT_OUT.matcher(message);
        assertTrue(leftOut.find(), message);
        int shown = 1 + (message.length() - message.replace(ESCAPED_CONTROL, "").length()) / ESCAPED_CONTROL.length();
        assertEquals(name.length() - shown, Integer.parseInt(leftOut.group(1)), message);
    }

    /** Asserts that the engine logged {@code message} whole at the end of an ERROR line, with no escape cut in half. */
    private static void assertLoggedWhole(Outcome<RuntimeException> failure, String message) {
        List<String> errors = failure.lines("ERROR");
        assertTrue(errors.stream().noneMatch(line -> CUT_ESCAPE.matcher(line).find()), failure.logs());
        assertTrue(errors.stream().anyMatch(line -> line.endsWith(message)), failure.logs());
    }

    /**
     * A class loader that has one class more than its parent, an empty one by the name given, and serves a class file
     * for it, so a lookup of the class in its package finds it.
     */
    private static final class OneClassLoader extends ClassLoader {

        private final String className;
        private final String superName;

        OneClassLoader(String className) {
            this(className, "java/lang/Object");
        }

        /**
         * Creates a loader whose one class extends the class given.
         *
         * @param className The class's binary name
         * @param superName Its superclass's internal name, such as {@code java/util/HashMap}
         */
        OneClassLoader(String className, String superName) {
            super(NameEscapingTest.class.getClassLoader());
            this.className = className;
            this.superName = superName;
        }

        @Override
        public URL getResource(String name) {
            return name.equals(className.replace('.', '/') + ".class")
                    ? super.getResource(NameEscapingTest.class.getName().replace('.', '/') + ".class")
                    : super.getResource(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(className)) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = emptyClass(name.replace('.', '/'), superName);
            return defineClass(name, bytes, 0, bytes.length);
        }

        /**
         * Writes the class file of an empty public class that extends the class given, in the format of Java 8. It
         * has no constructor, so it can't be created, only declared as a type.
         */
        private static byte[] emptyClass(String internalName, String superName) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(0xCAFEBABE);
                out.writeShort(0); // minor version
                out.writeShort(52); // major version: Java 8
                out.writeShort(5); // constant pool count: 4 entries, from 1
                out.writeByte(1); // #1 Utf8: the class's name
                out.writeUTF(internalName);
                out.writeByte(7); // #2 Class #1
                out.writeShort(1);
                out.writeByte(1); // #3 Utf8: its superclass's name
                out.writeUTF(superName);
                out.writeByte(7); // #4 Class #3
                out.writeShort(3);
                out.writeShort(0x21); // ACC_PUBLIC | ACC_SUPER
                out.writeShort(2); // this class
                out.writeShort(4); // superclass
                out.writeShort(0); // interfaces
                out.writeShort(0); // fields
                out.writeShort(0); // methods
                out.writeShort(0); // attributes
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return bytes.toByteArray();
        }
    }
}
