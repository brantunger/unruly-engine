package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an engine's settings are checked when they're resolved")
class EngineSettingsTest {

    @Test
    @DisplayName("a declared fact can't have a blank name")
    void blankDeclaredFactRejected() {
        EngineConfiguration<Object> configuration = new EngineConfiguration<>(Map.of(
                ToyExpressionLanguage.LANGUAGE_NAME, new ToyExpressionLanguage()), null, List.of(), List.of(),
                CopyLimit.none(), 0, null, Clock.systemUTC(), Object.class, OutputWriter.beansAndMaps(), Map.of(),
                Map.of("\t", String.class), false, Map.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EngineSettings.resolve(configuration));

        assertEquals("fact name must not be blank", ex.getMessage());
    }

    @Test
    @DisplayName("the languages in use list the default first, even when languages() lists it second (#1310)")
    void defaultLanguageInUseFirst() {
        // Map.copyOf, as EngineConfiguration keeps its languages, lists them in an order that varies between JVM runs,
        // so the default is the one it lists second, which a copy of a copy keeps.
        Map<String, ExpressionLanguage> languages = Map.copyOf(Map.of("a", new ToyExpressionLanguage("a"),
                "b", new ToyExpressionLanguage("b")));
        Iterator<String> listed = languages.keySet().iterator();
        String first = listed.next();
        String second = listed.next();
        EngineConfiguration<Object> configuration = new EngineConfiguration<>(languages, second, List.of(),
                List.of(), CopyLimit.none(), 0, null, Clock.systemUTC(), Object.class, OutputWriter.beansAndMaps(),
                Map.of(), Map.of(), false, Map.of());
        assertEquals(List.of(first, second), List.copyOf(configuration.languages().keySet()),
                "the default is listed second");

        EngineSettings settings = EngineSettings.resolve(configuration);

        assertEquals(List.of(second, first), settings.languagesInUse().stream().map(ExpressionLanguage::name).toList());
        assertSame(languages.get(second), settings.languagesInUse().get(0));
        assertSame(languages.get(first), settings.languagesInUse().get(1));
    }
}
