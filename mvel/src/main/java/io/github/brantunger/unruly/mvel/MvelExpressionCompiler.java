package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.MessageText;
import io.github.brantunger.unruly.api.language.Session;
import org.jspecify.annotations.Nullable;
import org.mvel2.CompileException;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compiles one rule list's MVEL expressions with the list's imports, and checks fact names against them.
 */
final class MvelExpressionCompiler implements ExpressionCompiler {

    // The name of the engine's logger, as its documentation gives it: core's AbstractRulesEngine.LOGGER_NAME is
    // package-private, and the core module doesn't export its package. The logger is got only when a scan gives up, so
    // MVEL's first load initializes no class of SLF4J's for it.
    static final String LOGGER_NAME = "io.github.brantunger.unruly.engine";

    private final Imports imports;
    private final FactNames factNames;
    // Every expression compiled, for warmUp(). Only load()'s thread compiles and warms up, so a plain list will do.
    private final List<MvelExpression> compiled = new ArrayList<>();
    // Every name the compiled expressions could read a fact by, for factNamesRead(), kept by load()'s thread too.
    private final Set<String> namesRead = new HashSet<>();
    // Whether namesRead holds every name, false once an expression had a word too long to scan (see RuleText.addNames).
    private boolean namesKnown = true;

    /**
     * Creates the compiler for one rule list.
     *
     * @param imports The imports the rule list is compiled with
     */
    MvelExpressionCompiler(Imports imports) {
        this.imports = imports;
        this.factNames = new FactNames(imports);
    }

    /**
     * Compiles a condition after rejecting any assignment in its text. The read-only facts a condition runs against
     * only stop a write to a bare variable at run time. A property write such as {@code claim.approved = true} goes
     * through the fact's own setter, so assignments are rejected here instead.
     */
    @Override
    public CompiledCondition compileCondition(Expression source) {
        ConditionAssignments.Write write = ConditionAssignments.find(source.text());
        if (write != null) {
            throw MvelCompileErrors.rejected(source.text(), write);
        }
        return compile(source);
    }

    @Override
    public CompiledAction compileAction(Expression source) {
        return compile(source);
    }

    private MvelExpression compile(Expression source) {
        MvelAnalysis analysis = new MvelAnalysis(source.text(), imports);
        MvelExpression expression;
        try {
            expression = MvelExpression.compile(analysis);
        } catch (CompileException e) {
            if (MvelCompileErrors.looped(e)) {
                throw MvelCompileErrors.analysisLoop(e);
            }
            // The engine reports an overflow by its cause: an expression too long for MVEL's recursive parser, or a
            // compile called too deep in the stack or on a thread with a small one.
            if (ExceptionReads.rootCause(e) instanceof StackOverflowError) {
                throw e;
            }
            throw MvelCompileErrors.compileError(e, analysis.rejectedType());
        } catch (Imports.ImportTooLarge e) {
            throw MvelCompileErrors.importTooLarge(source.text(), e);
        } catch (IndexOutOfBoundsException e) {
            // MVEL's parser reads out of bounds for some malformed expressions, such as a . or ? it can't read past or
            // blank parentheses, instead of reporting them. One from other code, such as the application's class
            // loader, is reported as is.
            if (!MvelCompileErrors.thrownInMvel(e)) {
                throw e;
            }
            throw MvelCompileErrors.malformed(e);
        } catch (RuntimeException e) {
            // An imported class called like a method, such as ArrayList(y), fails MVEL's cast to a static method.
            if (MvelCompileErrors.calledLikeMethod(e)) {
                throw MvelCompileErrors.classCalledLikeMethod(e);
            }
            // MVEL throws a plain RuntimeException, with no cause and no position, for some expressions it rejects,
            // such as int in = 1 or an ambiguous class name. Any other is reported as is.
            if (!MvelCompileErrors.rejectedPlainly(e)) {
                throw e;
            }
            throw MvelCompileErrors.plainRejection(e);
        }
        compiled.add(expression);
        // Outside the try, which covers only MVEL's compile, so nothing the scan throws, such as an SLF4J provider
        // failing in its logging, reaches the arms that classify MVEL's failures.
        if (namesKnown) {
            namesAdded(source);
        }
        return expression;
    }

    /**
     * Adds the names an expression compiled could read a fact by to {@link #namesRead}, or, if it has a word too long
     * to scan, forgets them all, so {@link #factNamesRead()} says MVEL can't tell, and logs at DEBUG which rule and
     * which limit, once for the rule list, as no later expression is scanned.
     *
     * @param source The expression
     */
    private void namesAdded(Expression source) {
        String limit = RuleText.addNames(source.text(), namesRead);
        if (limit != null) {
            namesKnown = false;
            namesRead.clear();
            LoggerFactory.getLogger(LOGGER_NAME).debug("MVEL checks every fact for this rule list: the {} of rule '{}'"
                    + " has {}, too long to scan for the names it reads", source.kind().name().toLowerCase(Locale.ROOT),
                    MessageText.escape(source.ruleName()), limit);
        }
    }

    /**
     * Creates the session that holds one copy of the rule list's compiled MVEL expressions.
     */
    @Override
    public Session newSession() {
        return new MvelSession();
    }

    /**
     * Compiles every condition and action of the rule list into the session, which otherwise compiles each one the
     * first time it runs. The first session warmed up takes the compilations made when the rules loaded; every later
     * one compiles the expressions again.
     *
     * @param session A session {@link #newSession()} returned
     * @throws IllegalArgumentException if the session isn't one this compiler created
     */
    @Override
    public void warmUp(Session session) {
        MvelSession mvel = MvelExpression.mvelSession(session);
        compiled.forEach(mvel::compiled);
    }

    @Override
    public void checkFactName(String name) {
        factNames.check(name);
    }

    /**
     * Returns the names the compiled conditions and actions could read a fact by, as {@link RuleText#addNames} finds
     * them in their text: each identifier, and for each word between MVEL's whitespace, every span of it that starts
     * at the word's start, just after one of the ends {@code ( ) [ ] { } , ; ' " = < > ! & | ? : * / + % - .}, or just
     * after {@code isdef} glued to the name that follows it, and stops at the word's end or just before a character
     * that isn't part of an identifier, and each backslash and high surrogate alone. So {@code \a} in {@code 1-\a},
     * {@code a.b} in {@code a.b--}, {@code my-fact} in {@code my-fact-1}, {@code #a} in {@code isdef#a} and {@code ,a}
     * in {@code ,a#b} are among them. It holds more names than they read, such as the words of a string literal, a
     * comment or a class's name, and spans MVEL reads as several names, which are only a best guess at the names a
     * rule's author meant, such as {@code my-fact} in {@code my-fact == 1}, which MVEL reads as {@code my} minus
     * {@code fact}. So a fact no MVEL rule names, such as one named {@code in} that only another language's rules
     * read, isn't checked by MVEL. It can still miss a name MVEL reads with whitespace in it, such as one
     * {@code isdef} reads up to a comment on a later line (#1089).
     *
     * <p>
     * Returns {@code null}, so MVEL checks every fact, once a condition or action has a word of more than 1,000
     * characters, or with more than 64 characters that aren't part of an identifier, each one just after
     * {@code isdef} counted twice, the most characters and parts an import may have, as a word's spans grow as the
     * square of those.
     * </p>
     *
     * @return The names, or {@code null} if a word was too long to scan
     */
    // null says MVEL can't tell which facts its rules read, as ExpressionCompiler.factNamesRead() allows.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    @Override
    public @Nullable Set<String> factNamesRead() {
        return namesKnown ? Set.copyOf(namesRead) : null;
    }
}
