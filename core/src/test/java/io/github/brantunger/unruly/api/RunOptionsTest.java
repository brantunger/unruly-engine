package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RunOptions holds one run's settings, and changes nothing unless a setting is given")
class RunOptionsTest {

    @Test
    @DisplayName("the default options set no timeout, and are one shared instance")
    void defaults() {
        assertNull(RunOptions.defaults().timeout());
        assertSame(RunOptions.defaults(), RunOptions.defaults());
        assertEquals(Set.of(), RunOptions.defaults().tags());
        assertEquals("RunOptions(timeout=the engine's, tags=any)", RunOptions.defaults().toString());
    }

    @Test
    @DisplayName("a timeout is set on a copy, so shared options never change")
    void withTimeoutCopies() {
        RunOptions shared = RunOptions.defaults();

        RunOptions timed = shared.withTimeout(Duration.ofMillis(200));

        assertNotSame(shared, timed);
        assertNull(shared.timeout());
        assertEquals(Duration.ofMillis(200), timed.timeout());
        assertEquals(Duration.ofSeconds(1), RunOptions.withTimeoutOf(Duration.ofSeconds(1)).timeout());
        assertEquals("RunOptions(timeout=PT0.2S, tags=any)", timed.toString());
    }

    @Test
    @DisplayName("tags are set on a copy, and each setting keeps the other")
    void withTagsCopies() {
        RunOptions timed = RunOptions.withTimeoutOf(Duration.ofMillis(200));

        RunOptions tagged = timed.withTags(List.of("retail", "eu", "eu"));
        RunOptions retimed = tagged.withTimeout(Duration.ofSeconds(1));

        assertNotSame(timed, tagged);
        assertEquals(Set.of(), timed.tags());
        assertEquals(List.of("eu", "retail"), List.copyOf(tagged.tags()), "in String order, each tag once");
        assertEquals(Duration.ofMillis(200), tagged.timeout(), "setting tags keeps the timeout");
        assertEquals(tagged.tags(), retimed.tags(), "setting a timeout keeps the tags");
        assertEquals(Set.of("uk"), tagged.withTags(Set.of("uk")).tags(), "new tags replace the old ones");
        assertEquals("RunOptions(timeout=PT0.2S, tags=[eu, retail])", tagged.toString());
        assertThrows(UnsupportedOperationException.class, () -> tagged.tags().add("uk"));
    }

    @Test
    @DisplayName("tags must be a set of names, none null or blank, and not empty")
    void tagsMustBeNames() {
        RunOptions options = RunOptions.defaults();
        assertEquals("tags must not be null",
                assertThrows(NullPointerException.class, () -> options.withTags(null)).getMessage());
        assertEquals("tags must not be empty; leave them unset to use every rule",
                assertThrows(IllegalArgumentException.class, () -> options.withTags(Set.of())).getMessage());
        assertEquals("tags must not contain null, but were [eu, null]", assertThrows(IllegalArgumentException.class,
                () -> options.withTags(Arrays.asList("eu", null))).getMessage());
        assertEquals("tags must not contain a blank tag, but were [eu,  ]", assertThrows(IllegalArgumentException.class,
                () -> options.withTags(List.of("eu", " "))).getMessage());
    }

    @Test
    @DisplayName("the tags in a rejection are escaped, and the list is cut at 1,000 characters before it's escaped")
    void tagsEscapedInRejections() {
        RunOptions options = RunOptions.defaults();
        assertEquals("tags must not contain null, but were [eu\\n[main] INFO forged, null]",
                assertThrows(IllegalArgumentException.class,
                        () -> options.withTags(Arrays.asList("eu\n[main] INFO forged", null))).getMessage());
        String rlo = "\u202e".repeat(200);
        String escaped = "\\u202e".repeat(200);
        String cut = "... (1 more characters)";

        String message = assertThrows(IllegalArgumentException.class,
                () -> options.withTags(List.of(rlo, rlo + "a", rlo + "b", rlo + "c", rlo + "d", " "))).getMessage();

        // Each tag is cut to 200 characters, which makes the list 1,105; its first 1,000 end inside the fifth tag.
        assertEquals("tags must not contain a blank tag, but were [" + escaped + ", " + escaped + cut + ", "
                + escaped + cut + ", " + escaped + cut + ", " + "\\u202e".repeat(122) + "... (105 more characters)",
                message);
    }

    @Test
    @DisplayName("toString() escapes the tags")
    void toStringEscapesTags() {
        assertEquals("RunOptions(timeout=the engine's, tags=[eu\\n[main] INFO forged])",
                RunOptions.defaults().withTags(List.of("eu\n[main] INFO forged")).toString());
    }

    @Test
    @DisplayName("a timeout must be a positive duration")
    void timeoutMustBePositive() {
        assertEquals("timeout must not be null",
                assertThrows(NullPointerException.class, () -> RunOptions.withTimeoutOf(null)).getMessage());
        assertEquals("timeout must be positive, but was PT0S", assertThrows(IllegalArgumentException.class,
                () -> RunOptions.withTimeoutOf(Duration.ZERO)).getMessage());
        assertEquals("timeout must be positive, but was PT-1S", assertThrows(IllegalArgumentException.class,
                () -> RunOptions.defaults().withTimeout(Duration.ofSeconds(-1))).getMessage());
    }

    @Test
    @DisplayName("RunOptions is a final class, so a later release can add settings")
    void finalClass() {
        assertTrue(Modifier.isFinal(RunOptions.class.getModifiers()));
        assertFalse(RunOptions.class.isRecord());
    }
}
