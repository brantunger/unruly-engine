package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.Throwables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.ast.LiteralNode;
import org.mvel2.ast.Union;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link GuardedVariables} tells where a name MVEL asks about stands, from frames given by hand, the JIT's and the
 * interpreter's among them, and when it walks the stack. {@link PropertyReadGuardTest} checks each place through the
 * engine.
 */
@DisplayName("the variables a run reads tell where a name stands from MVEL's frames")
class GuardedVariablesTest {

    private static final String OWN = GuardedVariables.class.getName();
    private static final String REFLECTIVE = "org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer";
    private static final String ASM = "org.mvel2.optimizers.impl.asm.ASMAccessorOptimizer";
    private static final String INTERPRETED = "org.mvel2.PropertyAccessor";
    private static final String NODE = "org.mvel2.ast.ASTNode";
    private static final String FOLD = "org.mvel2.ast.Fold";
    private static final String WITH_PAIR = "org.mvel2.ast.WithNode$ParmValuePair";
    private static final String WITH_ACCESSOR = "org.mvel2.optimizers.impl.refl.nodes.WithAccessor";
    private static final String STATEMENT = "org.mvel2.compiler.ExecutableAccessor";
    private static final String JIT_ACCESSOR = "org.mvel2.optimizers.dynamic.DynamicGetAccessor";
    private static final String FUNCTION_FACTORY = "org.mvel2.ast.InvokationContextFactory";
    private static final String RUN = MvelExpression.class.getName();

    // A frame given by hand: its class, its method and its line.
    private record Frame(String className, String methodName, int line) implements StackWalker.StackFrame {

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public int getLineNumber() {
            return line;
        }

        @Override
        public Class<?> getDeclaringClass() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getByteCodeIndex() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getFileName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isNativeMethod() {
            return false;
        }

        @Override
        public StackTraceElement toStackTraceElement() {
            throw new UnsupportedOperationException();
        }
    }

    private static Frame frame(String className, String methodName) {
        return new Frame(className, methodName, 1);
    }

    private static int position(StackWalker.StackFrame... frames) {
        return GuardedVariables.position(Arrays.asList(frames).iterator());
    }

    // The frames of a question about the first name of a chain, down to what asked the accessor for the chain.
    private static List<StackWalker.StackFrame> chainHead(String accessor, String method) {
        return List.of(frame(OWN, "isResolveable"), frame(OWN + "$Position", "apply"), frame(accessor, method),
                frame(accessor, "compileGetChain"), frame(accessor, "optimizeAccessor"));
    }

    private static int positionOf(List<StackWalker.StackFrame> top, StackWalker.StackFrame... rest) {
        List<StackWalker.StackFrame> frames = new ArrayList<>(top);
        frames.addAll(List.of(rest));
        return GuardedVariables.position(frames.iterator());
    }

    static Stream<Arguments> chainHeads() {
        return Stream.of(REFLECTIVE, ASM, INTERPRETED).flatMap(accessor -> Stream.of("getBeanProperty", "getMethod")
                .map(method -> Arguments.of(accessor, method)));
    }

