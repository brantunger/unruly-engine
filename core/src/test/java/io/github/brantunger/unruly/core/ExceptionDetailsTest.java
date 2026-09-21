package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("exceptions keep their cause and name the null argument")
class ExceptionDetailsTest {

    private final RulesEngineBuilder<Map<String, Object>> builder =
            RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).language(new ToyExpressionLanguage());

    @Test
    @DisplayName("a rejected import keeps the failed class lookup as its cause")
    void rejectedImportKeepsCause() {
        builder.imports("not a package!!");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);

        assertInstanceOf(ClassNotFoundException.class, ex.getCause());
    }

    @Test
    @DisplayName("imports(null) names the argument")
    void importsNullMessage() {
        NullPointerException array = assertThrows(NullPointerException.class, () -> builder.imports((String[]) null));
        NullPointerException collection = assertThrows(NullPointerException.class,
                () -> builder.imports((Collection<String>) null));

        assertEquals("names must not be null", array.getMessage());
        assertEquals("names must not be null", collection.getMessage());
    }
}
