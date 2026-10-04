package io.github.brantunger.unruly.core;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

/**
 * Compiles an engine's rule lists for {@code load()} and {@code validate()}: checks the list itself, compiles each rule
 * with the compiler of its language, and checks the declared fact names with the languages the rules use. It holds
 * only the engine's compile settings and its logger, fixed when the engine is built.
 */
final class RuleListCompiler {

    // The engine's logger, which logs what compiling a rule list fails with.
    private final Logger log;
    // The engine's languages and its default language, fixed when it's built.
    private final LanguageRegistry languages;
    // The imported packages and classes, resolved when the engine is built and passed to every compilation.
    private final Set<String> packageImports;
    private final Set<Class<?>> classImports;
    // The output type languages are told about.
    private final Class<?> outputType;
    // Each language's options, by language name.
    private final Map<String, Map<String, String>> options;
    // The declared type of each fact, by name, a primitive type as it was declared, and whether a run may supply only
    // those facts.
    private final Map<String, Class<?>> declaredFacts;
    private final boolean allFactsDeclared;
    // The fact names the engine's languages reserve, which no declared fact has.
    private final Set<String> reservedFactNames;
    // Each language's own imports, by language name, as written.
    private final Map<String, List<String>> languageImports;

    /**
     * Creates the compiler of an engine's rule lists.
     *
     * @param log               The engine's logger
     * @param languages         The engine's languages and its default language
     * @param packageImports    The imported packages
     * @param classImports      The imported classes
     * @param outputType        The output type languages are told about
     * @param options           Each language's options, by language name
     * @param declaredFacts     The declared type of each fact, by name
     * @param allFactsDeclared  Whether a run may supply only the declared facts
     * @param reservedFactNames The fact names the engine's languages reserve
     * @param languageImports   Each language's own imports, by language name
     */
    RuleListCompiler(Logger log, LanguageRegistry languages, Set<String> packageImports, Set<Class<?>> classImports,
                     Class<?> outputType, Map<String, Map<String, String>> options,
                     Map<String, Class<?>> declaredFacts, boolean allFactsDeclared, Set<String> reservedFactNames,
                     Map<String, List<String>> languageImports) {
        // Initialized here, when the engine is built, so load() and validate() never run a class's initializer: they
        // may be called deep in a run's stack, from an action (see StackHeadroom).
        Mode.values();
        this.log = log;
        this.languages = languages;
        this.packageImports = packageImports;
        this.classImports = classImports;
        this.outputType = outputType;
        this.options = options;
        this.declaredFacts = declaredFacts;
        this.allFactsDeclared = allFactsDeclared;
        this.reservedFactNames = reservedFactNames;
        this.languageImports = languageImports;
    }

    /** Whether a rule list is compiled for {@code load()} or for {@code validate()}. */
    enum Mode {
        LOAD,
        VALIDATE;

        /**
         * Whether to stop at the first problem in the rule list itself, which is all {@code load()} reports.
         *
         * @return {@code true} for {@code load()}
         */
        boolean firstOnly() {
            return this == LOAD;
        }

        /**
         * Whether each failure, and each warning a language reports, is logged: {@code load()} logs,
         * {@code validate()} doesn't. A failure a {@code run()} or a {@code load()} the language started reported
         * isn't logged either way: that run or load logged it.
         *
         * @return {@code true} for {@code load()}
         */
        boolean logged() {
            return this == LOAD;
        }
    }

    /**
     * Finds what makes a rule list unusable before any rule is compiled: a {@code null} entry, and a name a rule
     * shares with an earlier one.
     *
     * @param ruleList The list to check
     * @param mode     Whether the list is loaded, which stops at the first problem, or validated
     * @return One failure for each such entry, in list order; none when the list is usable
     */
    List<RuleCompilationException> listProblems(List<Rule> ruleList, Mode mode) {
        List<RuleCompilationException> problems = new ArrayList<>();
        Set<String> ruleNames = new HashSet<>();
        for (int i = 0; i < ruleList.size(); i++) {
            Rule rule = ruleList.get(i);
            if (rule == null) {
                problems.add(compilationFailure("Rule at index " + i + " of the rule list is null", null, null));
            } else if (!ruleNames.add(rule.getRuleName())) {
                problems.add(compilationFailure("Duplicate rule name '" + Failures.quote(rule.getRuleName()) + "'",
                        null, rule.getRuleName()));
            }
            if (mode.firstOnly() && !problems.isEmpty()) {
                return problems;
            }
        }
        return problems;
    }

