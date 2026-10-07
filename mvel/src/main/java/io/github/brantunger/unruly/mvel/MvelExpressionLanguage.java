package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.mvel2.DataConversion;
import org.mvel2.MVEL;
import org.mvel2.Operator;
import org.mvel2.ast.OperatorNode;
import org.mvel2.compiler.AbstractParser;
import org.mvel2.integration.PropertyHandlerFactory;
import org.mvel2.math.MathProcessor;
import org.mvel2.optimizers.impl.refl.nodes.GetterAccessor;
import org.mvel2.util.ErrorUtil;
import org.mvel2.util.ParseTools;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MVEL 2 as an expression language for the engine, named {@value #LANGUAGE_NAME}. A rule whose language is
 * {@code null} is written in MVEL when MVEL is the engine's only language, or when
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder#defaultLanguage(String)} names it.
 *
 * <ul>
 *     <li>Conditions and actions have the same access to the JVM as Java code, including processes, files and
 *     reflection. There is no sandbox, so only use rules from trusted sources.</li>
 *     <li>A condition is rejected when its text contains an assignment, {@code ++}, {@code --}, or the keywords
 *     {@code with}, {@code def}, {@code function} or {@code import_static}. A write made by calling a method, such as
 *     {@code claim.setApproved(true)}, can't be detected.</li>
 *     <li>Variables an action declares stay local to that action, and assigning to {@code output} fails.</li>
 *     <li>A fact name must be a Java identifier that isn't one of MVEL's reserved words, such as {@code empty} or
 *     {@code in}, and isn't a class name MVEL resolves instead, such as {@code Math} or an imported class, nor the
 *     first part of the name of a class the rule list's expressions name with its package, such as {@code java} for
 *     {@code java.lang.Integer.MAX_VALUE}, or find through an import. MVEL checks a fact's name only where the text of
 *     the rule list's MVEL expressions holds it, as a name or as a word, such as {@code my-fact} in
 *     {@code my-fact == 1}. That is a best effort: a name glued to a minus sign, as in {@code my-fact-1}, which MVEL
 *     reads as {@code my} minus {@code fact} minus 1, isn't checked, nor are some names MVEL reads whole that aren't
 *     identifiers, such as {@code \a} in {@code 1-\a}.</li>
 *     <li>MVEL caches accessors in a compiled expression without synchronization, so each session, which one run
 *     uses at a time, runs its own compiled copy of each expression.</li>
 *     <li>MVEL's imports are Java packages and classes, given with
 *     {@link io.github.brantunger.unruly.api.RulesEngineBuilder#imports(String...)}. It takes no imports of its own:
 *     given any with {@link io.github.brantunger.unruly.api.RulesEngineBuilder#languageImports(String, String...)},
 *     {@link #newCompiler(CompileContext)} throws {@link IllegalArgumentException}. The engine creates MVEL's compiler
 *     when a rule list given to {@code load()} or {@code validate()} has an MVEL rule, and for a list with no rules
 *     too when MVEL is the default language. That rule list then fails: {@code load()} throws, and
 *     {@code validate()} returns, a {@link io.github.brantunger.unruly.api.exception.RuleCompilationException} with
 *     the throw as its cause. A rule list that doesn't use MVEL never has its imports checked.</li>
 * </ul>
 *
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/mvel.md">MVEL</a>
 * @see <a href="https://github.com/brantunger/unruly-engine/blob/main/docs/languages/mvel-fact-names.md">MVEL fact
 *      names</a>
 */
public final class MvelExpressionLanguage implements ExpressionLanguage {

    /** The language's name. */
    public static final String LANGUAGE_NAME = "mvel";

    // Set once prepare() has done its work, its warm-up included, in this class loader's copy of this class. Without an
    // initializer, so the class still has no static initializer of its own.
    private static volatile boolean prepared;

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

    /**
     * Creates the compiler for one rule list, with the context's Java imports, class loader, declared facts and
     * options.
     *
     * @param context The imports and class loader the rule list is compiled with
     * @return A new compiler, used for this rule list only
     * @throws IllegalArgumentException if the context has {@link CompileContext#languageImports() language imports},
     *                                  which MVEL doesn't take, or an option MVEL can't apply
     */
    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        Objects.requireNonNull(context, "context must not be null");
        // ErrorUtil first, so a missing mvel2 is named on every call: rejecting language imports uses FactNames, whose
        // static initializer needs mvel2, so after the first call it would only say FactNames couldn't be initialized.
        initializeErrorReporting();
        rejectLanguageImports(context.languageImports());
        return new MvelExpressionCompiler(new Imports(Set.copyOf(context.packageImports()),
                Set.copyOf(context.classImports()), context.classLoader(), DeclaredTypes.inputsFor(context)));
    }

    /**
     * Initializes the classes with a static initializer that loading MVEL rules, running them and a load that fails
     * would otherwise be the first to use: this language's own, MVEL's, and those of the JDK that only they use, such
     * as the JDK's logging, which MVEL logs with. A load or a run may be nested deep in another run's stack, where a
     * {@link StackOverflowError} inside one of them would leave the class unusable for the life of the JVM, and so
     * every later MVEL load or run, or every later report of a compile error. The engine calls this once it has made
     * room for it: when it builds an engine whose builder names MVEL, as its default language or otherwise, or else
     * when a rule list first uses MVEL. They're initialized in the order a first load and run initialize them, so
     * MVEL's optimizer sets up as {@link MvelExpression} sets it up, and only those that this module's code uses
     * directly: each initializes what it needs itself, which depends on MVEL's settings, such as whether its JIT is on;
     * and MVEL's {@code GetterAccessor}, which a rule's first read of a property through its getter initializes. Its
     * static initializer creates the empty array that MVEL's calls of a method or a constructor with no arguments read,
     * which catch no {@link Error}, so one left unusable would fail every such call and every {@code new} without
     * arguments, in every MVEL engine for the life of the JVM. Not those of MVEL's other features: a rule's first
     * inline list or map, {@code new} or {@code soundslike} initializes MVEL classes of its own, which this doesn't, so
     * that first use, like the JIT's first compile, can overflow in one of them deep in a stack, which leaves only that
     * feature unusable. Nor those of MVEL's JIT, which compiles an accessor with ASM once more than 50 runs have used
     * it within 100 ms: its first compile initializes ASM's classes and the JDK's that it uses. MVEL marks an accessor
     * compiled before it compiles it, so a compile that fails, such as one that overflows deep in a stack, fails that
     * run and isn't tried again: the accessor stays reflective, slower but correct. If the overflow strikes a class's
     * static initializer, of ASM's or the JDK's, that class stays unusable for the JVM's life, and every other
     * accessor's first compile fails one run the same way. Then it evaluates a property read, a method call and a
     * method call with a literal argument once each, through MVEL's public API, on a value of this module's own. A
     * JVM's first read, first call and first literal argument load classes MVEL evaluates them with, which takes more
     * stack than {@code run()}'s own check of the room makes sure of, so a first run deep in a stack can fail its rule;
     * loading them here leaves that run fewer to load, so it needs less stack. Not nothing: a rule's first call of a
     * method makes the JDK build the method's reflective accessor, which for a signature of a shape no call has had yet
     * generates classes, and deep in a stack that can still overflow and fail the rule. Until a run returns normally
     * having run an action of an MVEL rule, the engine checks runs for more room, which covered a first call of one
     * method in the shapes measured, one with a {@code new} or a call among its arguments too, but not deeper nesting.
     * When the engine prepares MVEL, at {@code build()} or a first load, those evaluations initialize no class with a
     * static initializer that it hasn't, so if they overflow, nothing is left unusable: they're skipped, and tried
     * again by the next call, which the engine makes only for an engine whose builder names MVEL. A bare
     * {@code prepare()} call in an otherwise empty JVM may also initialize JDK classes the reflective call needs.
     * Anything else they throw is thrown. Once it has succeeded, in the class loader that loaded this module, later
     * calls return at once; calls made before that, such as two first calls at once, each do the work.
     */
    @Override
    public void prepare() {
        // Once: the last steps read a throwable's stack trace and compile and run three expressions, which cost more
        // than finding a class initialized already, and an engine that names MVEL calls this on every build.
        if (prepared) {
            return;
        }
        initializeErrorReporting();
        initialize(MethodHandles.lookup(), List.of(ExactNameClassLoader.class, ExceptionReads.class, FactNames.class,
                SplittableRandom.class, AbstractParser.class, ConditionAssignments.class, MvelAnalysis.class,
                MvelExpression.class, MVEL.class, ParseTools.class, OperatorNode.class, Operator.class,
                AtomicReference.class, CallSites.class, MathProcessor.class, DataConversion.class,
                PropertyHandlerFactory.class, GetterAccessor.class, CalledCodeFailures.class, MvelCompileErrors.class));
        // This module's classes without a static initializer that a load that fails would otherwise be the first to
        // load: a condition that assigns, a class called like a method, an import with too many parts, and the place
        // of either in the text. A class's first load deep in a stack can overflow as initializing one can (#1066,
        // #1097).
        initialize(MethodHandles.lookup(), List.of(ConditionAssignments.Write.class, MvelCompileErrors.Position.class,
                Imports.ClassCalledLikeMethod.class, Imports.ImportTooLarge.class));
        MvelCompileErrors.warmUp();
        // A rule's first use of one of the JDK's classes by its name, such as new java.util.ArrayList(), looks its
        // class file up first (see FactNames.mayBeClass), which on JDK 25 initializes the JDK's class that finds the
        // files of its own modules, so this looks one up. Through this module's own class loader, which needs no
        // permission under a security manager, as the system class loader may; or, were this module loaded by the
        // boot class loader, which has no object, the system class loader, which needs none from there either.
        @SuppressWarnings("PMD.UseProperClassLoader")
        ClassLoader own = MvelExpressionLanguage.class.getClassLoader();
        FactNames.mayBeClass(Objects.requireNonNullElseGet(own, ClassLoader::getSystemClassLoader),
                Object.class.getName());
        // A run whose rule calls code that throws reads the stack trace of what it threw, which initializes the JDK's
        // classes that describe a frame of one of its modules, so this reads one created in a method of the JDK's.
        ExceptionReads.stackTraceOf(Optional.<Throwable>empty().orElseGet(Throwable::new));
        // A JVM's first property read, first method call and first literal argument load classes MVEL evaluates them
        // with, which take more stack than run()'s own check of the room makes sure of, so this evaluates one of each.
        // Done only if it didn't overflow: the next call tries again.
        prepared = warmedUp(MvelExpression::warmUp);
    }

    /**
     * Returns {@value ActionContext#OUTPUT_NAME}, the name MVEL's actions see the output object by, which would hide a
     * fact of the same name. Said here rather than left to the default, so a change to the default doesn't change
     * MVEL.
     *
     * @return {@value ActionContext#OUTPUT_NAME} alone
     */
    @Override
    public Set<String> reservedFactNames() {
        return Set.of(ActionContext.OUTPUT_NAME);
    }

    /**
     * Returns {@code false}: only MVEL's actions see the output object by {@value ActionContext#OUTPUT_NAME}, so the
     * name is reserved only for a rule list with an MVEL rule, or with no rules while MVEL is the default language. A
     * fact declared with it is then rejected by {@code load()} and {@code validate()} for such a rule list, rather
     * than by {@code build()}.
     *
     * @return {@code false}
     */
    @Override
    public boolean reservesForEveryRuleList() {
        return false;
    }

    /**
     * Initializes each class through {@code lookup}, in order, skipping one it can't reach, which a first use then
     * initializes instead.
     *
     * @param lookup  The lookup to initialize the classes through
     * @param classes The classes
     */
    static void initialize(MethodHandles.Lookup lookup, List<Class<?>> classes) {
        for (Class<?> type : classes) {
            try {
                lookup.ensureInitialized(type);
            } catch (IllegalAccessException ignored) {
                // Left to its first use.
            }
        }
    }

    /**
     * Runs the warm-up of {@link #prepare()}, and tells whether it finished. One that overflowed is skipped: a
     * {@link StackOverflowError} it throws, or one anywhere in the cause chain of what it throws, as MVEL wraps one
     * thrown in a method it calls. When the engine prepares MVEL, at {@code build()} or a first load, its evaluations
     * initialize no class with a static initializer that it hasn't, so an overflow in them leaves nothing unusable,
     * and the JVM's first run loads the classes they load instead, as it would without them. A bare {@code prepare()}
     * call in an otherwise empty JVM may also initialize JDK classes the reflective call needs. Anything else it
     * throws is thrown as it is, so {@code prepare()} fails.
     *
     * @param warmUp The warm-up
     * @return {@code true} if it finished, {@code false} if it overflowed
     */
    static boolean warmedUp(Runnable warmUp) {
        try {
            warmUp.run();
            return true;
        } catch (StackOverflowError e) {
            return false;
        } catch (VirtualMachineError e) {
            // Fatal: as it is, with nothing allocated for it.
            throw e;
        } catch (Throwable e) {
            for (Throwable link : ExceptionReads.causeChain(e)) {
                if (link instanceof StackOverflowError) {
                    return false;
                }
            }
            throw e;
        }
    }

    /**
     * Initializes {@link ErrorReporting}, after resolving ErrorUtil. Without mvel2, resolving it fails with
     * {@link NoClassDefFoundError} naming the missing class, and the JVM fails every later resolution the same way, so
     * every call names it. Initializing the holder first would name it only the first time: the holder's failed static
     * initializer leaves it unusable, and every later call would only say that it couldn't be initialized.
     */
    private static void initializeErrorReporting() {
        Objects.requireNonNull(ErrorUtil.class);
        ErrorReporting.initialize();
    }

    /**
     * Rejects imports given to MVEL alone: its imports are Java packages and classes, which every language of an engine
     * is given from {@code imports(...)}, so one given to it alone is a mistake rather than something to ignore.
     *
     * @param languageImports The imports given to MVEL alone
     * @throws IllegalArgumentException if there are any, naming as many as fit in 1,000 characters (see
     *                                  {@link FactNames#quotedAllWithin})
     */
    private static void rejectLanguageImports(List<String> languageImports) {
        if (!languageImports.isEmpty()) {
            throw new IllegalArgumentException(FactNames.quotedAllWithin("MVEL takes no language imports; give Java "
                    + "imports with imports(...): ", languageImports));
        }
    }

    /**
     * MVEL formats every compile error with ErrorUtil, whose static initializer creates a logger. When the first error
     * in the JVM is a stack overflow, such as a deeply nested rule on a small stack, that initializer fails for lack of
     * stack, and the JVM marks the class unusable: every later MVEL compile error in the JVM then throws
     * NoClassDefFoundError. This holder initializes it in {@link #prepare()}, and again, for a compiler created
     * without an engine, when a rule list starts loading, before any rule is compiled, so an overflow only fails the
     * rule that caused it. It isn't done when the language is created: an application may create it anywhere, maybe
     * deep in a stack, before any engine has made room for it, and an engine that finds it with
     * {@link java.util.ServiceLoader} may never use it. It also installs
     * {@link MvelWarningFilter} on MVEL's logger, once for each class loader that loads this module, before any rule
     * runs, for the same reason: a run would otherwise initialize it first, possibly deep in the stack of a run nested
     * in an action, where a stack overflow would leave it unusable and fail every later run. It's only initialized
     * through {@link #initializeErrorReporting()}, which resolves ErrorUtil first, so a missing mvel2 is named on every
     * call rather than only the first.
     */
    private static final class ErrorReporting {

        static {
            new ErrorUtil();
            MvelWarningFilter.initialize();
        }

        private ErrorReporting() {
        }

        /** Initializes ErrorUtil and the filter, the first time it's called, by initializing this class. */
        static void initialize() {
            // The static initializer does the work.
        }
    }
}
