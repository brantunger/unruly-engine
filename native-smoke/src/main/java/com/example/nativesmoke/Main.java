package com.example.nativesmoke;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CancelRegistration;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.Session;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs MVEL rules the way an application would, so CI can build it into a GraalVM native image and run it. It goes
 * through each way the engine and MVEL use reflection: a record fact read by property, a bean output an action assigns
 * to, a map output, and a JDK static method. It also pins down what the engine does with a fact named after a class
 * in an imported package, the one class name the fact-name check resolves for itself: the name is rejected on both
 * platforms, which an image managed only once the check stopped asking for the class file it serves to nobody. The
 * bean and map engines each run more than MVEL's JIT threshold of about 50 runs, so a native image meets whatever
 * MVEL does after it. It also writes and reads a class that isn't public through the engine's own reflection, as a
 * language other than MVEL would, where the image has no metadata for the public types above it. And it stops a run
 * past its timeout with an action registered with onCancel, which the engine's timer thread starts on a pooled
 * platform thread. It prints one line and exits with 1 if a result is wrong.
 */
public final class Main {

    /**
     * How many times the bean and map engines run, well past the number of runs after which MVEL's JIT optimizer
     * steps in.
     */
    private static final int RUNS = 200;

    /** The part of the message the engine rejects a fact name with, which the fact-name check looks for. */
    private static final String REJECTION = "cannot be used as a fact name";

    /**
     * What the fact-name check does, on the JVM and in a native image alike: it rejects a fact named after a class in
     * an imported package, and accepts one whose package isn't imported. An image answered {@code "accepted,prime"}
     * until it stopped looking the class file up as a resource, which it serves for no class, and loaded the class
     * the way MVEL does; this application is what holds both platforms to the one answer.
     */
    private static final String FACT_NAME = "rejected,prime";

    /** The name of the language whose action spins until its run passes its deadline, and of its rule. */
    private static final String SPIN = "spin";

    /**
     * The fact the rules read.
     *
     * @param creditScore The applicant's credit score
     * @param income      The applicant's yearly income
     */
    public record Applicant(int creditScore, int income) {
    }

    /** The output a first-match engine's actions assign to, as a bean. */
    public static final class LoanDecision {

        private String rate;

        /** Creates a decision with no rate. */
        public LoanDecision() {
            // No rate until a rule sets one.
        }

        /**
         * Returns the rate a rule chose.
         *
         * @return The rate, or {@code null}
         */
        public String getRate() {
            return rate;
        }

        /**
         * Sets the rate.
         *
         * @param rate The rate
         */
        public void setRate(String rate) {
            this.rate = rate;
        }
    }

    /**
     * A rate, which the image has no metadata for, so the engine can't reach the methods of {@link Grade} and
     * {@link Rating} through it.
     */
    public interface Rated {

        /**
         * Returns the rate.
         *
         * @return The rate, or {@code null}
         */
        String getRate();

        /**
         * Sets the rate.
         *
         * @param rate The rate
         */
        void setRate(String rate);
    }

    /**
     * A level of a type a subclass picks, which the image has no metadata for: {@link Tier}'s override of its setter
     * gets a bridge, whose target the engine looks up on this class.
     *
     * @param <T> The level's type
     */
    public abstract static class Leveled<T> {

        /** Creates one with no level. */
        protected Leveled() {
            // No level until a subclass sets one.
        }

        /**
         * Sets the level.
         *
         * @param level The level
         */
        public abstract void setLevel(T level);
    }

    // Not public, and the class that declares the bridge for setLevel. The image has metadata for its getter, its
    // setter and the bridge, which is how the output writer finds the bridge on Grade, and then looks its target up.
    static class Tier extends Leveled<String> {

        private String level;

        public String getLevel() {
            return level;
        }

        @Override
        public void setLevel(String level) {
            this.level = level;
        }
    }

    // An output that isn't public, so the writer looks its setters up on Rated, Leveled and Object before calling its
    // own. The image has metadata for its own getter and setter, and none for a list of its methods: in a strict image
    // built with one, the lookup on Leveled threw NoSuchMethodException, so the bridge's target lookup never met the
    // image's error.
    static final class Grade extends Tier implements Rated {

        private String rate;

        @Override
        public String getRate() {
            return rate;
        }

        @Override
        public void setRate(String rate) {
            this.rate = rate;
        }
    }

    // A fact that isn't public, so FactProperties looks its getter up on Rated and Object before calling its own. The
    // image has metadata for a list of its methods, which reading a fact needs, and for its own getter and setter.
    static final class Rating implements Rated {

        private String rate = "standard";

        @Override
        public String getRate() {
            return rate;
        }

        @Override
        public void setRate(String rate) {
            this.rate = rate;
        }
    }

    private Main() {
    }

