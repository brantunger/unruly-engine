package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MvelExpressionLanguage")
class MvelExpressionLanguageTest {

    @Test
    @DisplayName("is named mvel")
    void named() {
        assertEquals("mvel", new MvelExpressionLanguage().name());
        assertEquals("mvel", MvelExpressionLanguage.LANGUAGE_NAME);
    }

    @Test
    @DisplayName("newCompiler names a null context")
    void newCompilerRejectsNull() {
        MvelExpressionLanguage language = new MvelExpressionLanguage();

        assertEquals("context must not be null",
                assertThrows(NullPointerException.class, () -> language.newCompiler(null)).getMessage());
    }
}
