package io.github.brantunger.unruly.mvel;

/**
 * Run as its own JVM by {@link BarePrepareInitializationTest}: prepares MVEL with no engine, as a language used
 * through the contract kit's contexts is, printing a line before and after, so the test can tell what
 * {@code prepare()} initialized.
 */
final class BarePrepareScenario {

    static final String BEFORE = "PREPARE STARTS";
    static final String AFTER = "PREPARE ENDED";

    private BarePrepareScenario() {
    }

    public static void main(String[] args) {
        MvelExpressionLanguage language = new MvelExpressionLanguage();
        System.out.println(BEFORE);
        language.prepare();
        System.out.println(AFTER);
    }
}