    /**
     * Prepares one compilation of a rule list.
     *
     * @param mode Whether the list is loaded or validated
     * @return The compilation, with a compiler registry for the engine's languages
     */
    Compilation compilation(Mode mode) {
        return new Compilation(mode);
    }

    /**
     * One compilation of a rule list, for {@code load()} or {@code validate()}: compiles every rule with the languages'
     * compilers, checks the declared fact names, and collects every failure rather than throwing the first.
     */
    final class Compilation {

        private final boolean logged;
        private final LanguageCompilers compilers;
        private final List<CompiledRule> compiled = new ArrayList<>();
        private final List<RuleCompilationException> failures = new ArrayList<>();
        private Map<String, ExpressionCompiler> used = Map.of();

        /**
         * Prepares a compilation with a compiler registry for the engine's languages.
         *
         * @param mode Whether the list is loaded or validated, which says whether each failure, and each warning a
         *             language reports, is logged (see {@link Mode#logged()})
         */
        private Compilation(Mode mode) {
            this.logged = mode.logged();
            ClassLoader loader = ImportResolver.contextClassLoader();
            // Each language gets its own options and its own imports.
            compilers = new LanguageCompilers(languages.languages(), (name, language) -> newCompiler(name, language,
                    new EngineCompileContext(packageImports, classImports, loader, outputType,
                            options.getOrDefault(name, Map.of()), declaredFacts, allFactsDeclared, logged,
                            languageImports.getOrDefault(name, List.of()), reservedFactNames)));
        }

        /**
         * Compiles the rules in priority order, then checks the declared fact names. The list has no {@code null}
         * entry.
         *
         * @param ruleList The rules
         */
        void compile(List<Rule> ruleList) {
            for (Rule rule : inPriorityOrder(ruleList)) {
                String language = languageOf(rule);
                // A language that can't create its compiler is reported once, for the first rule that needed it. The
                // rules written in it can't be compiled, and asking the language again would only repeat the failure.
                if (compilers.failed(language)) {
                    continue;
                }
                try {
                    compiled.add(compileRule(rule, compilers.forLanguage(language), compilers));
                } catch (RuleCompilationException e) {
                    failed(e);
                }
            }
            // The compilers every fact is checked with: the ones the rules used, or the default language's for an empty
            // list. When rules failed, only the compilers already created check the declared names.
            if (failures.isEmpty()) {
                try {
                    used = compilers.used(languages.defaultLanguage());
                } catch (RuleCompilationException e) {
                    failed(e);
                    used = compilers.created();
                }
            } else {
                used = compilers.created();
            }
            declaredNameFailures(used).forEach(this::failed);
        }

        // A failed run() or load() a language started while compiling or checking a name has already logged its
        // failure. One logged here is recorded, so a run or load around this one's doesn't log it again.
        private void failed(RuleCompilationException failure) {
            if (logged && Failures.nestedRunFailure(failure) == null) {
                log.error(failure.getMessage());
                LoggedFailures.loggedByLoad(failure);
            }
            failures.add(failure);
        }

        /**
         * Returns the rules compiled.
         *
         * @return The rules that compiled, in priority order
         */
        List<CompiledRule> compiledRules() {
            return compiled;
        }

        /**
         * Returns the compilers the declared fact names were checked with, which the runs of a loaded rule list check
         * fact names with.
         *
         * @return The compilers, by language name
         */
        Map<String, ExpressionCompiler> usedCompilers() {
            return used;
        }

        /**
         * Returns every failure found.
         *
         * @return The failures, in the order they were found; none when the rule list compiled
         */
        List<RuleCompilationException> foundFailures() {
            return failures;
        }

