package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.Set;

/**
 * MVEL 2, the engine's default expression language.
 *
 * <ul>
 *     <li>Conditions and actions have the same access to the JVM as Java code, including processes, files and
 *     reflection. There is no sandbox, so only use rules from trusted sources.</li>
 *     <li>A condition is rejected when its text contains an assignment, {@code ++}, {@code --}, or the keywords
 *     {@code with}, {@code def}, {@code function} or {@code import_static}. A write made by calling a method, such as
 *     {@code claim.setApproved(true)}, can't be detected.</li>
 *     <li>Variables an action declares stay local to that action, and assigning to {@code output} fails.</li>
 *     <li>A fact name must be a Java identifier that isn't one of MVEL's reserved words, such as {@code empty} or
 *     {@code in}, and isn't a class name MVEL resolves instead, such as {@code Math} or an imported class.</li>
 *     <li>MVEL caches accessors in a compiled expression without synchronization, so a concurrent run gets its own
 *     compiled copy.</li>
 * </ul>
 */
public final class MvelExpressionLanguage implements ExpressionLanguage {

    /** The language's name. */
    public static final String LANGUAGE_NAME = "mvel";

    /**
     * Creates the MVEL language. It holds no state: each rule list's state lives in its compiler.
     */
    public MvelExpressionLanguage() {
        // Nothing to set up.
    }

    @Override
    public String name() {
        return LANGUAGE_NAME;
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        return new MvelExpressionCompiler(new Imports(Set.copyOf(context.packageImports()),
                Set.copyOf(context.classImports()), context.classLoader()));
    }
}
