package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Session;
import org.mvel2.CompileException;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.ast.ASTNode;
import org.mvel2.compiler.AbstractParser;
import org.mvel2.compiler.CompiledExpression;
import org.mvel2.compiler.ExpressionCompiler;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.Serializable;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One compiled MVEL condition or action, shared by every run of its rule list.
 *
 * <p>
 * MVEL caches an accessor in a compiled expression the first time it runs, and replaces it without synchronization
 * when a later run binds the same name to a different kind of object, so MVEL's compiled form isn't shared: each
 * {@link MvelSession} runs its own, from {@link #newCompiled()}.
 * </p>
 *
 * <p>
 * While it runs, {@link MvelWarningFilter} drops the WARNING MVEL logs itself, with the value unescaped, when a method
 * call or an indexed read fails inside it, most often because MVEL can't convert a fact to the method's parameter
 * type. The run still fails, and the engine reports the failure.
 * </p>
 *
 * <p>
 * While MVEL compiles it, the thread's context class loader is the rule list's {@link ExactNameClassLoader}. After
 * the rule list's class loader refuses a name, MVEL's {@code ParseTools.createClass} asks the thread's context class
 * loader for it, unless that is the same loader. It looks a property read through a value it types as {@code Object}
 * up as a class nested in {@code Object}, such as {@code java.lang.Object$p7} for {@code f.p7}, and one of the JDK's
 * class loaders kept a lock object for each such name it was asked for (#807). MVEL initialises a class a rule names
 * in full while it compiles, so the class's static initialiser sees the rule list's class loader as the context class
 * loader: one that keeps it keeps that loader, and one that asks it for a class defined at run time, with one of the
 * JDK's class loaders as the application's, is refused. The thread's own is restored when
 * MVEL returns or throws, on a run's thread too when it compiles the expression again for a new session. Running the
 * expression doesn't change it.
 * </p>
 */
final class MvelExpression implements CompiledCondition, CompiledAction {

    static {
        // MVEL's optimizer, as it sets up, makes a JVM-wide class loader whose parent is the thread's context class
        // loader at that moment, and keeps it for the life of the JVM. Set up here, before any compilation sets the
        // rule list's class loader as the context class loader, with MVEL's own class loader as the context class
        // loader, it holds neither a rule list's nor the one the first thread to load rules happens to have.
        withClassLoader(mvelClassLoader(), OptimizerFactory::getDefaultAccessorCompiler);
    }

    private final String source;
    private final Imports imports;
    // MVEL's compiled expression from when the rule list loaded, which no run has used, until a session takes it.
    private final AtomicReference<Serializable> loaded;

    private MvelExpression(String source, Imports imports, Serializable loaded) {
        this.source = source;
        this.imports = imports;
        this.loaded = new AtomicReference<>(loaded);
    }

    /**
     * Compiles an expression.
     *
     * @param source  The expression's source text
     * @param imports The imports to compile it with
     * @return The compiled expression
     */
    static MvelExpression compile(String source, Imports imports) {
        return compile(new Analysis(source, imports));
    }

    /**
     * Compiles an expression after MVEL's analysis pass over it.
     *
     * @param analysis The analysis pass, which hasn't run yet, of the expression to compile. When it rejects the
     *                 expression, {@link Analysis#rejectedType()} tells what it could read of the reason.
     * @return The compiled expression
     */
    static MvelExpression compile(Analysis analysis) {
        // compileExpression alone accepts some malformed input (e.g. `x == == 1`) and defers the error to
        // run(). The analysis pass catches more of it up front.
        return withClassLoader(analysis.imports.classLoader(), () -> {
            analysis.compile();
            return new MvelExpression(analysis.source, analysis.imports,
                    MVEL.compileExpression(analysis.source, newParserContext(analysis.imports)));
        });
    }

    @Override
    public Object evaluate(EvaluationContext context, Session session) throws Exception {
        // MVEL's WARNING for a failed method call or indexed read can quote a fact unescaped; the engine escapes it.
        boolean outermost = MvelWarningFilter.enter();
        try {
            return MVEL.executeExpression(compiledIn(session), (Object) null, context.facts());
        } catch (RuntimeException e) {
            // What the rule's Java code threw, so a failure has the same cause before and after MVEL's JIT.
            throw CalledCodeFailures.<RuntimeException>unwrapped(e);
        } finally {
            MvelWarningFilter.leave(outermost);
        }
    }

    @Override
    public ActionResult execute(ActionContext context, Session session) throws Exception {
        // Reads the facts; the output object and the action's own assignments stay in this action, which changes the
        // output in place.
        boolean outermost = MvelWarningFilter.enter();
        try {
            MVEL.executeExpression(compiledIn(session), (Object) null,
                    new ActionVariables(context.facts(), context.output()));
        } catch (RuntimeException e) {
            throw CalledCodeFailures.<RuntimeException>unwrapped(e);
        } finally {
            MvelWarningFilter.leave(outermost);
        }
        return ActionResult.done();
    }

    /**
     * Returns MVEL's compiled form of this expression for a new session: the one compiled when the rule list loaded,
     * the first time, then a new compilation. The expression already compiled with these imports, so MVEL's analysis
     * pass isn't repeated. Sessions on several threads can call it at once.
     *
     * @return A compiled expression that no other session runs
     */
    Serializable newCompiled() {
        Serializable first = loaded.getAndSet(null);
        return first != null ? first
                : withClassLoader(imports.classLoader(),
                        () -> MVEL.compileExpression(source, newParserContext(imports)));
    }

    /**
     * Calls MVEL with a class loader as the thread's context class loader, such as the rule list's while it compiles,
     * so MVEL asks no other class loader for a name the rule list's refuses, and restores the thread's own once MVEL
     * returns or throws.
     *
     * @param loader The class loader to set
     * @param call   The call to MVEL
     * @param <T>    What the call returns
     * @return What the call returned
     */
    private static <T> T withClassLoader(ClassLoader loader, Supplier<T> call) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return call.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    // The class loader of the MVEL running the rules. PMD asks for the context class loader instead, which is the one
    // MVEL's optimizer mustn't hold.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static ClassLoader mvelClassLoader() {
        return OptimizerFactory.class.getClassLoader();
    }

    private Serializable compiledIn(Session session) {
        return mvelSession(session).compiled(this);
    }

    /**
     * Returns the session as the MVEL session it must be.
     *
     * @param session A session an MVEL compiler created
     * @return The session
     * @throws IllegalArgumentException if another language's compiler created it
     */
    static MvelSession mvelSession(Session session) {
        if (session instanceof MvelSession mvel) {
            return mvel;
        }
        throw new IllegalArgumentException("An MVEL expression runs with a session its compiler created, not "
                + session);
    }

    /**
     * Creates a context used by exactly one compilation. MVEL records variables, their types and inline
     * {@code import} statements on the context and its configuration, and a compiled expression goes on using
     * its context when it first runs. A context shared across rules let one rule change how another compiled,
     * and let {@code load()} modify it while a concurrent {@code run()} was still reading it. Only the
     * names found not to be classes are shared with the other compilations of the rule list; see
     * {@link Imports#newConfiguration()}.
     */
    private static ParserContext newParserContext(Imports imports) {
        ParserContext context = new ParserContext(imports.newConfiguration());
        if (imports.stronglyTyped()) {
            // Applied here, not only where the rule list loads, because a session compiles the expression again for
            // its own copy: without this, only the first copy would be the one that was type-checked.
            context.setStrongTyping(true);
            imports.inputs().forEach(context::addInput);
        }
        return context;
    }

    /**
     * MVEL's analysis pass over one expression, as {@code MVEL.analysisCompile} runs it, which also reads the type
     * that a declaration it rejects names, such as {@code BigDecimal} in {@code BigDecimal total = 0} without the
     * import: MVEL's own description of that error names its parser context, or the literal before the declaration,
     * in place of the type.
     */
    static final class Analysis extends ExpressionCompiler {

        private static final long serialVersionUID = 1L;

        // An identifier, as Java reads one.
        private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
        // What MVEL read after the type of a declaration it rejects, up to where it stopped: the variable's name, and
        // the = after it if there is one. A no-break space counts as whitespace.
        private static final Pattern DECLARED_NAME = Pattern.compile("[\\s\\u00a0]+" + IDENTIFIER + "[\\s\\u00a0]*=?");
        // A type's name: an identifier, or several joined with dots, and any number of [], such as java.util.Map[].
        private static final Pattern TYPE_NAME = Pattern.compile(IDENTIFIER + "(\\." + IDENTIFIER + ")*(\\[])*");
        // The words no part of a qualified name can be: Java's keywords, true, false and null, and _. MVEL names such
        // a word as a declaration's type, as this in this Zzz s = 1.
        private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte",
                "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum",
                "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
                "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
                "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                "transient", "try", "void", "volatile", "while", "_", "true", "false", "null");
        // The words a type's own name, the last part of a qualified one, can't be either: those Java doesn't allow as
        // a type's name, MVEL's literals that aren't classes, such as empty or nil, and its word operators, such as in
        // or contains. A package may have such a name, as java.util.function does.
        private static final Set<String> TYPE_WORDS = typeWords();

        private final String source;
        private final transient Imports imports;
        private String rejected;

        /**
         * Creates the analysis pass over an expression, with a parser context of its own.
         *
         * @param source  The expression's source text
         * @param imports The imports to compile it with
         */
        Analysis(String source, Imports imports) {
            // As MVEL.analysisCompile does: the text as it is, so MVEL's positions are positions in it.
            super(source.toCharArray(), newParserContext(imports));
            this.source = source;
            this.imports = imports;
            setVerifyOnly(true);
        }

        /**
         * Runs the analysis pass. When MVEL rejects the expression, the type it rejected is read first, for
         * {@link #rejectedType()}.
         *
         * @return What MVEL compiled, which is only checked
         */
        @Override
        public CompiledExpression compile() {
            try {
                return super.compile();
            } catch (CompileException e) {
                rejected = typeNamed(lastNode, expr, e.getCursor());
                throw e;
            }
        }

        /**
         * Returns the type's name MVEL's last node holds when the pass rejected the expression, as
         * {@link #typeNamed} reads it. It's read whatever MVEL rejected the expression for: the compiler names it only
         * after MVEL's words for a declaration whose type couldn't be resolved.
         *
         * @return The type's name as the expression wrote it, or {@code null} if the pass didn't reject the
         *         expression, or its last node isn't a type's name MVEL stopped after, as {@link #typeNamed} tells,
         *         which rules out names holding a word Java or MVEL reserves
         */
        String rejectedType() {
            return rejected;
        }

        /**
         * Reads the type a declaration names from the node MVEL rejected it at, as MVEL read it, only when the node is
         * that type: an {@link ASTNode} itself, not one of its subclasses, that ends at or before where MVEL stopped,
         * within the text, with only whitespace (a no-break space counts), the variable's name, any whitespace and an
         * {@code =} if there is one between them, and whose name is a type's: identifiers joined by dots, then any
         * number of {@code []}. No part of the name is a Java keyword, {@code true}, {@code false}, {@code null} or
         * {@code _}, such as {@code this} or {@code class}, and its last part, the type's own name, isn't one of the
         * words Java doesn't allow as a type's name ({@code var}, {@code yield}, {@code record}, {@code sealed},
         * {@code permits}), MVEL's literals that aren't classes, such as {@code empty}, or its word operators, such
         * as {@code in}: a package may have such a name, as {@code java.util.function} does. MVEL leaves an older
         * node in place for an error inside parentheses, brackets or a block, and a node of another kind, such as the
         * literal that ends the statement before, for other statements.
         *
         * @param node   MVEL's last node, or {@code null} if it has none
         * @param expr   The expression's text
         * @param cursor Where MVEL stopped in it
         * @return The type's name, or {@code null} if the node isn't a type's name MVEL stopped after
         */
        static String typeNamed(ASTNode node, char[] expr, int cursor) {
            if (node == null || !ASTNode.class.equals(node.getClass())) {
                return null;
            }
            int end = node.getStart() + node.getOffset();
            if (end > cursor || cursor > expr.length
                    || !DECLARED_NAME.matcher(CharBuffer.wrap(expr, end, cursor - end)).matches()) {
                return null;
            }
            String name = node.getName();
            if (!TYPE_NAME.matcher(name).matches()) {
                return null;
            }
            String[] parts = name.replace("[]", "").split("\\.");
            return Arrays.stream(parts).noneMatch(JAVA_KEYWORDS::contains)
                    && !TYPE_WORDS.contains(parts[parts.length - 1]) ? name : null;
        }

        private static Set<String> typeWords() {
            Set<String> words = new HashSet<>(List.of("var", "yield", "record", "sealed", "permits"));
            AbstractParser.LITERALS.forEach((word, value) -> {
                if (!(value instanceof Class)) {
                    words.add(word);
                }
            });
            for (String operator : AbstractParser.OPERATORS.keySet()) {
                if (Character.isJavaIdentifierStart(operator.charAt(0))) {
                    words.add(operator);
                }
            }
            return Set.copyOf(words);
        }
    }
}
