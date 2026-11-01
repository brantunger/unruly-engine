package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1263: the rule list's class loader holds a name to the limits on an import only when MVEL's reading of an inline
 * class import asks for it: when the first frame that is neither the loader's nor {@link Class}'s is
 * {@code ImportNode}'s constructor. Read here from made-up stacks, as a real one reaches only some of the answers.
 */
@DisplayName("the class loader tells MVEL's reading of a class import by the first frame not its own nor Class's")
class ClassImportWalkTest {

    private static final String LOADER = ExactNameClassLoader.class.getName();
    private static final String CLASS = Class.class.getName();
    private static final String IMPORT_NODE = "org.mvel2.ast.ImportNode";

    /** A frame with only a class and a method, all a walk reads. */
    private record Frame(String className, String methodName) implements StackWalker.StackFrame {

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public Class<?> getDeclaringClass() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getByteCodeIndex() {
            return 0;
        }

        @Override
        public String getFileName() {
            return "Frame.java";
        }

        @Override
        public int getLineNumber() {
            return 0;
        }

        @Override
        public boolean isNativeMethod() {
            return false;
        }

        @Override
        public StackTraceElement toStackTraceElement() {
            return new StackTraceElement(className, methodName, getFileName(), 0);
        }
    }

    private static boolean walk(Frame... frames) {
        return new ExactNameClassLoader.ClassImport().apply(Stream.of(frames));
    }

    /** The stack of a lookup, from the walk down: the loader's frames, then Class.forName's, then {@code caller}. */
    private static Frame[] lookupFrom(Frame caller, int classFrames) {
        List<Frame> frames = new ArrayList<>(List.of(new Frame(LOADER, "inClassImport"),
                new Frame(LOADER, "checkImportSize"), new Frame(LOADER, "loadClass")));
        frames.addAll(Collections.nCopies(classFrames, new Frame(CLASS, "forName")));
        frames.add(caller);
        frames.add(new Frame("org.mvel2.compiler.AbstractParser", "nextToken"));
        return frames.toArray(new Frame[0]);
    }

    @Test
    @DisplayName("ImportNode's constructor, past the loader's and Class.forName's frames, is a class import")
    void importNodeConstructorAfterClassFrames() {
        assertAll(
                () -> assertTrue(walk(lookupFrom(new Frame(IMPORT_NODE, "<init>"), 3)), "JDK 21's three"),
                () -> assertTrue(walk(lookupFrom(new Frame(IMPORT_NODE, "<init>"), 2)), "JDK 25's two"));
    }

    @Test
    @DisplayName("another method of ImportNode's, or another class first, isn't a class import, nor is ImportNode"
            + " further down the stack")
    void otherFirstFrame() {
        Frame[] nodeDeeper = lookupFrom(new Frame("org.mvel2.util.ParseTools", "findInnerClass"), 4);
        Frame[] withNode = new Frame[nodeDeeper.length + 1];
        System.arraycopy(nodeDeeper, 0, withNode, 0, nodeDeeper.length);
        withNode[nodeDeeper.length] = new Frame(IMPORT_NODE, "<init>");

        assertAll(
                () -> assertFalse(walk(lookupFrom(new Frame(IMPORT_NODE, "getReducedValue"), 4)), "another method"),
                () -> assertFalse(walk(lookupFrom(new Frame("org.mvel2.ast.StaticImportNode", "<init>"), 4)),
                        "another class"),
                () -> assertFalse(walk(withNode), "ImportNode below another caller"));
    }

    @Test
    @DisplayName("a stack with no frame but the loader's and Class's, or none at all, isn't a class import")
    void noOtherFrame() {
        assertAll(
                () -> assertFalse(walk(), "no frames"),
                () -> assertFalse(walk(new Frame(LOADER, "inClassImport"), new Frame(CLASS, "forName")),
                        "only the loader's and Class's"));
    }

    @Test
    @DisplayName("ImportNode's constructor is found as the last frame it reads, and not one frame past it")
    void stopsAtMostFrames() {
        // The 3 loader frames, then Class's, then ImportNode's: at frame MOST_FRAMES, then at MOST_FRAMES + 1.
        int most = ExactNameClassLoader.ClassImport.MOST_FRAMES;

        assertAll(
                () -> assertTrue(walk(lookupFrom(new Frame(IMPORT_NODE, "<init>"), most - 4)), "the last frame read"),
                () -> assertFalse(walk(lookupFrom(new Frame(IMPORT_NODE, "<init>"), most - 3)), "one frame past"));
    }
}
