package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.language.DoubleNumbers;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest.WritableApplicant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the contract test kit's bean writer sets a bean, not the map, with the default writer")
class BeanWriterTest {

    @Test
    @DisplayName("a property is set on the bean through the default writer, converted by the converter it's given")
    void setsTheBeanWithTheConverter() throws Exception {
        WritableApplicant applicant = new WritableApplicant(0);
        Map<String, Object> output = new HashMap<>();

        new BeanWriter(applicant).set(output, "creditScore", 3.0, DoubleNumbers::convert);

        assertEquals(3, applicant.getCreditScore());
        assertEquals(Map.of(), output, "the map isn't written");
    }

    @Test
    @DisplayName("without a converter, as an engine released before it calls the writer, nothing is converted")
    void setsTheBeanWithoutConverting() throws Exception {
        WritableApplicant applicant = new WritableApplicant(0);
        BeanWriter writer = new BeanWriter(applicant);

        writer.set(new HashMap<>(), "creditScore", 2);
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> writer.set(new HashMap<>(), "creditScore", 3.0));

        assertEquals(2, applicant.getCreditScore());
        assertTrue(thrown.getMessage().contains("has no public method setCreditScore that accepts a java.lang.Double"),
                thrown.getMessage());
    }
}
