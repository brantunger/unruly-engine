package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.hidden.HiddenOutputs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.GenericSignatureFormatError;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default {@link OutputWriter} reaches an output's setters the way {@link FactProperties} reaches a fact's
 * getters, so an output class the engine can read it can also write: through a public interface the class implements,
 * or directly where its package is open to the engine's module (#363). A setter it can't reach isn't skipped for a
 * less specific one it can, other than a bridge the compiler added for it (#549).
 */
@DisplayName("the default OutputWriter reaches a setter wherever FactProperties reaches a getter")
class OutputWriterAccessTest {

    /** A class whose setter throws. */
    public static final class Refusing {
        public void setScore(int score) {
            throw new IllegalStateException("no scores today");
        }
    }

    /** A class that isn't public, with a public setter. */
    static class Tagging {
        String tag;

        public void setTag(String tag) {
            this.tag = tag;
        }
    }

    /**
     * A public class that inherits the setter, so the compiler gives it a bridge with the same parameter, and declares
     * a setter of its own.
     */
    public static final class Tagged extends Tagging {
        String label;

        public void setLabel(String label) {
            this.label = label;
        }
    }

    @Test
    @DisplayName("a public class's setter inherited from a class that isn't public is written through its bridge")
    void inheritedFromAClassThatIsNotPublic() throws Exception {
        Tagged output = new Tagged();

        OutputWriter.beansAndMaps().set(output, "tag", "a");
        OutputWriter.beansAndMaps().set(output, "label", "b");

        assertEquals("a", output.tag);
        assertEquals("b", output.label);
    }

    @Test
    @DisplayName("a class that isn't public is written through the public interface that declares the setter")
    void throughAPublicInterface() throws Exception {
        HiddenOutputs.Scored output = HiddenOutputs.scored();

        OutputWriter.beansAndMaps().set(output, "score", 7);

        assertEquals(7, output.getScore());
        assertEquals(7, FactProperties.read(output, "score"), "read the same way");
    }

    @Test
    @DisplayName("a class that isn't public and has no interface is written directly on the class path")
    void directlyOnTheClassPath() throws Exception {
        Object output = HiddenOutputs.plain();

        OutputWriter.beansAndMaps().set(output, "score", 8);

        assertEquals(8, FactProperties.read(output, "score"));
    }

    @Test
    @DisplayName("a setter that throws is still reported as an InvocationTargetException with its cause")
    void setterThatThrows() {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> OutputWriter.beansAndMaps().set(new Refusing(), "score", 1));

        assertEquals("no scores today", thrown.getCause().getMessage());
    }

    private static final String API = """
            package com.example.api;

            public interface Scored {
                int getScore();

                void setScore(int score);
            }
            """;

    private static final String IMPLEMENTATION = """
            package com.example.impl;

            public final class Implementation implements com.example.api.Scored {
                private int score;

                public int getScore() {
                    return score;
                }

                public void setScore(int score) {
                    this.score = score;
                }
            }
            """;

    private static final String HIDDEN = """
            package com.example.api;

            final class Hidden {
                private int score;

                public int getScore() {
                    return score;
                }

                public void setScore(int score) {
                    this.score = score;
                }
            }
            """;

    private static final String SETTABLE = """
            package com.example.api;

            public interface Settable<T> {
                void setValue(T value);
            }
            """;

    private static final String GENERIC = """
            package com.example.api;

            final class Generic implements Settable<Integer> {
                private Integer value;

                public void setValue(Integer value) {
                    this.value = value;
                }

                public Integer getValue() {
                    return value;
                }
            }
            """;

    private static final String GENERIC_IMPLEMENTATION = """
            package com.example.impl;

            public final class GenericImplementation implements com.example.api.Settable<Integer> {
                public void setValue(Integer value) {
                }
            }
            """;

    private static final String COUNTING = """
            package com.example.api;

            class Counting {
                private Integer count;

                public void setCount(Integer count) {
                    this.count = count;
                }

                public Integer getCount() {
                    return count;
                }
            }
            """;

    private static final String COUNTED = """
            package com.example.api;

            public final class Counted extends Counting {
            }
            """;

    private static final String LABELLED = """
            package com.example.api;

            public interface Labelled {
                void setLabel(Object label);
            }
            """;

    private static final String LABEL = """
            package com.example.impl;

            public final class Label implements com.example.api.Labelled {
                public String setter;

                public void setLabel(Object label) {
                    setter = "Object";
                }

                public void setLabel(String label) {
                    setter = "String";
                }
            }
            """;

    private static final String BOXED_SCORE = """
            package com.example.impl;

            public final class BoxedScore implements com.example.api.Scored {
                public String setter;

                public int getScore() {
                    return 0;
                }

                public void setScore(int score) {
                    setter = "int";
                }

                public void setScore(Integer score) {
                    setter = "Integer";
                }
            }
            """;

    private static final String VALUE_BASE = """
            package com.example.impl;

            public class ValueBase {
                public String setter;

                public void setValue(Integer value) {
                    setter = "ValueBase.setValue(Integer)";
                }
            }
            """;

    private static final String INHERITED_VALUE = """
            package com.example.impl;

            public final class InheritedValue extends ValueBase implements com.example.api.Settable<Integer> {
            }
            """;

    private static final String GENERIC_BASE = """
            package com.example.impl;

            public class GenericBase implements com.example.api.Settable<Integer> {
                public String setter;

                public void setValue(Integer value) {
                    setter = "GenericBase.setValue(Integer)";
                }
            }
            """;

    private static final String OVERRIDING = """
            package com.example.impl;

            public final class Overriding extends GenericBase {
                @Override
                public void setValue(Integer value) {
                    setter = "Overriding.setValue(Integer)";
                }
            }
            """;

    private static final String TEXT_TOO = """
            package com.example.impl;

            public final class TextToo implements com.example.api.Settable<Integer> {
                public String setter;

                public void setValue(Integer value) {
                    setter = "Integer";
                }

                public void setValue(String value) {
                    setter = "String";
                }
            }
            """;

    private static final String TEXT_BASE = """
            package com.example.impl;

            public class TextBase {
                public String setter;

                public void setValue(String value) {
                    setter = "String";
                }
            }
            """;

    private static final String INHERITED_TEXT = """
            package com.example.impl;

            public final class InheritedText extends TextBase implements com.example.api.Settable<Integer> {
                public void setValue(Integer value) {
                    setter = "Integer";
                }
            }
            """;

    /** The class LateOverriding is compiled against, before it implements the generic interface. */
    private static final String LATE_BASE_BEFORE = """
            package com.example.impl;

            public class LateBase {
                public String setter;

                public void setValue(Integer value) {
                    setter = "LateBase.setValue(Integer)";
                }
            }
            """;

    private static final String LATE_BASE = """
            package com.example.impl;

            public class LateBase implements com.example.api.Settable<Integer> {
                public String setter;

                public void setValue(Integer value) {
                    setter = "LateBase.setValue(Integer)";
                }
            }
            """;

    /** Compiled on its own against LATE_BASE_BEFORE, so it has no bridge of its own. */
    private static final String LATE_OVERRIDING = """
            package com.example.impl;

            public final class LateOverriding extends LateBase {
                @Override
                public void setValue(Integer value) {
                    setter = "LateOverriding.setValue(Integer)";
                }
            }
            """;

    private static final String NUMBERS = """
            package com.example.impl;

            public final class Numbers implements com.example.api.Settable<Integer> {
                public String setter;

                public void setValue(Integer value) {
                    setter = "Integer";
                }

                public void setValue(Number value) {
                    setter = "Number";
                }
            }
            """;

    private static final String TEXT_NOTE = """
            package com.example.impl;

            public class TextNote {
                public String setter;

                public void setNote(String note) {
                    setter = "TextNote.setNote(String)";
                }
            }
            """;

    private static final String ANY_NOTE = """
            package com.example.api;

            class AnyNote extends com.example.impl.TextNote {
                public void setNote(Object note) {
                    setter = "AnyNote.setNote(Object)";
                }
            }
            """;

    private static final String VISIBLE_NOTE = """
            package com.example.api;

            public final class VisibleNote extends AnyNote {
            }
            """;

    private static final String FLUENT_BASE = """
            package com.example.api;

            public class FluentBase {
                public String setter;

                public FluentBase setName(Object name) {
                    setter = "FluentBase.setName(Object)";
                    return this;
                }
            }
            """;

    private static final String FLUENT = """
            package com.example.impl;

            public final class Fluent extends com.example.api.FluentBase {
                @Override
                public Fluent setName(Object name) {
                    setter = "Fluent.setName(Object)";
                    return this;
                }

                public Fluent setName(String name) {
                    setter = "Fluent.setName(String)";
                    return this;
                }
            }
            """;

    private static final String HIDDEN_SETTABLE = """
            package com.example.hidden;

            public interface HiddenSettable<T> {
                void setValue(T value);
            }
            """;

    private static final String HIDDEN_SETTABLE_VALUE = """
            package com.example.impl;

            public final class HiddenSettableValue extends ValueBase
                    implements com.example.hidden.HiddenSettable<Integer> {
            }
            """;

    private static final String MISSING = """
            package com.example.api;

            public final class Missing {
            }
            """;

    private static final String MISSING_BASE = """
            package com.example.api;

            class MissingBase {
                public String setter;

                public void setItems(java.util.List<Missing> items) {
                    setter = "MissingBase.setItems(List)";
                }

                public void setName(String name) {
                    setter = "MissingBase.setName(String)";
                }
            }
            """;

    private static final String MISSING_SUB = """
            package com.example.api;

            public final class MissingSub extends MissingBase {
            }
            """;

    private static final String INTEGER_VALUE_BASE = """
            package com.example.impl;

            class IntegerValueBase {
                public String setter;

                public void setValue(Integer value) {
                    setter = "IntegerValueBase.setValue(Integer)";
                }
            }
            """;

    /** Inherits the generic setter's implementation from a class that isn't public, through a bridge. */
    private static final String INTEGER_VALUE = """
            package com.example.impl;

            public final class IntegerValue extends IntegerValueBase implements com.example.api.Settable<Integer> {
            }
            """;

    private static final String TEXT_VALUE_BASE = """
            package com.example.impl;

            class TextValueBase {
                public String setter;

                public void setValue(String value) {
                    setter = "TextValueBase.setValue(String)";
                }
            }
            """;

    private static final String TEXT_VALUE = """
            package com.example.impl;

            public final class TextValue extends TextValueBase implements com.example.api.Settable<String> {
            }
            """;

    private static final String TWO_VALUES_BASE = """
            package com.example.impl;

            class TwoValuesBase {
                public String setter;

                public void setValue(String value) {
                    setter = "TwoValuesBase.setValue(String)";
                }

                public void setValue(Integer value) {
                    setter = "TwoValuesBase.setValue(Integer)";
                }
            }
            """;

    private static final String TWO_VALUES = """
            package com.example.impl;

            public final class TwoValues extends TwoValuesBase implements com.example.api.Settable<String> {
            }
            """;

    /** Adds an override of a generic setter for text beside the setter for an Integer it inherits. */
    private static final String TEXT_OVER_INTEGER = """
            package com.example.impl;

            public final class TextOverInteger extends IntegerValueBase implements com.example.api.Settable<String> {
                public void setValue(String value) {
                    setter = "TextOverInteger.setValue(String)";
                }
            }
            """;

    private static final String TEXT_BASE_BELOW = """
            package com.example.impl;

            class TextBelowBase {
                public String setter;

                public void setValue(String value) {
                    setter = "TextBelowBase.setValue(String)";
                }
            }
            """;

    /** Overrides a generic setter for a character sequence beside the setter for text it inherits. */
    private static final String SEQUENCE_OVER_TEXT = """
            package com.example.impl;

            public final class SequenceOverText extends TextBelowBase
                    implements com.example.api.Settable<CharSequence> {
                public void setValue(CharSequence value) {
                    setter = "SequenceOverText.setValue(CharSequence)";
                }
            }
            """;

    private static final String HIDDEN_BOX = """
            package com.example.api;

            class HiddenBox<T> {
                public String setter;

                public void setContent(T content) {
                    setter = "HiddenBox.setContent(T)";
                }
            }
            """;

    /** Inherits a setter declared with a type variable, for a Long, through a bridge, beside one for a long. */
    private static final String VISIBLE_LONG_BOX = """
            package com.example.api;

            public final class VisibleLongBox extends HiddenBox<Long> {
                public void setContent(long content) {
                    setter = "VisibleLongBox.setContent(long)";
                }
            }
            """;

    /** Inherits a setter declared with a type variable, for a Long, through a bridge, beside one for an Integer. */
    private static final String VISIBLE_INTEGER_BOX = """
            package com.example.api;

            public final class VisibleIntegerBox extends HiddenBox<Long> {
                public void setContent(Integer content) {
                    setter = "VisibleIntegerBox.setContent(Integer)";
                }
            }
            """;

    private static final String PAIRED_BOX = """
            package com.example.api;

            public class PairedBox<T> {
                public String setter;

                public void setContent(T content) {
                    setter = "PairedBox.setContent(T)";
                }

                public void setContent(long content) {
                    setter = "PairedBox.setContent(long)";
                }
            }
            """;

    /** Gives the type variable of a setter beside another one a class that's missing once Missing.class is deleted. */
    private static final String MISSING_PAIRED_BOX = """
            package com.example.api;

            public final class MissingPairedBox extends PairedBox<Missing> {
            }
            """;

    private static final String TAGGED = """
            package com.example.api;

            public interface Tagged<T> {
            }
            """;

    /** Overrides a generic setter, and gives another interface a class that's missing once Missing.class is deleted. */
    private static final String MISSING_TAGGED_BOX = """
            package com.example.api;

            public final class MissingTaggedBox extends PairedBox<Integer> implements Tagged<Missing> {
                @Override
                public void setContent(Integer content) {
                    setter = "MissingTaggedBox.setContent(Integer)";
                }
            }
            """;

    /** A setter declared with a type variable bounded by a class that goes missing once Missing.class is deleted. */
    private static final String BOUNDED_BOX = """
            package com.example.api;

            public class BoundedBox<T extends Comparable<Missing>> {
                public String setter;

                public void setContent(T content) {
                    setter = "BoundedBox.setContent(T)";
                }

                public void setContent(long content) {
                    setter = "BoundedBox.setContent(long)";
                }
            }
            """;

    /** Gives the type variable of a setter beside another one a Long, in a signature that a test then breaks. */
    private static final String BAD_SIGNATURE_BOX = """
            package com.example.api;

            public final class BadSignatureBox extends PairedBox<Long> {
            }
            """;

    private static final String FACTORY = """
            package com.example.api;

            public final class Outputs {
                private Outputs() {
                }

                public static Object implementation() {
                    return new com.example.impl.Implementation();
                }

                public static Object hidden() {
                    return new Hidden();
                }

                public static Object generic() {
                    return new Generic();
                }

                public static Object valueOf(Object generic) {
                    return ((Generic) generic).getValue();
                }

                public static Object genericImplementation() {
                    return new com.example.impl.GenericImplementation();
                }

                public static Object counted() {
                    return new Counted();
                }

                public static Object label() {
                    return new com.example.impl.Label();
                }

                public static Object boxedScore() {
                    return new com.example.impl.BoxedScore();
                }

                public static Object inheritedValue() {
                    return new com.example.impl.InheritedValue();
                }

                public static Object overriding() {
                    return new com.example.impl.Overriding();
                }

                public static Object textToo() {
                    return new com.example.impl.TextToo();
                }

                public static Object inheritedText() {
                    return new com.example.impl.InheritedText();
                }

                public static Object lateOverriding() throws ReflectiveOperationException {
                    return Class.forName("com.example.impl.LateOverriding").getConstructor().newInstance();
                }

                public static Object numbers() {
                    return new com.example.impl.Numbers();
                }

                public static Object visibleNote() {
                    return new VisibleNote();
                }

                public static Object fluent() {
                    return new com.example.impl.Fluent();
                }

                public static Object hiddenSettableValue() {
                    return new com.example.impl.HiddenSettableValue();
                }

                public static Object missingSub() {
                    return new MissingSub();
                }

                public static Object integerValue() {
                    return new com.example.impl.IntegerValue();
                }

                public static Object textValue() {
                    return new com.example.impl.TextValue();
                }

                public static Object twoValues() {
                    return new com.example.impl.TwoValues();
                }

                public static Object textOverInteger() {
                    return new com.example.impl.TextOverInteger();
                }

                public static Object sequenceOverText() {
                    return new com.example.impl.SequenceOverText();
                }

                public static Object visibleLongBox() {
                    return new VisibleLongBox();
                }

                public static Object visibleIntegerBox() {
                    return new VisibleIntegerBox();
                }

                public static Object missingPairedBox() {
                    return new MissingPairedBox();
                }

                public static Object missingTaggedBox() {
                    return new MissingTaggedBox();
                }

                public static Object boundedBox() {
                    return new BoundedBox<>();
                }

                public static Object badSignatureBox() {
                    return new BadSignatureBox();
                }

                public static Object setterOf(Object output) throws ReflectiveOperationException {
                    java.lang.reflect.Field setter = output.getClass().getField("setter");
                    setter.setAccessible(true);
                    return setter.get(output);
                }
            }
            """;

    @Test
    @DisplayName("on the module path, a class in an unexported package is written through its exported interface")
    void exportedInterfaceOnTheModulePath(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("implementation").invoke(null);

        OutputWriter.beansAndMaps().set(output, "score", 9);

        assertEquals(9, FactProperties.read(output, "score"));
    }

    @Test
    @DisplayName("on the module path, a class that isn't public in an exported but not opened package can't be written")
    void exportedButNotOpened(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("hidden").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "score", 1));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertTrue(thrown.getMessage().contains("open it to io.github.brantunger.unruly.core for a type that isn't"
                + " public"), thrown.getMessage());
    }

    @Test
    @DisplayName("on the module path, a value that a setter takes only once widened fails as that setter can't be"
            + " reached, not as though no setter took it")
    void widenedValueForASetterThatCantBeReached(@TempDir Path classes) throws Exception {
        // setScore(int) takes a Short only as Java widens a short to an int (#524), so what fails is reaching it.
        Object output = outputs(classes, "exports com.example.api;").getMethod("hidden").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "score", (short) 1));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertTrue(thrown.getMessage().startsWith("A com.example.api.Hidden has a setter for 'score', but"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("on the module path, a generic setter is written through the exported interface's bridge method")
    void genericInterfaceOnTheModulePath(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("generic").invoke(null);

        OutputWriter.beansAndMaps().set(output, "value", 5);

        assertEquals(5, factory.getMethod("valueOf", Object.class).invoke(null, output));
    }

    @Test
    @DisplayName("on the module path, a value of another type isn't passed through the exported interface's bridge")
    void genericInterfaceRefusesAnotherType(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("generic").invoke(null);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", "text"));

        assertEquals("com.example.api.Generic has no public method setValue that accepts a java.lang.String",
                thrown.getMessage());
    }

    @Test
    @DisplayName("on the module path, a value of another type isn't passed through the bridge of a public class in an"
            + " unexported package")
    void unexportedGenericImplementationRefusesAnotherType(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("genericImplementation").invoke(null);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", "text"));

        assertEquals("com.example.impl.GenericImplementation has no public method setValue that accepts a"
                + " java.lang.String", thrown.getMessage());
    }

    @Test
    @DisplayName("on the module path, a public class's setter inherited from a class that isn't public is written"
            + " through its bridge")
    void inheritedFromAClassThatIsNotPublicOnTheModulePath(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("counted").invoke(null);

        OutputWriter.beansAndMaps().set(output, "count", 4);

        assertEquals(4, FactProperties.read(output, "count"));
    }

    @Test
    @DisplayName("on the module path, a class that isn't public in an opened package is written directly")
    void opened(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "opens com.example.api;").getMethod("hidden").invoke(null);

        OutputWriter.beansAndMaps().set(output, "score", 4);

        assertEquals(4, FactProperties.read(output, "score"));
    }

    @Test
    @DisplayName("on the module path, a setter that can't be reached isn't skipped for a less specific one that can")
    void unreachableSetterIsNotSkippedForALessSpecificOne(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("label").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "label", "text"));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertNull(setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a value only the exported interface's setter accepts is written through it")
    void lessSpecificSetterWhenItIsTheOnlyOneThatAccepts(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("label").invoke(null);

        OutputWriter.beansAndMaps().set(output, "label", 1);

        assertEquals("Object", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a boxed value whose setter can't be reached isn't unboxed for the exported"
            + " interface's primitive setter")
    void unreachableSetterIsNotSkippedForAPrimitiveOne(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("boxedScore").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "score", 3));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertNull(setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a setter inherited from a class in an unexported package is written through the"
            + " bridge its subclass gets for a generic interface")
    void inheritedImplementationThroughTheSubclassBridge(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("inheritedValue").invoke(null);

        OutputWriter.beansAndMaps().set(output, "value", 4);

        assertEquals("ValueBase.setValue(Integer)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a value of another type isn't passed through the bridge for an inherited"
            + " implementation")
    void inheritedImplementationRefusesAnotherType(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("inheritedValue").invoke(null);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", "text"));

        assertEquals("com.example.impl.InheritedValue has no public method setValue that accepts a"
                + " java.lang.String", thrown.getMessage());
    }

    @Test
    @DisplayName("on the module path, an override in a subclass of the class with the bridge is written through it")
    void overrideInASubclassOfTheBridgesClass(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("overriding").invoke(null);

        OutputWriter.beansAndMaps().set(output, "value", 4);

        assertEquals("Overriding.setValue(Integer)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a generic setter beside an overload in an unexported package is written")
    void genericSetterBesideAnOverload(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("textToo").invoke(null);

        OutputWriter.beansAndMaps().set(output, "value", 5);

        assertEquals("Integer", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a value for an overload beside a generic setter in an unexported package reaches"
            + " the bridge, which can't take it: a known limit")
    void overloadBesideAGenericSetterReachesTheBridge(@TempDir Path classes) throws Exception {
        // The bridge accepts what the overloads beside it do, as nothing says which of them it bridges to. So it
        // accepts the text meant for setValue(String), which the engine can't reach, and fails to cast it.
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("textToo").invoke(null);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", "text"));

        assertInstanceOf(ClassCastException.class, thrown.getCause());
        assertNull(setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a value for an overload that a generic setter's class inherits from a class in an"
            + " unexported package reaches the bridge, which can't take it: a known limit")
    void inheritedOverloadBesideAGenericSetterReachesTheBridge(@TempDir Path classes) throws Exception {
        // The bridge accepts what the public setters of its class do, inherited ones too, as nothing says which of
        // them it bridges to. So it accepts the text meant for the inherited setValue(String), which the engine can't
        // reach, and fails to cast it.
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("inheritedText").invoke(null);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", "text"));

        assertInstanceOf(ClassCastException.class, thrown.getCause());
        assertNull(setter(factory, output));
        OutputWriter.beansAndMaps().set(output, "value", 5);
        assertEquals("Integer", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, an override compiled before its superclass implemented a generic interface is"
            + " written through the superclass's bridge")
    void overrideCompiledWithoutItsOwnBridge(@TempDir Path earlier, @TempDir Path classes) throws Exception {
        compile(earlier, List.of(source("com/example/impl/LateBase", LATE_BASE_BEFORE),
                source("com/example/impl/LateOverriding", LATE_OVERRIDING)));
        Path overriding = Path.of("com", "example", "impl", "LateOverriding.class");
        Files.createDirectories(classes.resolve(overriding).getParent());
        Files.copy(earlier.resolve(overriding), classes.resolve(overriding));
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("lateOverriding").invoke(null);
        assertEquals("com.example.impl.LateBase",
                output.getClass().getMethod("setValue", Object.class).getDeclaringClass().getName());

        OutputWriter.beansAndMaps().set(output, "value", 4);

        assertEquals("LateOverriding.setValue(Integer)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a generic setter's override is written through the bridge when a setter between"
            + " them in the order can't be reached either")
    void bridgeAfterAnotherSetterThatCantBeReached(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("numbers").invoke(null);

        OutputWriter.beansAndMaps().set(output, "value", 5);

        assertEquals("Integer", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, null goes through the bridge to the generic setter's override")
    void nullThroughTheBridge(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("textToo").invoke(null);

        OutputWriter.beansAndMaps().set(output, "value", null);

        assertEquals("Integer", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a setter that can't be reached isn't skipped for a less specific one that a"
            + " bridge makes callable")
    void unreachableSetterIsNotSkippedForAVisibilityBridge(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("visibleNote").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "note", "text"));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertNull(setter(factory, output));
        OutputWriter.beansAndMaps().set(output, "note", 42);
        assertEquals("AnyNote.setNote(Object)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a setter that can't be reached isn't skipped for an override with a narrower"
            + " return type, or its bridge")
    void unreachableSetterIsNotSkippedForACovariantBridge(@TempDir Path classes) throws Exception {
        // The override and its bridge share a parameter, so which comes first depends on getMethods(). Neither is
        // tried for text, and for a number both call the override, so the outcome doesn't depend on that order.
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("fluent").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "name", "text"));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertNull(setter(factory, output));
        OutputWriter.beansAndMaps().set(output, "name", 1);
        assertEquals("Fluent.setName(Object)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a setter whose bridge can't be reached either fails naming the setter that was"
            + " refused first")
    void refusedBridgeKeepsTheFirstRefusal(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("hiddenSettableValue").invoke(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", 4));

        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertTrue(thrown.getMessage().contains("but com.example.impl.ValueBase can't be reached"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("a class whose setter names a class that's missing still has its other setters written")
    void setterBesideOneNamingAMissingClass(@TempDir Path classes) throws Exception {
        compileOutputs(classes, "exports com.example.api;");
        Files.delete(classes.resolve(Path.of("com", "example", "api", "Missing.class")));
        Class<?> factory = load(classes);
        Object output = factory.getMethod("missingSub").invoke(null);

        OutputWriter.beansAndMaps().set(output, "name", "hi");

        assertEquals("MissingBase.setName(String)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a Short goes to setContent(long), not through the bridge to setContent(T) with T"
            + " a Long in a class that isn't public")
    void visibilityBridgeForATypeVariableBesideAPrimitiveSetter(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("visibleLongBox").invoke(null);

        OutputWriter.beansAndMaps().set(output, "content", (short) 7);

        assertEquals("VisibleLongBox.setContent(long)", setter(factory, output));
        OutputWriter.beansAndMaps().set(output, "content", 7L);
        assertEquals("HiddenBox.setContent(T)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a Long goes through the bridge to setContent(T) with T a Long in a class that"
            + " isn't public, beside setContent(Integer)")
    void visibilityBridgeForATypeVariableBesideANarrowerReference(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("visibleIntegerBox").invoke(null);

        OutputWriter.beansAndMaps().set(output, "content", 7L);

        assertEquals("HiddenBox.setContent(T)", setter(factory, output));
        OutputWriter.beansAndMaps().set(output, "content", 7);
        assertEquals("VisibleIntegerBox.setContent(Integer)", setter(factory, output));
    }

    @Test
    @DisplayName("a setter declared with a type variable that a class gives a class that's missing takes its erased"
            + " parameter, as before")
    void variableGivenAMissingClass(@TempDir Path classes) throws Exception {
        compileOutputs(classes, "exports com.example.api;");
        Files.delete(classes.resolve(Path.of("com", "example", "api", "Missing.class")));
        Class<?> factory = load(classes);
        Object output = factory.getMethod("missingPairedBox").invoke(null);

        OutputWriter.beansAndMaps().set(output, "content", (short) 7);

        assertEquals("PairedBox.setContent(T)", setter(factory, output));
    }

    @Test
    @DisplayName("a generic setter's bridge in a class whose supertypes name a class that's missing takes only what the"
            + " override does, as before")
    void bridgeInAClassNamingAMissingClass(@TempDir Path classes) throws Exception {
        compileOutputs(classes, "exports com.example.api;");
        Files.delete(classes.resolve(Path.of("com", "example", "api", "Missing.class")));
        Class<?> factory = load(classes);
        Object output = factory.getMethod("missingTaggedBox").invoke(null);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(output, "content", "text"));

        assertEquals("com.example.api.MissingTaggedBox has no public method setContent that accepts a"
                + " java.lang.String", thrown.getMessage());
        OutputWriter.beansAndMaps().set(output, "content", 7);
        assertEquals("MissingTaggedBox.setContent(Integer)", setter(factory, output));
    }

    @Test
    @DisplayName("a setter declared with a type variable whose bound names a class that's missing takes its erased"
            + " parameter, as before")
    void variableBoundedByAMissingClass(@TempDir Path classes) throws Exception {
        compileOutputs(classes, "exports com.example.api;");
        Files.delete(classes.resolve(Path.of("com", "example", "api", "Missing.class")));
        Class<?> factory = load(classes);
        Object output = factory.getMethod("boundedBox").invoke(null);

        OutputWriter.beansAndMaps().set(output, "content", (short) 7);

        assertEquals("BoundedBox.setContent(T)", setter(factory, output));
    }

    @Test
    @DisplayName("a setter declared with a type variable, in a class whose generic signature is malformed, takes its"
            + " erased parameter, as before")
    void variableInAMalformedSignature(@TempDir Path classes) throws Exception {
        // The class's Signature attribute ends in '>' where ';' belongs, so reading its generic superclass throws
        // GenericSignatureFormatError. The replacement keeps the constant's length, so the class file stays valid.
        compileOutputs(classes, "exports com.example.api;");
        Path file = classes.resolve(Path.of("com", "example", "api", "BadSignatureBox.class"));
        String signature = "Lcom/example/api/PairedBox<Ljava/lang/Long;>;";
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertEquals(text.indexOf(signature), text.lastIndexOf(signature));
        Files.write(file, text.replace(signature, signature.substring(0, signature.length() - 1) + ">")
                .getBytes(StandardCharsets.ISO_8859_1));
        Class<?> factory = load(classes);
        Object output = factory.getMethod("badSignatureBox").invoke(null);
        assertThrows(GenericSignatureFormatError.class, () -> output.getClass().getGenericSuperclass());

        OutputWriter.beansAndMaps().set(output, "content", (short) 7);

        assertEquals("PairedBox.setContent(T)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a generic setter's implementation inherited from a class that isn't public is"
            + " written through the bridge")
    void inheritedFromAClassThatIsNotPublicThroughTheGenericBridge(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object integer = factory.getMethod("integerValue").invoke(null);
        OutputWriter.beansAndMaps().set(integer, "value", 5);
        assertEquals("IntegerValueBase.setValue(Integer)", setter(factory, integer));

        Object text = factory.getMethod("textValue").invoke(null);
        OutputWriter.beansAndMaps().set(text, "value", "s");
        assertEquals("TextValueBase.setValue(String)", setter(factory, text));

        Object none = factory.getMethod("textValue").invoke(null);
        OutputWriter.beansAndMaps().set(none, "value", null);
        assertEquals("TextValueBase.setValue(String)", setter(factory, none));
    }

    @Test
    @DisplayName("on the module path, a generic setter's implementation inherited from a class that isn't public,"
            + " beside an overload, is written through the bridge")
    void inheritedBesideAnOverloadThroughTheGenericBridge(@TempDir Path classes) throws Exception {
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object text = factory.getMethod("twoValues").invoke(null);
        OutputWriter.beansAndMaps().set(text, "value", "s");
        assertEquals("TwoValuesBase.setValue(String)", setter(factory, text));

        Object none = factory.getMethod("twoValues").invoke(null);
        OutputWriter.beansAndMaps().set(none, "value", null);
        assertEquals("TwoValuesBase.setValue(String)", setter(factory, none));

        // The known limit: the bridge also accepts the Integer meant for the inherited setValue(Integer), which the
        // engine can't reach, and fails to cast it.
        Object integer = factory.getMethod("twoValues").invoke(null);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> OutputWriter.beansAndMaps().set(integer, "value", 5));
        assertInstanceOf(ClassCastException.class, thrown.getCause());
        assertNull(setter(factory, integer));
    }

    @Test
    @DisplayName("on the module path, a value for an overload that a generic setter's class inherits from a class that"
            + " isn't public reaches the bridge, which can't take it: a known limit")
    void overloadInheritedFromAClassThatIsNotPublicReachesTheBridge(@TempDir Path classes) throws Exception {
        // The bridge that makes the inherited setValue(Integer) callable counts among the setters the generic
        // setter's bridge may call, so that bridge accepts the Integer, which the engine can't reach, and fails to
        // cast it.
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object output = factory.getMethod("textOverInteger").invoke(null);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", 5));

        assertInstanceOf(ClassCastException.class, thrown.getCause());
        assertNull(setter(factory, output));
        OutputWriter.beansAndMaps().set(output, "value", "s");
        assertEquals("TextOverInteger.setValue(String)", setter(factory, output));
    }

    @Test
    @DisplayName("on the module path, a value of another type isn't passed through the bridge for an implementation"
            + " inherited from a class that isn't public")
    void inheritedFromAClassThatIsNotPublicRefusesAnotherType(@TempDir Path classes) throws Exception {
        Object output = outputs(classes, "exports com.example.api;").getMethod("integerValue").invoke(null);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OutputWriter.beansAndMaps().set(output, "value", "text"));

        assertEquals("com.example.impl.IntegerValue has no public method setValue that accepts a java.lang.String",
                thrown.getMessage());
    }

    @Test
    @DisplayName("on the module path, text for an overload that a generic setter's class inherits from a class that"
            + " isn't public goes through the bridge to the less specific generic setter: a known limit")
    void narrowerInheritedOverloadReachesTheLessSpecificGenericSetter(@TempDir Path classes) throws Exception {
        // Java would call the inherited setValue(String), which the engine can't reach. The bridge accepts text, as
        // that setter does, and calls setValue(CharSequence), which takes it too.
        Class<?> factory = outputs(classes, "exports com.example.api;");
        Object text = factory.getMethod("sequenceOverText").invoke(null);
        OutputWriter.beansAndMaps().set(text, "value", "s");
        assertEquals("SequenceOverText.setValue(CharSequence)", setter(factory, text));

        Object none = factory.getMethod("sequenceOverText").invoke(null);
        OutputWriter.beansAndMaps().set(none, "value", null);
        assertEquals("SequenceOverText.setValue(CharSequence)", setter(factory, none));
    }

    /** The setter an output class of the module ran, which only the module can read, as its package isn't exported. */
    private static Object setter(Class<?> factory, Object output) throws ReflectiveOperationException {
        return factory.getMethod("setterOf", Object.class).invoke(null, output);
    }

    /**
     * Compiles a module named {@code outputs}, with the directive given for {@code com.example.api} and nothing for
     * {@code com.example.impl}, loads it in a new layer, and returns its public factory class.
     */
    private static Class<?> outputs(Path classes, String directive) throws ClassNotFoundException {
        compileOutputs(classes, directive);
        return load(classes);
    }

    /** Compiles the module {@link #outputs} loads, without loading it. */
    private static void compileOutputs(Path classes, String directive) {
        String moduleInfo = "module outputs { " + directive + " }";
        compile(classes, List.of(source("module-info", moduleInfo), source("com/example/api/Scored", API),
                        source("com/example/impl/Implementation", IMPLEMENTATION),
                        source("com/example/api/Hidden", HIDDEN), source("com/example/api/Settable", SETTABLE),
                        source("com/example/api/Generic", GENERIC),
                        source("com/example/impl/GenericImplementation", GENERIC_IMPLEMENTATION),
                        source("com/example/api/Counting", COUNTING), source("com/example/api/Counted", COUNTED),
                        source("com/example/api/Labelled", LABELLED), source("com/example/impl/Label", LABEL),
                        source("com/example/impl/BoxedScore", BOXED_SCORE),
                        source("com/example/impl/ValueBase", VALUE_BASE),
                        source("com/example/impl/InheritedValue", INHERITED_VALUE),
                        source("com/example/impl/GenericBase", GENERIC_BASE),
                        source("com/example/impl/Overriding", OVERRIDING),
                        source("com/example/impl/TextToo", TEXT_TOO),
                        source("com/example/impl/TextBase", TEXT_BASE),
                        source("com/example/impl/InheritedText", INHERITED_TEXT),
                        source("com/example/impl/LateBase", LATE_BASE),
                        source("com/example/impl/Numbers", NUMBERS), source("com/example/impl/TextNote", TEXT_NOTE),
                        source("com/example/api/AnyNote", ANY_NOTE),
                        source("com/example/api/VisibleNote", VISIBLE_NOTE),
                        source("com/example/api/FluentBase", FLUENT_BASE), source("com/example/impl/Fluent", FLUENT),
                        source("com/example/hidden/HiddenSettable", HIDDEN_SETTABLE),
                        source("com/example/impl/HiddenSettableValue", HIDDEN_SETTABLE_VALUE),
                        source("com/example/api/Missing", MISSING), source("com/example/api/MissingBase", MISSING_BASE),
                        source("com/example/api/MissingSub", MISSING_SUB),
                        source("com/example/impl/IntegerValueBase", INTEGER_VALUE_BASE),
                        source("com/example/impl/IntegerValue", INTEGER_VALUE),
                        source("com/example/impl/TextValueBase", TEXT_VALUE_BASE),
                        source("com/example/impl/TextValue", TEXT_VALUE),
                        source("com/example/impl/TwoValuesBase", TWO_VALUES_BASE),
                        source("com/example/impl/TwoValues", TWO_VALUES),
                        source("com/example/impl/TextOverInteger", TEXT_OVER_INTEGER),
                        source("com/example/impl/TextBelowBase", TEXT_BASE_BELOW),
                        source("com/example/impl/SequenceOverText", SEQUENCE_OVER_TEXT),
                        source("com/example/api/HiddenBox", HIDDEN_BOX),
                        source("com/example/api/VisibleLongBox", VISIBLE_LONG_BOX),
                        source("com/example/api/VisibleIntegerBox", VISIBLE_INTEGER_BOX),
                        source("com/example/api/PairedBox", PAIRED_BOX),
                        source("com/example/api/MissingPairedBox", MISSING_PAIRED_BOX),
                        source("com/example/api/Tagged", TAGGED),
                        source("com/example/api/MissingTaggedBox", MISSING_TAGGED_BOX),
                        source("com/example/api/BadSignatureBox", BAD_SIGNATURE_BOX),
                        source("com/example/api/BoundedBox", BOUNDED_BOX),
                        source("com/example/api/Outputs", FACTORY)));
    }

    /** Loads a compiled module named {@code outputs} in a new layer, and returns its public factory class. */
    private static Class<?> load(Path classes) throws ClassNotFoundException {
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration()
                .resolve(ModuleFinder.of(classes), ModuleFinder.of(), Set.of("outputs"));
        ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        return layer.findLoader("outputs").loadClass("com.example.api.Outputs");
    }

    private static void compile(Path classes, List<JavaFileObject> sources) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean compiled = javac.getTask(null, null, diagnostics, List.of("-proc:none", "-d", classes.toString()),
                null, sources).call();
        String errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n"));
        assertTrue(compiled, errors);
    }

    private static JavaFileObject source(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
