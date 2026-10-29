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

    // A name of a package's shape that isn't a class is a package import, so no rejection has a failed lookup to keep;
    // one for a class that exists but can't be loaded keeps the linkage error, as UnloadableImportTest checks.
    @Test
    @DisplayName("an import that isn't a valid package name is rejected before it is looked up, so with no cause"
            + " (#1272)")
    void malformedImportHasNoCause() {
        builder.imports("not a package!!");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("'not a package!!' is neither a class nor a valid package name", ex.getMessage());
        assertNull(ex.getCause());
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
