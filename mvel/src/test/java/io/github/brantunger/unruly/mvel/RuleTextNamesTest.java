package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.util.ParseTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RuleText#addNames} finds every name MVEL could read a fact by in an expression's text, and the words a rule's
 * author may have meant as one.
 */
@DisplayName("the names an MVEL expression could read a fact by are found in its text")
class RuleTextNamesTest {

    private static Set<String> names(String text) {
        Set<String> names = new HashSet<>();
        RuleText.addNames(text, names);
        return names;
    }

    /** The names RuleText.addNames found before #1089, copied from 9d17b8d7, to show none is lost. */
    private static Set<String> namesBefore1089(String text) {
        Set<String> names = new HashSet<>();
        int index = 0;
        while (index < text.length()) {
            if (!ParseTools.isIdentifierPart(text.charAt(index))) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < text.length() && ParseTools.isIdentifierPart(text.charAt(end))) {
                end++;
            }
            String run = text.substring(index, end);
            addName(run, names);
            addName(run.trim(), names);
            int part = index;
            for (int at = index; at <= end; at++) {
                if (at == end || RuleText.isMvelWhitespace(text.charAt(at))) {
                    addName(text.substring(part, at), names);
                    part = at + 1;
                }
            }
            index = end;
        }
        String ends = "()[]{};'\"=<>!&|?:";
        for (String passEnds : List.of("", ends + ",", ends, ends + "*/+%")) {
            int start = 0;
            for (int at = 0; at <= text.length(); at++) {
                if (at == text.length() || RuleText.isMvelWhitespace(text.charAt(at))
                        || passEnds.indexOf(text.charAt(at)) >= 0) {
                    String word = text.substring(start, at);
                    addName(word, names);
                    int part = 0;
                    for (int dot = word.indexOf('.'); dot >= 0; dot = word.indexOf('.', part)) {
                        addName(word.substring(part, dot), names);
                        part = dot + 1;
                    }
                    if (part > 0) {
                        addName(word.substring(part), names);
                    }
                    start = at + 1;
                }
            }
        }
        return names;
    }

    private static void addName(String name, Set<String> names) {
        if (!name.isEmpty()) {
            names.add(name);
        }
    }

    /** Every string up to five characters long over an alphabet with each kind of character the scan tells apart. */
    private static List<String> shortTexts() {
        String alphabet = "a1.-+,(/*\\ \u0001\ud835#\u00a0";
        List<String> texts = new ArrayList<>(List.of(""));
        List<String> shorter = List.of("");
        for (int length = 1; length <= 5; length++) {
            List<String> longer = new ArrayList<>();
            for (String text : shorter) {
                for (int at = 0; at < alphabet.length(); at++) {
                    longer.add(text + alphabet.charAt(at));
                }
            }
            texts.addAll(longer);
            shorter = longer;
        }
        return texts;
    }

    /** Every one-line string literal of this module's tests, which hold its rules' texts. */
    private static List<String> testLiterals() throws IOException {
        List<String> texts = new ArrayList<>();
        Pattern literal = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
        try (Stream<Path> files = Files.walk(Path.of("src", "test", "java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    texts.add(unescape(matcher.group(1)));
                }
            }
        }
        return texts;
    }

    /** A Java string literal's text as the string it stands for, for the escapes this module's tests use. */
    private static String unescape(String literal) {
        StringBuilder text = new StringBuilder();
        for (int at = 0; at < literal.length(); at++) {
            char c = literal.charAt(at);
            if (c != '\\' || at + 1 == literal.length()) {
                text.append(c);
                continue;
            }
            char escaped = literal.charAt(++at);
            switch (escaped) {
                case 'n' -> text.append('\n');
                case 't' -> text.append('\t');
                case 'r' -> text.append('\r');
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 's' -> text.append(' ');
                default -> text.append(escaped);
            }
        }
        return text.toString();
    }

    /**
     * Whether a text has a word, between MVEL's whitespace, with more characters that aren't part of an identifier
     * than an import has parts, one just after a keyword isdef counted twice, or more characters than an import may
     * have, which the scan gives up on, adding only some names (#1089).
     */
    private static boolean pastTheLimits(String text) {
        for (String word : text.split(RuleText.MVEL_WHITESPACE)) {
            int stops = 0;
            for (int at = 0; at < word.length(); at++) {
                if (!ParseTools.isIdentifierPart(word.charAt(at))) {
                    int keyword = at - "isdef".length();
                    boolean afterIsdef = keyword >= 0 && word.startsWith("isdef", keyword) && (keyword == 0
                            || "()[]{},;'\"=<>!&|?:*/+%-.".indexOf(word.charAt(keyword - 1)) >= 0);
                    stops += afterIsdef ? 2 : 1;
                }
            }
            if (stops > Imports.MAX_IMPORT_PARTS || word.length() > Imports.MAX_IMPORT_LENGTH) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("no name found before #1089 is lost, for every short text and every literal of the module's tests")
    void noNameLost() throws IOException {
        List<String> texts = shortTexts();
        List<String> literals = testLiterals();
        assertTrue(literals.size() > 5_000, "literals: " + literals.size());
        texts.addAll(literals);
        for (String text : texts) {
            if (pastTheLimits(text)) {
                continue;
            }
            Set<String> names = names(text);
            Set<String> before = namesBefore1089(text);
            if (!names.containsAll(before)) {
                before.removeAll(names);
                fail("lost " + before + " from " + text);
            }
        }
    }

    @Test
    @DisplayName("each identifier, wherever it is, and each word and its parts between dots")
    void identifiersAndWords() {
        Set<String> names = names("my-fact == 1 && a.b('s') // c\ndef f(x) {}");

        assertTrue(names.containsAll(Set.of("my-fact", "my", "fact", "1", "a.b", "a", "b", "s", "//", "c", "def", "f",
                "x", "==", "&&", "a.b('s')", "b('s')", "f(x)", "{}")),
                "the names found before #1089 are kept: " + names);
        assertEquals(Set.of("my-fact", "my", "fact", "1", "a.b", "a", "b", "s", "//", "c", "def", "f", "x", "==", "&&",
                "a.b('s')", "b('s')", "f(x)", "{}", "a.b(", "a.b('s", "a.b('s'", "b(", "b('s", "b('s'", "'s", "'s'",
                "'s')", "s'", "s')", ")", "f(x", "x)", "{", "}", "=", "&", "/"), names);
    }

    @Test
    @DisplayName("a comma that starts a word is kept on it, as MVEL reads (,a) == 5 by the name ,a")
    void leadingComma() {
        Set<String> names = names("(,a) == 5 x,y");

        assertTrue(names.containsAll(Set.of("a", "(,a)", ",a", "==", "5", "x,y", "x", "y")),
                "the names found before #1089 are kept: " + names);
        assertEquals(Set.of("a", "(,a)", ",a", "==", "5", "x,y", "x", "y", "(", "(,a", ",a)", "a)", "="), names);
    }

    @Test
    @DisplayName("an operator ends a word too, as MVEL reads 1+,a by the name ,a, and so does a minus sign (#1089)")
    void operatorsEndAWord() {
        Set<String> names = names("1+,a*2%b/**/ my-fact-1");

        assertTrue(names.containsAll(Set.of(",a", "2", "b", "my-fact-1")), names.toString());
        assertTrue(names.contains("my-fact"), names.toString());
        assertTrue(names("1%,a").contains(",a"), names("1%,a").toString());
    }

    @Test
    @DisplayName("each span of a word between its ends, a backslash and a high surrogate alone are names (#1089)")
    void spansBetweenEnds() {
        assertEquals(Set.of("my-fact-1", "my", "fact", "1", "my-fact", "fact-1"), names("my-fact-1"));
        assertTrue(names("a.b--").contains("a.b"), names("a.b--").toString());
        assertTrue(names("isdef a!b/**/").contains("a!b"), names("isdef a!b/**/").toString());
        assertTrue(names("max(1+\\a,2)").contains("\\a"), names("max(1+\\a,2)").toString());
        assertTrue(names("\\\\a").contains("\\"), names("\\\\a").toString());
        assertTrue(names("\ud835\udc65").contains("\ud835"), names("\ud835\udc65").toString());
    }

    @Test
    @DisplayName("a span starts after isdef glued to a name, and stops before what isn't part of an identifier (#1089)")
    void spansAfterIsdefAndBeforeStops() {
        assertTrue(names("isdef#a").contains("#a"), names("isdef#a").toString());
        assertTrue(names("(isdef\\a)").contains("\\a"), names("(isdef\\a)").toString());
        assertFalse(names("xisdef#a").contains("#a"), names("xisdef#a").toString());
        assertFalse(names("isdefa").contains("a"), names("isdefa").toString());
        assertEquals(Set.of("isde", "isde#a", "a"), names("isde#a"));
        assertTrue(names(",a#b").contains(",a"), names(",a#b").toString());
        assertTrue(names(",a\u00a0").contains(",a"), names(",a\u00a0").toString());
    }

    @Test
    @DisplayName("a run with MVEL's whitespace in it, which is part of an identifier too, adds its trimmed parts")
    void controlCharactersInARun() {
        assertEquals(Set.of("\u0001a\u0001b\u0001", "a\u0001b", "a", "b"), names("\u0001a\u0001b\u0001"));
        assertEquals(Set.of("\u0001"), names("\u0001"));
    }

    @Test
    @DisplayName("text with nothing MVEL reads as a name adds no name, and no empty one")
    void noNames() {
        assertEquals(Set.of("(", "+", ")"), names(" ( + ) "));
        assertTrue(names("").isEmpty());
    }
}
