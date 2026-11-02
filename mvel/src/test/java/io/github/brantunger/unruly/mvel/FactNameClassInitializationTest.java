package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import probe.classinit.InitRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The check of a fact's name loads a class an imported package has under that name without initializing it: if it
 * ran, a static initializer that throws would make the load fail, so the check would read the name as no class and
 * accept a fact MVEL then reads the class in the place of, the shape of #1255 (#1310). The classes are named here only
 * by their names, so nothing but the engine could initialize them.
 */
@DisplayName("a fact's name is checked against a class without running the class's static initializer (#1310)")
class FactNameClassInitializationTest {

    private static final String PACKAGE = "probe.classinit";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ThrowingInit", "QuietInit"})
    @DisplayName("a fact named like a class in an imported package is rejected, and the class isn't initialized")
    void rejectedWithoutInitializing(String className) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .imports(PACKAGE).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition(className + " == 5")
                .action("output.put('k', 'fired')").build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.run(new FactMap<>(new Fact<>(className, 5))));

        assertEquals("'" + className + "' cannot be used as a fact name: MVEL reads it as a keyword or class name, so"
                + " rules would never see the fact", ex.getMessage());
        assertFalse(InitRecord.hasRun(className), className + "'s static initializer ran");
    }
}
