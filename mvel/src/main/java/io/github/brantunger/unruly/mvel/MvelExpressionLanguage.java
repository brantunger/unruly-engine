package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.mvel2.util.ErrorUtil;

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
 *     <li>MVEL caches accessors in a compiled expression without synchronization, so each session, which one run
 *     uses at a time, runs its own compiled copy of each expression.</li>
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
        ErrorReporting.initialize();
        return new MvelExpressionCompiler(new Imports(Set.copyOf(context.packageImports()),
                Set.copyOf(context.classImports()), context.classLoader(), DeclaredTypes.inputsFor(context)));
    }

    /**
     * MVEL formats every compile error with ErrorUtil, whose static initializer creates a logger. When the first error
     * in the JVM is a stack overflow, such as a deeply nested rule on a small stack, that initializer fails for lack of
     * stack, and the JVM marks the class unusable: every later MVEL compile error in the JVM then throws
     * NoClassDefFoundError. This holder initializes it when a rule list starts loading, before any rule is compiled, so
     * an overflow only fails the rule that caused it. It isn't done when the language is created, because an engine
     * creates its languages when it's built, and building an engine loads no MVEL class.
     */
    private static final class ErrorReporting {

        static {
            new ErrorUtil();
        }

        private ErrorReporting() {
        }

        /** Initializes ErrorUtil, the first time it's called, by initializing this class. */
        static void initialize() {
            // The static initializer does the work.
        }
    }
}
