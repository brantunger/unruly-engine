package io.github.brantunger.unruly;

import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.core.Engines;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2.0 requires Java 21. The API compatibility check compares members, not class-file versions, so this is the only
 * check that the published classes really target Java 21.
 */
@DisplayName("class file version")
class ClassFileVersionTest {

    private static final int MAGIC = 0xCAFEBABE;
    private static final int JAVA_21 = 65;

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {RulesEngine.class, Engines.class, MvelExpressionLanguage.class})
    @DisplayName("the library's classes are compiled for Java 21")
    void compiledForJava21(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "class file for " + type.getName());
            DataInputStream data = new DataInputStream(in);

            assertEquals(MAGIC, data.readInt(), "class file magic number");
            data.readUnsignedShort(); // minor version
            assertEquals(JAVA_21, data.readUnsignedShort(), "major version of " + type.getName());
        }
    }
}