    @Test
    @DisplayName("a question that isn't about the first name of a chain is at the root")
    void notAChainHead() {
        assertEquals(GuardedVariables.ROOT, position());
        assertEquals(GuardedVariables.ROOT, position(frame(OWN, "isResolveable")));
        assertEquals(GuardedVariables.ROOT, position(frame(OWN, "isResolveable"),
                frame("org.mvel2.ast.IsDef", "getReducedValueAccelerated"), frame(WITH_PAIR, "eval")));
        // One of the accessor's other methods, and a method of that name in another class.
        assertEquals(GuardedVariables.ROOT, position(frame(OWN, "isResolveable"),
                frame(REFLECTIVE, "compileGetChain"), frame(WITH_PAIR, "eval")));
        assertEquals(GuardedVariables.ROOT, position(frame(OWN, "isResolveable"),
                frame("org.mvel2.ast.Other", "getBeanProperty"), frame(WITH_PAIR, "eval")));
        // A factory that asks as it gets the variable for what is built already.
        assertEquals(GuardedVariables.ROOT, position(frame(OWN, "isResolveable"),
                frame(FUNCTION_FACTORY, "isResolveable"), frame(FUNCTION_FACTORY, "getVariableResolver"),
                frame("org.mvel2.optimizers.impl.refl.nodes.VariableAccessor", "getValue"), frame(WITH_PAIR, "eval")));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("chainHeads")
    @DisplayName("the first name of a chain that nothing scoped asked for is at the root")
    void chainHeadAtTheRoot(String accessor, String method) {
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(accessor, method)));
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(accessor, method), frame(NODE, "optimize"),
                frame("org.mvel2.MVELRuntime", "execute"), frame(RUN, "run")));
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(accessor, method), frame(JIT_ACCESSOR, "optimize")));
        // A stack that ends at the chain's node, or at its statement.
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(accessor, method), frame(NODE, "optimize")));
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(accessor, method), frame(NODE, "optimize"),
                frame(STATEMENT, "getValue")));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("chainHeads")
    @DisplayName("the factories MVEL wraps the variables in are passed over")
    void factoriesArePassedOver(String accessor, String method) {
        assertEquals(GuardedVariables.RESTART, position(frame(OWN, "isResolveable"),
                frame("org.mvel2.integration.impl.BaseVariableResolverFactory", "isNextResolveable"),
                frame("org.mvel2.integration.impl.ClassImportResolverFactory", "getVariableResolver"),
                frame(FUNCTION_FACTORY, "isResolveable"), frame(accessor, method),
                frame("org.mvel2.ast.Union", "getReducedValueAccelerated")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"org.mvel2.ast.Union", "org.mvel2.optimizers.impl.refl.nodes.Union",
        "org.mvel2.optimizers.impl.refl.nodes.NullSafe", "org.mvel2.ast.NewObjectNode",
        "org.mvel2.ast.LiteralDeepPropertyNode"})
    @DisplayName("the first name of a chain asked for against a value that isn't a name is a restart")
    void restart(String asker) {
        for (String accessor : List.of(REFLECTIVE, ASM, INTERPRETED)) {
            assertEquals(GuardedVariables.RESTART, positionOf(chainHead(accessor, "getBeanProperty"),
                    frame(asker, "getValue"), frame(RUN, "run")));
            // With the JIT's frames between.
            assertEquals(GuardedVariables.RESTART, positionOf(chainHead(accessor, "getMethod"),
                    frame(JIT_ACCESSOR, "optimize"), frame(JIT_ACCESSOR, "getValue"), frame(asker, "getValue")));
        }
    }

    @Test
    @DisplayName("the first name of a chain after a new object is a restart, and a constructor's argument isn't")
    void constructor() {
        assertEquals(GuardedVariables.RESTART, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(REFLECTIVE, "compileConstructor"), frame(REFLECTIVE, "optimizeObjectCreation")));
        assertEquals(GuardedVariables.RESTART, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(REFLECTIVE, "compileConstructor"), frame(REFLECTIVE, "optimizeObjectCreation"),
                frame(NODE, "getReducedValueAccelerated"), frame(RUN, "run")));
        // The argument's own chain: another node's frames come before the constructor's.
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(NODE, "optimize"), frame("org.mvel2.compiler.ExecutableAccessor", "getValue"),
                frame(REFLECTIVE, "compileConstructor"), frame("org.mvel2.ast.NewObjectNode", "getReducedValue"),
                frame(RUN, "run")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {WITH_PAIR, WITH_ACCESSOR})
    @DisplayName("the first name of a chain under a with block's frame is in a scoped body")
    void withBlock(String scope) {
        for (String accessor : List.of(REFLECTIVE, ASM, INTERPRETED)) {
            assertEquals(GuardedVariables.SCOPED_BODY, positionOf(chainHead(accessor, "getBeanProperty"),
                    frame(NODE, "optimize"), frame(scope, "eval"), frame(RUN, "run")));
        }
        // The target of the block, which the node itself reads, at the root.
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(NODE, "optimize"), frame("org.mvel2.ast.WithNode", "getReducedValueAccelerated"),
                frame(RUN, "run")));
    }

    // The frames of a block's statement that is one chain, down to the block's frame at a line.
    private static List<StackWalker.StackFrame> ownStatement(String accessor, String block, int line) {
        List<StackWalker.StackFrame> frames = new ArrayList<>(chainHead(accessor, "getBeanProperty"));
        frames.addAll(List.of(frame(NODE, "optimize"), frame(NODE, "getReducedValueAccelerated"),
                frame(STATEMENT, "getValue"), new Frame(block, "eval", line), frame(block, "getValue")));
        return frames;
    }

    static Stream<Arguments> statementLines() {
        return Stream.of(Arguments.of(WITH_PAIR, 278), Arguments.of(WITH_ACCESSOR, 57));
    }

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("statementLines")
    @DisplayName("the first name of a block's statement without an assignment, by MVEL 2.5.4's lines, is the block's "
            + "own target, at the root")
    void ownTarget(String block, int line) {
        for (String accessor : List.of(REFLECTIVE, ASM, INTERPRETED)) {
            assertEquals(GuardedVariables.ROOT, positionOf(ownStatement(accessor, block, line)));
            assertEquals(GuardedVariables.ROOT, positionOf(ownStatement(accessor, block, line),
                    frame("org.mvel2.ast.WithNode", "getReducedValueAccelerated"), frame(RUN, "run"),
                    frame(WITH_PAIR, "eval")));
        }
        // With the JIT's frames between the accessor and the node.
        List<StackWalker.StackFrame> jit = new ArrayList<>(chainHead(ASM, "getMethod"));
        jit.addAll(List.of(frame(JIT_ACCESSOR, "optimize"), frame(NODE, "getReducedValueAccelerated"),
                frame(STATEMENT, "getValue"), new Frame(block, "eval", line)));
        assertEquals(GuardedVariables.ROOT, positionOf(jit, frame(RUN, "run")));
    }

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("statementLines")
    @DisplayName("the block's own target is in a scoped body when the block is under a restart, a new object or "
            + "another scoped body")
    void ownTargetUnderAnotherPlace(String block, int line) {
        for (StackWalker.StackFrame above : List.of(frame("org.mvel2.ast.Union", "getReducedValueAccelerated"),
                frame("org.mvel2.optimizers.impl.refl.nodes.NullSafe", "getValue"),
                frame(REFLECTIVE, "compileConstructor"), frame(ASM, "compileConstructor"), frame(WITH_PAIR, "eval"),
                frame(WITH_ACCESSOR, "processWith"), new Frame(FOLD, "getReducedValueAccelerated", 103))) {
            assertEquals(GuardedVariables.SCOPED_BODY, positionOf(ownStatement(REFLECTIVE, block, line),
                    frame(REFLECTIVE, "compileGetChain"), frame(NODE, "optimize"), above, frame(RUN, "run")),
                    () -> "under " + above);
        }
        // An accessor's other methods and a projection's collection above it change nothing.
        assertEquals(GuardedVariables.ROOT, positionOf(ownStatement(REFLECTIVE, block, line),
                frame(REFLECTIVE, "compileGetChain"), new Frame(FOLD, "getReducedValueAccelerated", 100),
                frame(RUN, "run")));
    }

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("statementLines")
    @DisplayName("any other name of a block's statement, and a statement at a line this doesn't know, is in the "
            + "block's body")
    void notTheOwnTarget(String block, int line) {
        // At another line: an assignment's value, or a line of another MVEL.
        for (int other : List.of(line - 1, line + 1, 281, 54, -1)) {
            assertEquals(GuardedVariables.SCOPED_BODY, positionOf(ownStatement(REFLECTIVE, block, other),
                    frame(RUN, "run")), () -> "line " + other);
        }
        // The other block's line.
        assertEquals(GuardedVariables.SCOPED_BODY, positionOf(ownStatement(REFLECTIVE, block, line == 57 ? 278 : 57),
                frame(RUN, "run")));
        // A name of an argument: another accessor's frames come before the block's.
        List<StackWalker.StackFrame> argument = new ArrayList<>(chainHead(REFLECTIVE, "getBeanProperty"));
        argument.addAll(List.of(frame(NODE, "optimize"), frame(STATEMENT, "getValue"), frame(REFLECTIVE, "getMethod"),
                frame(REFLECTIVE, "compileGetChain"), frame(NODE, "optimize"), frame(STATEMENT, "getValue"),
                new Frame(block, "eval", line)));
        assertEquals(GuardedVariables.SCOPED_BODY, positionOf(argument, frame(RUN, "run")));
        // A name under an operator's node, one with no statement's frame, and one with no node's.
        List<StackWalker.StackFrame> operand = new ArrayList<>(chainHead(REFLECTIVE, "getBeanProperty"));
        operand.addAll(List.of(frame(NODE, "optimize"), frame("org.mvel2.ast.BinaryOperation",
                "getReducedValueAccelerated"), frame(STATEMENT, "getValue"), new Frame(block, "eval", line)));
        assertEquals(GuardedVariables.SCOPED_BODY, positionOf(operand, frame(RUN, "run")));
        List<StackWalker.StackFrame> bare = new ArrayList<>(chainHead(REFLECTIVE, "getBeanProperty"));
        bare.addAll(List.of(frame(NODE, "optimize"), new Frame(block, "eval", line)));
        assertEquals(GuardedVariables.SCOPED_BODY, positionOf(bare, frame(RUN, "run")));
        List<StackWalker.StackFrame> noNode = new ArrayList<>(chainHead(REFLECTIVE, "getBeanProperty"));
        noNode.addAll(List.of(frame(STATEMENT, "getValue"), new Frame(block, "eval", line)));
        assertEquals(GuardedVariables.SCOPED_BODY, positionOf(noNode, frame(RUN, "run")));
    }

    @ParameterizedTest(name = "line {0}")
    @ValueSource(ints = {88, 93, 100, 116, 125, 131})
    @DisplayName("a projection's collection and filter, by Fold.java's lines in MVEL 2.5.4, are at the root")
    void projectionAtTheRoot(int line) {
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(NODE, "optimize"), new Frame(FOLD, "getReducedValueAccelerated", line), frame(RUN, "run")));
        // Unless the projection is itself in a scoped body.
        assertEquals(GuardedVariables.SCOPED_BODY, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(NODE, "optimize"), new Frame(FOLD, "getReducedValueAccelerated", line),
                frame(WITH_PAIR, "eval"), frame(RUN, "run")));
    }

    @ParameterizedTest(name = "line {0}")
    @ValueSource(ints = {94, 103, 126, 139, 1, -1, 999})
    @DisplayName("a projection's element, and any line of Fold.java this doesn't know, is a scoped body")
    void projectionElement(int line) {
        assertEquals(GuardedVariables.SCOPED_BODY, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(NODE, "optimize"), new Frame(FOLD, "getReducedValueAccelerated", line), frame(RUN, "run")));
    }

    @Test
    @DisplayName("the walk stops at this run's own frame, so the block that started the run doesn't count")
    void stopsAtTheRun() {
        assertEquals(GuardedVariables.ROOT, positionOf(chainHead(REFLECTIVE, "getBeanProperty"),
                frame(NODE, "optimize"), frame(RUN, "run"), frame(RUN, "execute"),
                frame("org.mvel2.optimizers.impl.refl.nodes.MethodAccessor", "getValue"), frame(WITH_PAIR, "eval"),
                new Frame(FOLD, "getReducedValueAccelerated", 103)));
    }

    @Test
    @DisplayName("MVEL 2.5.4 has every class and method the frames are told by")
    void pinnedNamesExist() throws ClassNotFoundException, IOException, URISyntaxException {
        for (String accessor : List.of(REFLECTIVE, ASM, INTERPRETED)) {
            Set<String> methods = methodsOf(accessor);
            assertTrue(methods.contains("getBeanProperty"), accessor + ".getBeanProperty");
            assertTrue(methods.contains("getMethod"), accessor + ".getMethod");
        }
        assertTrue(methodsOf(REFLECTIVE).contains("compileConstructor"), "compileConstructor");
        assertTrue(methodsOf(FUNCTION_FACTORY).contains("isResolveable"), "isResolveable");
        for (String name : List.of("org.mvel2.ast.Union", "org.mvel2.optimizers.impl.refl.nodes.Union",
                "org.mvel2.optimizers.impl.refl.nodes.NullSafe", "org.mvel2.ast.NewObjectNode",
                "org.mvel2.ast.LiteralDeepPropertyNode", WITH_PAIR, WITH_ACCESSOR, FOLD, JIT_ACCESSOR, NODE, STATEMENT,
                "org.mvel2.integration.impl.ClassImportResolverFactory")) {
            assertDoesNotThrow(() -> Class.forName(name, false, MVEL.class.getClassLoader()), name);
        }
        // The version in the manifest of the jar MVEL was loaded from: MVEL.VERSION says 2.3.
        try (JarFile jar = new JarFile(Path.of(MVEL.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toFile())) {
            assertEquals("2.5.4.Final", jar.getManifest().getMainAttributes().getValue("Bundle-Version"), "MVEL's "
                    + "version changed: check every test of PropertyReadGuardTest and of this class against its "
                    + "frames, and Fold.java's lines, then change this");
        }
    }

    private static Set<String> methodsOf(String className) throws ClassNotFoundException {
        Set<String> names = new HashSet<>();
        for (Method method : Class.forName(className, false, MVEL.class.getClassLoader()).getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    @Test
    @DisplayName("only a supplied fact's name walks the stack: not an unknown name, the output or an assigned variable")
    void walksOnlyForFacts() {
        Map<String, Object> variables = new HashMap<>(Map.of("order", 1, "output", 2));
        GuardedVariables guarded = new GuardedVariables(variables, Map.of("order", 1), Set.of());
        guarded.createVariable("local", 3);
        long before = GuardedVariables.walks();

        assertFalse(guarded.isResolveable("unknown"));
        assertTrue(guarded.isResolveable("output"));
        assertTrue(guarded.isResolveable("local"));
        assertEquals(before, GuardedVariables.walks());
        assertTrue(guarded.isResolveable("order"));
        assertEquals(before + 1, GuardedVariables.walks());

        guarded.finished();
        assertEquals(3, variables.get("local"), "written to the map once the run ends");
    }

    /** Asks variables of its own about a name, from MVEL's frames for a name after a literal. */
    public static class Probe {

        private GuardedVariables guarded;
        private RuntimeException thrown;

        public Object ask(Object value) {
            guarded = new GuardedVariables(new HashMap<>(Map.of("size", 9)), Map.of("size", 9), Set.of("size"));
            ParserContext context = new ParserContext();
            Union afterLiteral = new Union("size".toCharArray(), 0, 4, 0, new LiteralNode(value, context), context);
            thrown = assertThrows(RuntimeException.class, () -> afterLiteral.getReducedValue(null, null, guarded));
            return value;
        }
    }

    @Test
    @DisplayName("a refused fact fails the run when it ends, whatever MVEL wrapped the refusal in, declared or not")
    void refusalIsKept() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(TreeMap::new)
                .build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("true")
                .action("output.put('r', probe.ask(['a': 1]))").build()));
        FactMap<Object> facts = new FactMap<>();
        Probe probe = new Probe();
        facts.setValue("probe", probe);

        assertEquals(Map.of("r", Map.of("a", 1)), engine.run(facts));

        // MVEL's interpreter wraps it in an exception of its own.
        assertEquals("org.mvel2.PropertyAccessException", probe.thrown.getClass().getName());
        GuardedVariables.FactReadAsProperty refusal = assertThrows(GuardedVariables.FactReadAsProperty.class,
                probe.guarded::finished);
        assertTrue(Throwables.causeChain(probe.thrown).contains(refusal), "the refusal MVEL wrapped");
        assertEquals("Fact 'size' can't be read in place of a property: MVEL reads a name after a value in "
                + "parentheses, a literal, a new object or a null-safe name as the fact of that name, not as the "
                + "value's property", refusal.getMessage());
    }

    @Test
    @DisplayName("a rule that only reads what MVEL built in its first run walks the stack in that run alone")
    void warmRunsDoNotWalk() {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(TreeMap::new)
                .fact("orders", List.class).build();
        engine.load(List.of(Rule.builder().ruleName("r")
                .condition("limit > 1 && orders.size() == 2 && (orders).size() == 2 && ($ in orders if $ > limit)"
                        + ".size() == 1")
                .action("x = limit + 1; with (output) { put('r', x), put('s', orders.size()) }; "
                        + "foreach (o : orders) { output.put('o', o + limit) }").build()));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("limit", 2);
        facts.setValue("orders", List.of(1, 3));
        long before = GuardedVariables.walks();

        assertEquals(Map.of("r", 3, "s", 2, "o", 5), engine.run(facts));
        long first = GuardedVariables.walks();
        assertTrue(first > before, "the first run walks");
        for (int run = 0; run < 200; run++) {
            engine.run(facts);
        }

        assertEquals(first, GuardedVariables.walks(), "200 more runs walk nothing");
    }
}