        /**
         * Throws the one failure there is, or one exception for several, whose message lists as many as fit. The
         * message counts rules when every failure is a rule's, and failures otherwise: a language that couldn't
         * create its compiler and a rejected declared fact name have no rule. It lists the first failure whole, and
         * each next one while the list stays within {@value Failures#MAX_DESCRIPTION_LENGTH} characters, then counts
         * the rest, which the exception's {@code failures()} still has. Every failure was logged when it happened, so
         * the one exception for several is recorded as logged, and a run or load around a nested {@code load()}
         * doesn't log it again (see {@link LoggedFailures}).
         *
         * @throws RuleCompilationException if there are any
         */
        void throwIfAnyFailed() {
            if (failures.isEmpty()) {
                return;
            }
            throw failures.size() == 1 ? failures.get(0) : LoggedFailures.loggedByLoad(combined(failures));
        }

        /**
         * Closes the compilers created, when the rule list isn't kept.
         *
         * @return The first fatal {@link Error} a compiler threw, for the caller to throw, or {@code null} if none did
         */
        Error closeCompilers() {
            return Closing.compilers(compilers.created());
        }
    }

    /**
     * Checks every declared fact name with the languages of a rule list being loaded, which are the ones its runs check
     * names with, and returns a failure for each name a language rejects or fails to check.
     *
     * @param checks The compilers to check the names with, by language name
     * @return One failure for each declared name that can't be used; none when every name passes
     */
    private List<RuleCompilationException> declaredNameFailures(Map<String, ExpressionCompiler> checks) {
        List<RuleCompilationException> failures = new ArrayList<>();
        // Inside the load() or validate(), whose call-out each check is.
        LoggedFailures.Runs runs = LoggedFailures.inProgress();
        for (String name : declaredFacts.keySet()) {
            IllegalArgumentException rejected = FactIntake.factNameRejection(log, name, checks, false, runs);
            if (rejected != null) {
                failures.add(compilationFailure("Declared fact '" + Failures.quote(name) + "' can't be used: "
                        + Failures.describe(rejected), rejected, null));
            }
        }
        return failures;
    }

    /**
     * Makes the exception for a rejected rule list or a failure to compile, for the caller to log and throw. An
     * interrupt in {@code cause} sets the thread's interrupt status again. A fatal {@link Error} in {@code cause}'s
     * cause chain, or suppressed on it (see {@link Failures#fatalError}), is logged at ERROR here instead, unless a
     * run the language started logged it, and rethrown.
     *
     * @param msg      What failed, naming the rule or the language
     * @param cause    What the expression language threw, or {@code null}
     * @param ruleName The name of the rule that failed, or {@code null} if the failure isn't about one rule
     * @return The exception to throw, caused by {@code cause}
     */
    private RuleCompilationException compilationFailure(String msg, Throwable cause, String ruleName) {
        return compilationFailure(msg, cause, ruleName, null, List.of());
    }

    /**
     * Makes the exception for a failure of a rule's condition or action, as
     * {@link #compilationFailure(String, Throwable, String)} does.
     *
     * @param msg      What failed, naming the rule
     * @param cause    What the expression language threw, or {@code null}
     * @param ruleName The name of the rule that failed, or {@code null} if it has none
     * @param kind     Whether the condition or the action failed
     * @param issues   Where and what the language found wrong
     * @return The exception to throw, caused by {@code cause}
     */
    // Not logged here: load() logs each failure as it collects it, and validate() logs nothing. A fatal error is
    // the exception: it's logged, then rethrown, whichever is compiling, unless a run the language started logged it
    // and nothing wrapped around it says something of its own (see LoggedFailures). One with a cause is the engine's
    // words around what the language threw, which may be a nested run's failure, so it's recorded as the engine's.
    private RuleCompilationException compilationFailure(String msg, Throwable cause, String ruleName,
                                                        ExpressionKind kind,
                                                        List<InvalidExpressionException.Issue> issues) {
        Failures.keepInterruptStatus(cause);
        Error fatal = Failures.fatalError(cause);
        if (fatal != null) {
            if (LoggedFailures.unlogged(cause)) {
                log.error(msg);
            }
            throw fatal;
        }
        RuleCompilationException failure = new RuleCompilationException(msg, cause, ruleName, kind, issues);
        return cause == null ? failure : LoggedFailures.builtByEngine(failure);
    }

