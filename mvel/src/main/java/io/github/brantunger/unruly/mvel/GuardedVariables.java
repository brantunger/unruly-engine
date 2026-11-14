package io.github.brantunger.unruly.mvel;

import org.jspecify.annotations.Nullable;
import org.mvel2.integration.impl.CachingMapVariableResolverFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The variables one run of an expression reads, as MVEL asks for them, which keep one rule: a fact can't take the
 * place of a property MVEL reads from a value.
 *
 * <p>
 * MVEL reads the first name of a chain as a variable before it reads it as a property, whatever the chain is read
 * from. At the root of an expression nothing else could be meant: {@code order} in {@code order.tier} is the fact.
 * Where MVEL starts a new chain against a value, the name the rule's author wrote is a property of that value, and a
 * fact of the same name would be read in its place. There are two kinds of such places:
 * </p>
 * <ul>
 *     <li>a <b>restart</b>: the name after a value that isn't a name, as {@code tier} in {@code (order).tier}, a cast
 *     or a ternary in parentheses, {@code (e in c).size}, {@code [1, 2].size}, {@code {1, 2}.length},
 *     {@code 'abc'.bytes} and {@code new Order().tier}, and the name after a null-safe first name, as {@code tier} in
 *     {@code ?p.tier} and {@code (order).?peer.tier}. No fact is read there, declared or not: a run in which MVEL
 *     would read one fails;</li>
 *     <li>a <b>scoped body</b>: the element of a projection, as {@code level} in {@code (level in roles)}, the body of
 *     {@code with (t) { ... }} and of {@code t.{ ... }}, and what any of them calls. There a name may be meant either
 *     way, and all this is told is the name, so a fact the engine declared is read, as the rule's author could see it
 *     would be, and the run fails for a supplied fact nobody declared.</li>
 * </ul>
 *
 * <p>
 * MVEL makes the choice once, as it builds what reads the chain: in a copy's first run, again when its JIT compiles the
 * chain, and for a null-safe name the first time its value isn't {@code null}. So the rule is checked where MVEL asks
 * whether a name is a variable, {@link #isResolveable}, and only for a supplied fact's name. What MVEL asks for is told
 * from the frames of the call's stack, which is walked for each such question: MVEL asks while it builds what reads a
 * chain, and each time a run evaluates {@code isdef} of a fact, or reads a fact in a function's body or in an
 * expression with an {@code import} of its own, where a factory of MVEL's asks again as it gets the variable. Any
 * other run that only reads what is built already asks nothing.
 * </p>
 *
 * <p>
 * <b>Pinned to MVEL 2.5.4.</b> The frames are MVEL's own, by class and method name, and for a projection and a with
 * block's statement by line number, none of which MVEL promises to keep:
 * </p>
 * <ul>
 *     <li>the first frame under the factories that pass the question on (a class of {@code org.mvel2.integration},
 *     or a method named {@code isResolveable}, as {@code ast.InvokationContextFactory}'s in a function's body) is
 *     {@code getBeanProperty} or {@code getMethod} of {@code ReflectiveAccessorOptimizer},
 *     {@code ASMAccessorOptimizer} or {@code PropertyAccessor} exactly when the name is the first of a chain;</li>
 *     <li>a restart is a chain whose accessor was asked for by {@code ast.Union}, {@code refl.nodes.Union},
 *     {@code refl.nodes.NullSafe}, {@code ast.NewObjectNode} or {@code ast.LiteralDeepPropertyNode}, past the frames
 *     of {@code optimizers.dynamic} the JIT puts between, or under the accessor's own
 *     {@code compileConstructor};</li>
 *     <li>a scoped body is a frame of {@code ast.WithNode$ParmValuePair} or {@code refl.nodes.WithAccessor} further
 *     up, or one of {@code ast.Fold} at a line other than those where it evaluates the projection's collection and
 *     its filter, which it reads at the root: {@code Fold.java} lines 88, 93, 100, 116, 125 and 131. The walk stops at
 *     this run's own frame, so a run that started this one from a scoped body doesn't count;</li>
 *     <li>a with block's own target isn't in its body. MVEL reads a statement of the block that has no assignment, as
 *     {@code setNote('x')} in {@code with (order) { setNote('x') }}, as the chain {@code order.setNote('x')}, inside
 *     the block, so its first name is the target's, read as the block's target was just before. It is told by the
 *     frames of a statement that is one chain, {@code ast.ASTNode}'s then {@code compiler.ExecutableAccessor}'s, right
 *     under the block's frame at the line where it evaluates such a statement: {@code WithNode.java} line 278 or
 *     {@code WithAccessor.java} line 57. Only when nothing above the block is a restart or a scoped body: there the
 *     target's name falls under that place's rule.</li>
 * </ul>
 *
 * <p>
 * A frame the walk doesn't know is read the safe way where it can be: a projection's frame at an unknown line counts as
 * its element, and a with block's at an unknown line as its body. A later MVEL that renames a class or a method
 * listed here would let a fact be read again, so {@code PropertyReadGuardTest} has a test for each place, which fails
 * when the fact is read, and one for each place MVEL reads at the root, which fails when a fact is refused there: both
 * must pass before the MVEL version changes.
 * </p>
 *
 * <p>
 * A function's body called from a projection's element, from {@code with (t) { ... }} or from {@code t.{ ... }} is
 * read at the root, but its frames are under the scoped body's, so a fact nobody declared is refused there too. A walk
 * with no room on the stack overflows, and the rule fails with that, as nothing then tells where the name stands.
 * </p>
 *
 * <p>
 * Otherwise this is the factory MVEL makes itself for a map of variables: a variable an expression assigns is
 * written to the map once the run ends (see {@link #finished()}).
 * </p>
 */
final class GuardedVariables extends CachingMapVariableResolverFactory {

    private static final long serialVersionUID = 1L;

    /** The name isn't the first of a chain MVEL reads from a value. */
    static final int ROOT = 0;
    /** The name is the first of a chain MVEL starts against a value that isn't a name. */
    static final int RESTART = 1;
    /** The name is the first of a chain in a projection's element or a with block's body. */
    static final int SCOPED_BODY = 2;

    private static final String OWN = GuardedVariables.class.getName();
    private static final String RUN = MvelExpression.class.getName();
    private static final String FACTORY_PACKAGE = "org.mvel2.integration.";
    private static final String IS_RESOLVEABLE = "isResolveable";
    private static final String COMPILE_CONSTRUCTOR = "compileConstructor";
    private static final Set<String> ACCESSORS = Set.of("org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer",
            "org.mvel2.optimizers.impl.asm.ASMAccessorOptimizer", "org.mvel2.PropertyAccessor");
    private static final Set<String> CHAIN_HEADS = Set.of("getBeanProperty", "getMethod");
    private static final String JIT_PACKAGE = "org.mvel2.optimizers.dynamic.";
    private static final Set<String> RESTARTS = Set.of("org.mvel2.ast.Union",
            "org.mvel2.optimizers.impl.refl.nodes.Union", "org.mvel2.optimizers.impl.refl.nodes.NullSafe",
            "org.mvel2.ast.NewObjectNode", "org.mvel2.ast.LiteralDeepPropertyNode");
    private static final Set<String> SCOPES = Set.of("org.mvel2.ast.WithNode$ParmValuePair",
            "org.mvel2.optimizers.impl.refl.nodes.WithAccessor");
    private static final String NODE = "org.mvel2.ast.ASTNode";
    private static final String STATEMENT = "org.mvel2.compiler.ExecutableAccessor";
    // Where WithNode.java and WithAccessor.java evaluate a block's statement that has no assignment, in MVEL 2.5.4.
    private static final Set<String> WITH_STATEMENT_LINES = Set.of("org.mvel2.ast.WithNode$ParmValuePair:278",
            "org.mvel2.optimizers.impl.refl.nodes.WithAccessor:57");
    private static final String FOLD = "org.mvel2.ast.Fold";
    // Where Fold.java evaluates a projection's collection and its filter, at the root, in MVEL 2.5.4.
    private static final Set<Integer> FOLD_ROOT_LINES = Set.of(88, 93, 100, 116, 125, 131);

    // Reads each frame's class by its name, as CallSites does, and its method's name and line.
    private static final StackWalker WALKER = StackWalker.getInstance();
    // What a walk reads the stack with: a class of its own, not a lambda, which the first walk, maybe deep in a stack,
    // would link (see this package's rule on lambdas). Created here, so preparing MVEL, which initializes this class,
    // loads it.
    private static final Function<Stream<StackWalker.StackFrame>, Integer> FRAMES_POSITION = new Position();
    // How many times the stack was walked, in this JVM. Only for tests.
    private static final AtomicLong WALK_COUNT = new AtomicLong();

    // The run's facts, whose names are checked: not the output object or a variable the expression assigns.
    private final transient Map<String, ?> facts;
    // The names of the facts the engine declared.
    private final transient Set<String> declared;
    // The run's last refusal, or null.
    private transient @Nullable FactReadAsProperty refused;

    /**
     * Creates the variables of one run of an expression.
     *
     * @param variables The names the expression reads, and their values, which the variables it assigns are written to
     * @param facts     The run's facts, which are among {@code variables}
     * @param declared  The names of the facts the engine declared
     */
    GuardedVariables(Map<String, ?> variables, Map<String, ?> facts, Set<String> declared) {
        super(variables);
        this.facts = facts;
        this.declared = declared;
    }

    /**
     * Tells MVEL whether a name is a variable, as the factory it makes itself does, unless the name is a supplied
     * fact's where MVEL would read it in place of a property of a value: then the run fails.
     *
     * @param name The name MVEL asks about
     * @return {@code true} if the name is a variable
     * @throws FactReadAsProperty if the name is a supplied fact's at a restart, or an undeclared one's in a scoped body
     */
    @Override
    public boolean isResolveable(String name) {
        boolean resolveable = super.isResolveable(name);
        if (resolveable && facts.containsKey(name)) {
            WALK_COUNT.incrementAndGet();
            int position = WALKER.walk(FRAMES_POSITION);
            if (position == RESTART || position == SCOPED_BODY && !declared.contains(name)) {
                refused = new FactReadAsProperty(name, position);
                throw refused;
            }
        }
        return resolveable;
    }

    /**
     * Ends the run: writes the variables the expression assigned to the map, as MVEL does for the factory it makes
     * itself, then fails the run if it refused a fact, whatever MVEL made of the refusal: MVEL wraps an exception
     * thrown while it builds what reads a chain in its own, and could catch one.
     *
     * @throws FactReadAsProperty the run's last refusal, if it had one
     */
    void finished() {
        externalize();
        if (refused != null) {
            throw refused;
        }
    }

    /**
     * Returns how many times the stack was walked, in this JVM. Only for tests.
     *
     * @return The walks
     */
    static long walks() {
        return WALK_COUNT.get();
    }

    /**
     * Walks the stack once, so the JDK's classes that read a frame's method are loaded and initialized before a first
     * run walks it, maybe deep in a stack. Only {@link MvelExpressionLanguage#prepare()} calls this.
     */
    static void warmUp() {
        WALKER.walk(FRAMES_POSITION);
    }

    /**
     * Tells where a name MVEL asks about stands, from the frames of the call's stack, the top one first.
     *
     * @param frames The frames, from the one that walks the stack
     * @return {@link #RESTART}, {@link #SCOPED_BODY} or {@link #ROOT}
     */
    static int position(Iterator<StackWalker.StackFrame> frames) {
        StackWalker.StackFrame frame = next(frames);
        // This class's own frames, then those of the factories MVEL wraps this one in, which pass the question on.
        while (frame != null && (frame.getClassName().startsWith(OWN)
                || frame.getClassName().startsWith(FACTORY_PACKAGE)
                || IS_RESOLVEABLE.equals(frame.getMethodName()))) {
            frame = next(frames);
        }
        if (frame == null || !ACCESSORS.contains(frame.getClassName())
                || !CHAIN_HEADS.contains(frame.getMethodName())) {
            return ROOT;
        }
        // The accessor's own frames, down to what asked it for the chain.
        String accessor = frame.getClassName();
        boolean constructor = false;
        while (frame != null && accessor.equals(frame.getClassName())) {
            constructor |= COMPILE_CONSTRUCTOR.equals(frame.getMethodName());
            frame = next(frames);
        }
        while (frame != null && frame.getClassName().startsWith(JIT_PACKAGE)) {
            frame = next(frames);
        }
        if (constructor || frame != null && RESTARTS.contains(frame.getClassName())) {
            return RESTART;
        }
        boolean ownTarget = false;
        if (frame != null && NODE.equals(frame.getClassName())) {
            // A statement that is one chain: the node's own frames, then the statement's.
            while (frame != null && NODE.equals(frame.getClassName())) {
                frame = next(frames);
            }
            boolean statement = false;
            while (frame != null && STATEMENT.equals(frame.getClassName())) {
                statement = true;
                frame = next(frames);
            }
            if (statement && frame != null
                    && WITH_STATEMENT_LINES.contains(frame.getClassName() + ':' + frame.getLineNumber())) {
                // The block's statement without an assignment, which MVEL reads as a chain from the block's own
                // target: past the block's frames, and it is the target's name unless something scoped is above.
                ownTarget = true;
                String block = frame.getClassName();
                while (frame != null && block.equals(frame.getClassName())) {
                    frame = next(frames);
                }
            }
        }
        for (; frame != null; frame = next(frames)) {
            String name = frame.getClassName();
            if (RUN.equals(name)) {
                return ROOT;
            }
            if (SCOPES.contains(name) || FOLD.equals(name) && !FOLD_ROOT_LINES.contains(frame.getLineNumber())) {
                return SCOPED_BODY;
            }
            if (ownTarget && (RESTARTS.contains(name)
                    || ACCESSORS.contains(name) && COMPILE_CONSTRUCTOR.equals(frame.getMethodName()))) {
                return SCOPED_BODY;
            }
        }
        return ROOT;
    }

    private static StackWalker.@Nullable StackFrame next(Iterator<StackWalker.StackFrame> frames) {
        return frames.hasNext() ? frames.next() : null;
    }

    /**
     * Where the name MVEL asks about stands, read from the stack with a loop rather than a stream's operations, whose
     * first use would link the JDK's own lambdas.
     */
    private static final class Position implements Function<Stream<StackWalker.StackFrame>, Integer> {

        @Override
        public Integer apply(Stream<StackWalker.StackFrame> stack) {
            return position(stack.iterator());
        }
    }

    /**
     * What fails a run that supplies a fact where MVEL would read it in place of a property of a value. It is the
     * rule's failure, with this as its cause.
     */
    static final class FactReadAsProperty extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private static final String START = "Fact '";
        private static final String AT_RESTART = "' can't be read in place of a property: MVEL reads a name after a "
                + "value in parentheses, a literal, a new object or a null-safe name as the fact of that name, not as "
                + "the value's property";
        private static final String IN_SCOPED_BODY = "' can't be read in place of a property: in a with block or a "
                + "projection's element MVEL reads a name as the fact of that name, not as the value's property, and "
                + "this fact isn't declared. Declare the fact, or read it outside the block";

        FactReadAsProperty(String name, int position) {
            super(MessageQuotes.quotedWithin(START, name, position == RESTART ? AT_RESTART : IN_SCOPED_BODY));
        }
    }
}
