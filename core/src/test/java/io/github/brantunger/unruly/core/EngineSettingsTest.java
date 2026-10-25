package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
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
}