    /**
     * Returns the rules in the order they're compiled and evaluated: highest priority first, a rule without one last,
     * and rules of equal priority in list order.
     *
     * @param rules The rules, with no {@code null} entry
     * @return The rules in priority order
     */
    static List<Rule> inPriorityOrder(List<Rule> rules) {
        return rules.stream()
                .sorted(Comparator.comparing(
                        Rule::getPriority,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
    }

    /**
     * Returns the rules that aren't {@code null}, in list order, as {@code validate()} compiles them, having reported
     * each {@code null} entry itself.
     *
     * @param rules The rules
     * @return The rules without the {@code null} entries
     */
    static List<Rule> withoutNulls(List<Rule> rules) {
        return rules.stream().filter(Objects::nonNull).toList();
    }

    private static RuleCompilationException combined(List<RuleCompilationException> failures) {
        String what = failures.stream().allMatch(failure -> failure.getRuleName() != null)
                ? " rules failed to compile: "
                : " failures while loading the rules: ";
        // Bounded, as a rule table loaded after a breaking change can fail thousands of rules at once. Whole failures
        // are left out, not characters, so this doesn't go through Failures.truncate: each message is built from
        // parts shortened and escaped one at a time, so a single message isn't capped at MAX_DESCRIPTION_LENGTH, and
        // a cut could fall inside an escape.
        StringBuilder listed = new StringBuilder(failures.get(0).getMessage());
        int count = 1;
        while (count < failures.size()
                && listed.length() + 2 + failures.get(count).getMessage().length() <= Failures.MAX_DESCRIPTION_LENGTH) {
            listed.append("; ").append(failures.get(count).getMessage());
            count++;
        }
        if (count < failures.size()) {
            listed.append("; and ").append(failures.size() - count).append(" more (see failures())");
        }
        return new RuleCompilationException(failures.size() + what + listed, failures);
    }

    private String languageOf(Rule rule) {
        return rule.getLanguage() != null ? rule.getLanguage() : languages.defaultLanguage();
    }

    /**
     * Creates a language's compiler for one rule list. A language that throws or returns {@code null} fails the rule
     * list, like an expression that doesn't compile.
     *
     * @param name     The language's name
     * @param language The language
     * @param context  The imports and class loader the rule list is compiled with
     * @return The compiler
     * @throws RuleCompilationException if the language throws or returns {@code null}, or its first use prepares it and
     *                                  that throws
     */
    private ExpressionCompiler newCompiler(String name, ExpressionLanguage language, CompileContext context) {
        // A language the engine wasn't built to use is prepared at its first use (see RunClasses). The room for that
        // is checked out here, so a StackOverflowError from the check reaches the caller as it is, with nothing
        // initialized; what prepare() throws fails the language as what newCompiler() throws does, saying which failed.
        boolean firstUse = RunClasses.firstUse(language);
        ExpressionCompiler compiler;
        String failedTo = "prepare";
        try {
            // prepare() and newCompiler() are each a call-out to the language, so each is marked (see LoggedFailures).
            if (firstUse) {
                LoggedFailures.callOut();
                RunClasses.prepare(language);
            }
            failedTo = "create a compiler";
            LoggedFailures.callOut();
            compiler = language.newCompiler(context);
        } catch (Throwable e) {
            throw compilationFailure("The '" + Failures.quote(name) + "' expression language failed to " + failedTo
                    + ": " + Failures.describe(e), e, null);
        }
        if (compiler == null) {
            throw compilationFailure("The '" + Failures.quote(name) + "' expression language returned no compiler",
                    null, null);
        }
        return compiler;
    }

    private CompiledRule compileRule(Rule rule, ExpressionCompiler compiler, LanguageCompilers compilers) {
        String ruleName = rule.getRuleName();
        String displayName = Failures.quote(ruleName);
        if (rule.getCondition().isBlank()) {
            throw compilationFailure("Rule '" + displayName + "' has a blank condition expression", null,
                    ruleName, ExpressionKind.CONDITION, List.of());
        }
        if (rule.getAction().isBlank()) {
            throw compilationFailure("Rule '" + displayName + "' has a blank action expression", null,
                    ruleName, ExpressionKind.ACTION, List.of());
        }
        String language = languageOf(rule);
        if (compiler == null) {
            throw compilationFailure("Rule '" + displayName + "' is written in '" + Failures.quote(language)
                    + "', which isn't one of the engine's expression languages: "
                    + Failures.quoteAll(compilers.languageNames()), null, ruleName);
        }
        CompiledCondition compiledCondition = compile(
                new Expression(ruleName, ExpressionKind.CONDITION, rule.getCondition()), compiler::compileCondition);
        CompiledAction compiledAction = compile(
                new Expression(ruleName, ExpressionKind.ACTION, rule.getAction()), compiler::compileAction);
        return new CompiledRule(rule, displayName, language, compiledCondition, compiledAction);
    }

    /**
     * Compiles one condition or action. An expression the language rejects is reported with the language's reason and
     * issues, or with none if its exception can't give them (see {@link Failures#issuesOf}); anything else the
     * language throws, such as a syntax error it doesn't point to, becomes the cause of the failure. A
     * {@link StackOverflowError}, also as the root of what the language threw, is reported by its cause, which the room
     * left on the stack where it was caught tells (see {@link #overflowReason()}). A fatal {@link Error}, also one the
     * language wraps in its own exception, is logged like any failure and then rethrown.
     *
     * @param source      The expression to compile
     * @param compilation Compiles it with the rule's language
     * @param <T>         The type of compiled expression
     * @return The compiled expression
     * @throws RuleCompilationException if the expression doesn't compile, or the language returns {@code null}
     */
    private <T> T compile(Expression source, Function<Expression, T> compilation) {
        String expression = Failures.expression(source.kind(), source.ruleName());
        T compiled;
        try {
            LoggedFailures.callOut();
            compiled = compilation.apply(source);
        } catch (InvalidExpressionException e) {
            // A language's own subclass may have a getMessage() or an issues() that throws.
            String reason = Failures.clip(Failures.messageOr(e, "was rejected by its expression language"));
            throw compilationFailure(expression + " " + reason, e, source.ruleName(), source.kind(),
                    Failures.issuesOf(e));
        } catch (Throwable e) {
            // MVEL's parser recurses once per operator, so a very long expression overflows the stack; so does any
            // expression compiled with too little stack left, such as by a load called deep in a stack or on a thread
            // with a small one (#1013).
            String reason = Failures.rootCause(e) instanceof StackOverflowError ? overflowReason()
                    : Failures.describe(e);
            throw compilationFailure(expression + " failed to compile: " + reason, e, source.ruleName(), source.kind(),
                    List.of());
        }
        if (compiled == null) {
            throw compilationFailure(expression + " wasn't compiled: its expression language returned null", null,
                    source.ruleName(), source.kind(), List.of());
        }
        return compiled;
    }

    /**
     * Tells why compiling an expression overflowed the stack, from the room left where the overflow was caught, a few
     * frames below {@code load()} or {@code validate()}. Room there for {@link StackHeadroom#checkInitializing()}, far
     * more than a compile needs before it reaches the expression, means the expression itself took the stack: it is too
     * long or too deeply nested. Too little means the call that compiled it started too deep in the stack, where a
     * short, valid expression can overflow too, as a language's first load does while it loads its classes, or on a
     * thread whose whole stack is smaller than that room, where a long expression that overflows is reported so too.
     * Nothing but the check runs on the way, which gives its stack back before this returns, and an overflow in it is
     * caught here, so what the language threw stays the failure's cause.
     *
     * @return The reason, for the failure's message
     */
    private static String overflowReason() {
        try {
            Faults.at(Faults.Step.OVERFLOW_ROOM_CHECKED);
            StackHeadroom.checkInitializing();
            return "the expression is too long or too deeply nested to compile";
        } catch (StackOverflowError e) {
            return "the stack ran out: it was compiled too deep in the stack, or on a thread whose stack is too small";
        }
    }
}
