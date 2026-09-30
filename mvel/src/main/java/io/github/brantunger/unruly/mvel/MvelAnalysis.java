package io.github.brantunger.unruly.mvel;

import org.mvel2.CompileException;
import org.mvel2.ast.ASTNode;
import org.mvel2.compiler.AbstractParser;
import org.mvel2.compiler.CompiledExpression;
import org.mvel2.compiler.ExpressionCompiler;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MVEL's analysis pass over one expression, as {@code MVEL.analysisCompile} runs it, which also reads the type
 * that a declaration it rejects names, such as {@code BigDecimal} in {@code BigDecimal total = 0} without the
 * import: MVEL's own description of that error names its parser context, or the literal before the declaration,
 * in place of the type.
 */
final class MvelAnalysis extends ExpressionCompiler {

    private static final long serialVersionUID = 1L;

    // How many times MVEL's analysis may ask for the class loader: a fixed allowance, and more for each character of
    // the expression (see classLoaderCallLimit).
    private static final long CLASS_LOADER_CALLS = 10_000;
    private static final long CLASS_LOADER_CALLS_PER_CHARACTER = 20;

    // An identifier, as Java reads one.
    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    // What MVEL read after the type of a declaration it rejects, up to where it stopped: the variable's name, and
    // the = after it if there is one. A no-break space counts as whitespace. This reads MVEL's error, not the rule's
    // text as MVEL lexes it, so it follows neither of RuleText's rules: \s is U+0009 to U+000D and a space.
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
    MvelAnalysis(String source, Imports imports) {
        // As MVEL.analysisCompile does: the text as it is, so MVEL's positions are positions in it.
        super(source.toCharArray(), MvelExpression.newParserContext(imports));
        this.source = source;
        this.imports = imports;
        setVerifyOnly(true);
        Imports.limitClassLoaderCalls(pCtx.getParserConfiguration(), classLoaderCallLimit(source.length()));
    }

    /**
     * Runs the analysis pass. When MVEL rejects the expression, the type it rejected is read first, for
     * {@link #rejectedType()}.
     *
     * <p>
     * MVEL's analysis never ends for some expressions, such as {@code java.lang.Math.abs(1)x}, a call through a class
     * named with its package with something glued to it, and never checks for an interrupt (#840). On every round it
     * asks the parser configuration for its class loader, so the pass is stopped once it has asked more times than
     * {@link #classLoaderCallLimit} allows for the expression, and the expression is rejected.
     * </p>
     *
     * @return What MVEL compiled, which is only checked
     * @throws CompileException if MVEL rejects the expression, or its analysis went round in a loop: then the cause
     *                          is an {@link Imports.AnalysisLoop}
     */
    @Override
    public CompiledExpression compile() {
        CompiledExpression compiled;
        try {
            compiled = super.compile();
        } catch (CompileException e) {
            throwIfLooped();
            rejected = typeNamed(lastNode, expr, e.getCursor());
            throw e;
        } catch (RuntimeException e) {
            // MVEL throws some failures as they are, not as a CompileException, and one may follow a loop MVEL caught.
            throwIfLooped();
            throw e;
        }
        throwIfLooped();
        return compiled;
    }

    /**
     * Rejects the expression if MVEL's analysis asked for the class loader as many times as the limit allows, whether
     * the pass ended or MVEL caught what was thrown to end it.
     *
     * @throws CompileException if the analysis went round in a loop, with the {@link Imports.AnalysisLoop} as its cause
     */
    private void throwIfLooped() {
        Imports.AnalysisLoop loop = Imports.analysisLoop(pCtx.getParserConfiguration());
        if (loop != null) {
            throw new CompileException(loop.getMessage(), expr, 0, loop);
        }
    }

    /**
     * Returns how many times MVEL's analysis of an expression may ask for the class loader: a fixed allowance of
     * {@value #CLASS_LOADER_CALLS}, and {@value #CLASS_LOADER_CALLS_PER_CHARACTER} more for each character. MVEL asks
     * about once for each part of a chain of properties and calls, at most about once for each character of an
     * expression it analyses to its end, and once on each round of the loop that never ends.
     *
     * <p>
     * MVEL analyses what is inside brackets again for each level of them, so a long chain of names that aren't
     * classes, wrapped in many levels of brackets that aren't needed, asks about as many times as the levels and the
     * chain's parts multiplied, and is rejected once that passes the limit: {@code m.a.a...} with 93 or more
     * {@code .a}, inside as many levels of parentheses (#840).
     * </p>
     *
     * @param length The expression's length
     * @return How many times MVEL may ask
     */
    static long classLoaderCallLimit(int length) {
        return CLASS_LOADER_CALLS + CLASS_LOADER_CALLS_PER_CHARACTER * length;
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
     * Returns the expression's source text.
     *
     * @return The text the pass reads
     */
    String sourceText() {
        return source;
    }

    /**
     * Returns the imports the expression is compiled with.
     *
     * @return The imports
     */
    Imports compiledImports() {
        return imports;
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
        words.addAll(RuleText.wordOperators());
        return Set.copyOf(words);
    }
}
