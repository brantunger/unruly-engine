package io.github.brantunger.unruly.mvel.warmup;

/**
 * The value {@link io.github.brantunger.unruly.mvel.MvelExpressionLanguage#prepare()} evaluates a property read, a
 * method call and a call with a literal argument on, once each, through MVEL's public API: a JVM's first property
 * read, first method call and first literal argument load classes MVEL evaluates them with, which a first run deep in
 * a stack would otherwise load. Public, as MVEL calls a method by reflection only on a public class; in a package the
 * module exports only to MVEL, so it adds nothing to the API. The mvel jar's {@code reflect-config.json} registers it
 * for a native image. Not for applications to use.
 */
public final class WarmUpTarget {

    /**
     * Creates the value.
     */
    public WarmUpTarget() {
        // Nothing to set up.
    }

    /**
     * The property the warm-up reads, through this getter.
     *
     * @return {@code true}
     */
    public boolean isReady() {
        return true;
    }

    /**
     * The method the warm-up calls.
     *
     * @return {@code true}
     */
    public boolean check() {
        return true;
    }

    /**
     * The method the warm-up calls with a literal argument.
     *
     * @param value The argument, which it ignores
     * @return {@code true}
     */
    public boolean accepts(String value) {
        return true;
    }
}
