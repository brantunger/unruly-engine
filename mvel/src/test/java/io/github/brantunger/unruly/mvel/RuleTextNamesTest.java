package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

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

    @Test
    @DisplayName("each identifier, wherever it is, and each word and its parts between dots")
    void identifiersAndWords() {
        assertEquals(Set.of("my-fact", "my", "fact", "1", "a.b", "a", "b", "s", "//", "c", "def", "f", "x", "==", "&&",
                "a.b('s')", "b('s')", "f(x)", "{}"), names("my-fact == 1 && a.b('s') // c\ndef f(x) {}"));
    }

    @Test
    @DisplayName("a comma that starts a word is kept on it, as MVEL reads (,a) == 5 by the name ,a")
    void leadingComma() {
        assertEquals(Set.of("a", "(,a)", ",a", "==", "5", "x,y", "x", "y"), names("(,a) == 5 x,y"));
    }

    @Test
    @DisplayName("an operator ends a word too, as MVEL reads 1+,a by the name ,a, but a minus sign doesn't")
    void operatorsEndAWord() {
        Set<String> names = names("1+,a*2%b/**/ my-fact-1");

        assertTrue(names.containsAll(Set.of(",a", "2", "b", "my-fact-1")), names.toString());
        assertFalse(names.contains("my-fact"), names.toString());
        assertTrue(names("1%,a").contains(",a"), names("1%,a").toString());
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