    /**
     * Runs the rules and checks the results.
     *
     * @param args Unused
     */
    // A command-line check: the line it prints is its result.
    @SuppressWarnings("PMD.SystemPrintln")
    public static void main(String[] args) {
        String bean = beanOutput();
        String map = mapOutput();
        String factName = factNameOutcome();
        String hidden = hiddenOutcome();
        String deadline = deadlineOutcome();
        boolean ok = "prime".equals(bean) && "standard,raised".equals(map) && FACT_NAME.equals(factName)
                && "prime,gold,standard".equals(hidden) && "timeout,platform".equals(deadline);
        System.out.println("native smoke " + (ok ? "OK" : "FAILED") + ": bean=" + bean + " map=" + map
                + " factName=" + factName + " hidden=" + hidden + " deadline=" + deadline
                + " dateResource=" + dateResource()
                + " dateFactValue=" + dateFactValue() + " image=" + nativeImage()
                + " jit=" + (Boolean.getBoolean("mvel2.disable.jit") ? "off" : "on"));
        if (!ok) {
            System.exit(1);
        }
    }

    // Which of the two runs of this binary the status line came from: CI runs the image, and the start script
    // :native-smoke:installDist writes runs the same code on the JVM once something launches it. Give that one
    // -Dmvel2.disable.jit=true in JAVA_OPTS, because a -D on the script's own command line reaches main as a program
    // argument and the run keeps jit=on. GraalVM sets this property only in an image, so no run has to be told which
    // it is. A diagnostic, and nothing is held to it: both platforms are held to one expectation.
    private static boolean nativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    // The java.util import is here, and not only on the fact-name engines below, because those run once each: this is
    // the only engine that resolves a package import past MVEL's JIT threshold, and the only one that works the
    // check's cache of names that aren't classes over many runs.
    private static String beanOutput() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName(), "java.util").build()) {
            engine.load(decisionRules());
            LoanDecision decision = new LoanDecision();
            for (int i = 0; i < RUNS; i++) {
                decision = engine.run(applicant(760 + i % 10, 50_000));
            }
            return decision == null ? "no match" : decision.getRate();
        }
    }

    private static String mapOutput() {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .build()) {
            engine.load(List.of(
                    Rule.builder().ruleName("standard").condition("applicant.creditScore < 750")
                            .action("output.put('rate', 'standard')").build(),
                    Rule.builder().ruleName("raised").condition("Math.max(applicant.income, 0) > 100000")
                            .action("output.put('limit', 'raised')").build()));
            Map<String, Object> output = Map.of();
            for (int i = 0; i < RUNS; i++) {
                output = engine.run(applicant(700, 150_000 + i));
            }
            return Objects.toString(output.get("rate")) + "," + Objects.toString(output.get("limit"));
        }
    }

    // Both halves of the fact-name check, in the same binary, so a pass means the package import is what rejects the
    // name and not something else about it: a fact named Date is rejected on an engine that imports the java.util
    // package, and accepted on one that imports only the Applicant class. Only the package import makes the check
    // resolve a class name of its own, which is the step the two platforms take differently: a JVM looks the class
    // file up as a resource first and loads the class only if that found it, and an image, which serves no class
    // file, loads it straight away. The rejection is logged at ERROR, so a green run prints that line before its
    // "native smoke OK:" line.
    private static String factNameOutcome() {
        return packageImportRejects() + "," + classImportAccepts();
    }

    private static String packageImportRejects() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName(), "java.util").build()) {
            engine.load(decisionRules());
            FactStore<Object> facts = datedApplicant();
            try {
                engine.run(facts);
                return "accepted";
            } catch (IllegalArgumentException e) {
                // The message must name the fact too: the engine rejects an output fact with a message that ends the
                // same way, and a run's facts are checked in no particular order.
                String message = String.valueOf(e.getMessage());
                return message.contains(REJECTION) && message.contains("'Date'")
                        ? "rejected" : "unexpected: " + message;
            }
        }
    }

    private static String classImportAccepts() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName()).build()) {
            engine.load(decisionRules());
            FactStore<Object> facts = datedApplicant();
            try {
                LoanDecision decision = engine.run(facts);
                return decision == null ? "no match" : decision.getRate();
            } catch (IllegalArgumentException e) {
                return "unexpected: " + e.getMessage();
            }
        }
    }

    // The default output writer and FactProperties, called directly, as a language other than MVEL calls them, on
    // classes that aren't public. Each looks a setter or getter up on the public types above its class, and the writer
    // looks the target of Tier's bridge up on Leveled. The image has no metadata for those types, so a strict image
    // throws MissingReflectionRegistrationError there: the engine must read that as a type without the method, and
    // call the class's own, which the image has metadata for. The image's error isn't an Exception, so it isn't
    // caught here: it ends the run with a stack trace that names the lookup that threw.
    private static String hiddenOutcome() {
        Grade grade = new Grade();
        OutputWriter<Object> writer = OutputWriter.beansAndMaps();
        try {
            writer.set(grade, "rate", "prime");
            writer.set(grade, "level", "gold");
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
        }
        return grade.getRate() + "," + grade.getLevel() + "," + FactProperties.read(new Rating(), "rate");
    }

    // A run whose action spins until an action it registered with onCancel stops it, as a runtime that can only be
    // stopped from outside does: the run fails with its timeout, and the action ran on a platform thread. The
    // registration is closed in a finally, as try-with-resources would warn that the body never reads it.
    @SuppressWarnings("PMD.UseTryWithResources")
    private static String deadlineOutcome() {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        CompiledAction spin = (context, session) -> {
            AtomicBoolean stop = new AtomicBoolean();
            CancelRegistration deadline = context.onCancel(() -> {
                ranOn.set(Thread.currentThread());
                stop.set(true);
            });
            try {
                while (!stop.get()) {
                    Thread.onSpinWait();
                }
            } finally {
                deadline.close();
            }
            return ActionResult.done();
        };
        ExpressionLanguage spinning = new ExpressionLanguage() {
            @Override
            public String name() {
                return SPIN;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        return spin;
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(spinning).defaultLanguage(SPIN).runTimeout(Duration.ofMillis(200)).build()) {
            engine.load(List.of(Rule.builder().ruleName(SPIN).condition("true").action(SPIN).build()));
            try {
                engine.run(new FactMap<>());
                return "returned";
            } catch (RuleExecutionException e) {
                Thread thread = ranOn.get();
                return (e.getCause() instanceof TimeoutException ? "timeout" : "failed: " + e.getMessage()) + ","
                        + (thread == null ? "not run" : thread.isVirtual() ? "virtual" : "platform");
            }
        }
    }

    // A diagnostic, never an assertion: it must not feed into ok or the exit code. It shows why the check has to ask
    // an image a different question — an image serves no class file as a resource, and prints false here however the
    // check above ends. It asks the loader the engine resolves package imports with, the thread's context loader, as
    // core's ImportResolver.contextClassLoader does, so the two see the same class path here; the fallback for a thread
    // without one stands in for that method's library loader, which this application shares a class path with. It is
    // still read separately from the engine, and what the loader answers is nothing the engine promises.
    private static String dateResource() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = context != null ? context : ClassLoader.getSystemClassLoader();
        return String.valueOf(loader.getResource("java/util/Date.class") != null);
    }

    // A diagnostic, like dateResource, and for the same reason: it must not feed into ok or the exit code. It is what
    // settled what an accepted name costs, and is kept because it is the one line that would catch the check going
    // quiet again. A rule copies whatever MVEL reads 'Date' as into the output, and the check rejects the name first,
    // so "rejected" is what both platforms print; "class java.util.Date" is the image reading the imported class,
    // which means the check accepted a name MVEL resolves and the fact is invisible to every rule. A value, not a
    // null check — the class isn't null either, so Date != null would settle nothing. Its own engine, so neither
    // half of the check above is disturbed.
    private static String dateFactValue() {
        try (RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports(Applicant.class.getName(), "java.util").build()) {
            engine.load(List.of(Rule.builder().ruleName("dateFact").condition("applicant.creditScore >= 0")
                    .action("output.rate = '' + Date").build()));
            LoanDecision decision = engine.run(datedApplicant());
            return decision == null ? "no match" : String.valueOf(decision.getRate());
        } catch (RuntimeException e) {
            // The status line is the deliverable, so anything thrown is reported on it, in one line, not thrown on.
            String message = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            return message.contains(REJECTION) ? "rejected" : e.getClass().getSimpleName() + ": " + message;
        }
    }

    // MVEL checks only the fact names its rules' text holds, so the prime rule names Date in a comment: the fact-name
    // check above then asks MVEL about a fact named Date, and the rule reads nothing by it.
    private static List<Rule> decisionRules() {
        return List.of(
                Rule.builder().ruleName("prime").priority(2).condition("applicant.creditScore >= 750")
                        .action("// Date\noutput.rate = 'prime'").build(),
                Rule.builder().ruleName("standard").priority(1).condition("applicant.creditScore < 750")
                        .action("output.rate = 'standard'").build());
    }

    // An applicant, and a fact named after java.util.Date. The check above needs no rule to read it, only one whose
    // text names it, as decisionRules' comment does, while the probe's rule reads it on purpose, to see what MVEL makes
    // of the name.
    private static FactStore<Object> datedApplicant() {
        FactStore<Object> facts = applicant(760, 50_000);
        facts.setValue("Date", "2026-01-01");
        return facts;
    }

    private static FactStore<Object> applicant(int creditScore, int income) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("applicant", new Applicant(creditScore, income));
        return facts;
    }
}
