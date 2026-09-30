package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.test.ExpressionLanguageContractTest.WritableApplicant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("the contract test kit's writable applicant")
class WritableApplicantTest {

    @Test
    @DisplayName("the writable applicant says what credit score it holds (#847)")
    void writableApplicantPrints() {
        WritableApplicant applicant = new WritableApplicant(750);
        applicant.setCreditScore(1);

        assertEquals("WritableApplicant[creditScore=1]", applicant.toString());
    }
}
