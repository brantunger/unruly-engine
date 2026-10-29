package io.github.brantunger.unruly.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "wrong name" check reads only the side of the message this JVM writes the name asked for on (#1261). Both
 * orders are checked here on any JVM, through the overload that takes the side.
 */
@DisplayName("Lookups.isWrongName reads the name asked for only on the side the JVM writes it (#1261)")
class LookupsTest {

    @ParameterizedTest(name = "{0} for {1}, named last: {2}")
    @CsvSource(delimiter = '|', value = {
            "p/a (wrong name: p/A) | p.a | false | true",
            "p/a (wrong name: p/A) | p.a | true  | false",
            "p/A (wrong name: p/a) | p.a | true  | true",
            "p/A (wrong name: p/a) | p.a | false | false",
            // A class whose superclass is named like it in another case: HotSpot's error for the superclass names the
            // class on the side OpenJ9 writes the name asked for, and the other way round.
            "p/base (wrong name: p/Base) | p.Base | false | false",
            "p/Base (wrong name: p/base) | p.Base | true  | false",
            "p/Rules$Limit (wrong name: p/rules$Limit) | p.Rules$Limit | false | true",
            "p/rules$Limit (wrong name: p/Rules$Limit) | p.Rules$Limit | true  | true",
            "p/Base | p.Base | false | false",
            "p/Base | p.Base | true  | false"})
    @DisplayName("a message is told by the name on the side given, and not by the other")
    void sideGivenIsRead(String message, String name, boolean lookupLast, boolean wrongName) {
        assertEquals(wrongName, Lookups.isWrongName(new NoClassDefFoundError(message), name, lookupLast));
    }

    @Test
    @DisplayName("an error with no message is no \"wrong name\" error on either side")
    void noMessageOnEitherSide() {
        assertFalse(Lookups.isWrongName(new NoClassDefFoundError(), "p.a", false));
        assertFalse(Lookups.isWrongName(new NoClassDefFoundError(), "p.a", true));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "Eclipse OpenJ9 VM | true",
            "OpenJ9 | true",
            "OpenJDK 64-Bit Server VM | false",
            "Java HotSpot(TM) 64-Bit Server VM | false",
            "Substrate VM | false",
            "OpenJ | false",
            "'' | false"})
    @DisplayName("OpenJ9 is told by its java.vm.name, whoever builds it")
    void openJ9Told(String vmName, boolean lookupLast) {
        assertEquals(lookupLast, Lookups.namesLookupLast(vmName));
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("a JVM with no java.vm.name is taken to write the name first, as HotSpot does")
    void noVmName(String vmName) {
        assertFalse(Lookups.namesLookupLast(vmName));
    }

    @Test
    @DisplayName("the public check reads the side this JVM writes")
    void thisJvmsSide() {
        boolean onOpenJ9 = System.getProperty("java.vm.name", "").contains("OpenJ9");

        assertEquals(!onOpenJ9, Lookups.isWrongName(new NoClassDefFoundError("p/a (wrong name: p/A)"), "p.a"));
        assertEquals(onOpenJ9, Lookups.isWrongName(new NoClassDefFoundError("p/A (wrong name: p/a)"), "p.a"));
        assertFalse(Lookups.isWrongName(new LinkageError("p/a (wrong name: p/A)"), "p.a"));
    }
}
