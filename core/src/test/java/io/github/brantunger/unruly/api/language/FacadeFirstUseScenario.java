package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.core.EngineCompileContext;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Run by {@link FacadeFirstUseInitializationTest} in a JVM that logs every class it initializes, and builds no engine
 * before it prints {@link #AFTER}. It makes what it reads first, then prints {@link #BEFORE}, calls every public
 * method of {@link Throwables}, {@link ImportLimits} and {@link MessageText} once or more, so each takes each of its
 * paths, then builds a rule whose name and tag start with a Greek letter, declares facts, adds a language and makes
 * run options with such names (#1292), makes a compile context as a language's tests make one, with a package import
 * and with one that isn't a valid package name, which it rejects (#1271), makes three with a declared fact, an
 * {@code int}, a {@code long} and a {@code String}, as a declared fact initializes the engine's own
 * {@code internal.Widening} (#1322), and prints {@link #AFTER}. Between the two
 * it concatenates no string and links no lambda, as the test's own code does both through a call site the JVM links
 * on its first use, and it keeps the answers in an array. Then it prints each answer after {@link #ANSWER}, builds an
 * engine and runs it, and prints {@link #ENGINE} and what the run returned. Its text has characters above U+00FF, of
 * each plane the escaping decides a character of: escaping one read the JDK's table for it, a class the JVM builds on
 * its first use, before the engine decided them from a table of its own (#1286); and a name that starts with one read
 * the same table in the blank check, {@code String.isBlank()}, before the engine decided that from comparisons of its
 * own (#1292). Before {@link #BEFORE} it reads one character of that text, so the JVM's code for such strings,
 * {@code java.lang.StringUTF16}, is initialized before the calls, as it is at startup on Windows but not on Linux or
 * macOS: the test assumes the JVM has read such a string already. Before it too, it creates the engine builder the
 * names are given to, and it touches
 * {@link RunOptions}, whose static initializer makes the default options, the language and collections the calls are
 * given, whose classes, {@code HashMap} aside, have static initializers of their own: the calls' blank checks are what
 * is measured, not what they are given. Not the {@code TreeSet} a rule and run options used to sort their tags in,
 * whose class has one too: the engine sorts them itself now, and the test checks that nothing before the calls
 * initialized it or {@code TreeMap}, and that the calls initialize neither (#1298).
 */
final class FacadeFirstUseScenario {

    static final String BEFORE = "SCENARIO facades start";
    static final String AFTER = "SCENARIO facades ended";
    static final String ANSWER = "SCENARIO answer: ";
    static final String ENGINE = "SCENARIO engine: ";
    // A constant, which javac joins itself, so it links nothing.
    private static final String MISSING = "The program tried to reflectively access class p.A without it being"
            + " registered for runtime reflection.";

    private FacadeFirstUseScenario() {
    }

    /** A {@code getMessage()} that throws, as a language's own exception's may. */
    private static final class MessageThrows extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("no message");
        }
    }

    /** A {@code getCause()} that throws, which ends the cause chain. */
    private static final class CauseThrows extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException("no cause");
        }
    }

    public static void main(String[] args) {
        OutOfMemoryError fatal = new OutOfMemoryError("probe");
        RuntimeException plain = new RuntimeException("outer", new IllegalArgumentException("inner"));
        RuntimeException fatalCause = new RuntimeException("caused", fatal);
        RuntimeException fatalSuppressed = new RuntimeException("suppressing");
        fatalSuppressed.addSuppressed(new IllegalStateException("beside", fatal));
        RuntimeException interruptCause = new RuntimeException("interrupted", new InterruptedException("probe"));
        RuntimeException interruptSuppressed = new RuntimeException("suppressing");
        interruptSuppressed.addSuppressed(new InterruptedException("beside"));
        StackOverflowError overflow = new StackOverflowError();
        InternalError wrappedOverflow = new InternalError("wrapped", overflow);
        NoClassDefFoundError brokenClass = new NoClassDefFoundError("Could not initialize class p.A");
        brokenClass.initCause(overflow);
        NoClassDefFoundError wrongName = new NoClassDefFoundError("p/a (wrong name: p/A)");
        NoClassDefFoundError wrongNameLast = new NoClassDefFoundError("p/A (wrong name: p/a)");
        NoClassDefFoundError noMessage = new NoClassDefFoundError();
        // Its message given whole, as the constructor that words it from a name concatenates with +.
        MissingReflectionRegistrationError missing = new MissingReflectionRegistrationError((CharSequence) MISSING);
        MessageThrows messageThrows = new MessageThrows();
        CauseThrows causeThrows = new CauseThrows();
        // Joined with concat, as + would link a call site, which initializes java.lang.Long on JDK 21.
        String manyParts = "a.".repeat(ImportLimits.MAX_IMPORT_PARTS).concat("B");
        String tooLong = "a".repeat(ImportLimits.MAX_IMPORT_LENGTH + 1);
        String text = "a\nb\rc\td\u0001e\u007ff\u0085g\u00a0h\u00adi 'j' \\k \u00e9";
        // Text above U+00FF, one character of each plane's kind the escaping decides: a Greek letter, a format
        // character of plane 1 (U+1BCA0, as its surrogate pair), a tag character of plane 14 (U+E0001), an
        // ideographic space, the line separator and a CJK letter of plane 2 (U+20000) (#1286).
        String wide = "a\u03c0b\ud82f\udca0c\udb40\udc01d\u3000e\u2028f\ud840\udc00g";
        String longText = "x".repeat(MessageText.MAX_TEXT_LENGTH + 100);
        String longName = "n".repeat(MessageText.MAX_NAME_LENGTH + 10);
        // Names that start with a character above U+00FF, whose blank check read the JDK's table for it, as escaping
        // did, before the engine decided that from comparisons of its own (#1292): a rule's name and tag, two facts',
        // a language's and a run's tag. What the calls are given is made here: the builder, with a factory that isn't
        // a method reference, as linking one initializes java.lang.Long on JDK 21, which the calls must be the first
        // that could; the default run options, as RunOptions has a static initializer; the language, as its class has
        // one; and the collections, whose classes have one each.
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.firstMatch(new Supplier<>() {
            @Override
            public Map<String, Object> get() {
                return new HashMap<>();
            }
        });
        RunOptions options = RunOptions.defaults();
        ToyExpressionLanguage piLanguage = new ToyExpressionLanguage("\u03c0lang");
        List<String> piTag = new ArrayList<>();
        piTag.add("\u03c0tag");
        Map<String, Class<?>> piFacts = new HashMap<>();
        piFacts.put("\u03c0facts", String.class);
        Set<String> piRunTag = new HashSet<>();
        piRunTag.add("\u03c0run");
        // The package imports a compile context is given, one of them not a valid package name (#1271).
        Set<String> packages = Set.of("java.util");
        Set<String> malformed = Set.of("zq..b");
        // The options and the declarations of the contexts with a declared fact, which initialize internal.Widening
        // (#1322): two of a primitive type, kept as its wrapper, and one of a class. One is a long, which Widening
        // must tell without long.class, as that literal reads Long.TYPE, which initializes java.lang.Long on JDK 21; so
        // it's made here as the component type of a long[], which initializes nothing.
        Map<String, String> noOptions = Map.of();
        Map<String, Class<?>> intFact = Map.of("x", int.class);
        Map<String, Class<?>> longFact = Map.of("n", long[].class.getComponentType());
        Map<String, Class<?>> stringFact = Map.of("s", String.class);
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Object[] answers = new Object[64];
        int i = 0;
        // The test's precondition: the JVM has read a string with a character above U+00FF already, as a JVM on
        // Windows has at startup, so the calls aren't the first to initialize java.lang.StringUTF16, the JDK's code
        // for such strings, which a literal's creation doesn't and the first read of one of its characters does. On
        // Linux and macOS nothing at startup reads one, and a deep first read breaks StringUTF16 for the life of the
        // JVM, which the library can't avoid for a call with no engine built; a separate issue covers it. Read with
        // a plain call, printed without concatenation, as a concat call site initializes java.lang.Long (#1286).
        System.out.println(wide.codePointAt(1));

        System.out.println(BEFORE);
        answers[i++] = Throwables.isOverflow(null);
        answers[i++] = Throwables.isOverflow(overflow);
        answers[i++] = Throwables.isOverflow(wrappedOverflow);
        answers[i++] = Throwables.isOverflow(brokenClass);
        answers[i++] = Throwables.isOverflow(plain);
        answers[i++] = Throwables.isFatal(null);
        answers[i++] = Throwables.isFatal(fatal);
        answers[i++] = Throwables.isFatal(overflow);
        answers[i++] = Throwables.isFatal(wrappedOverflow);
        answers[i++] = Throwables.isFatal(new InternalError("not an overflow"));
        answers[i++] = Throwables.isFatal(plain);
        answers[i++] = Throwables.fatalError(null);
        answers[i++] = Throwables.fatalError(fatal);
        answers[i++] = Throwables.fatalError(fatalCause);
        answers[i++] = Throwables.fatalError(fatalSuppressed);
        answers[i++] = Throwables.fatalError(plain);
        answers[i++] = Throwables.isInterrupt(null);
        answers[i++] = Throwables.isInterrupt(interruptCause);
        answers[i++] = Throwables.isInterrupt(interruptSuppressed);
        answers[i++] = Throwables.isInterrupt(plain);
        answers[i++] = Throwables.messageOf(plain);
        answers[i++] = Throwables.messageOf(messageThrows);
        answers[i++] = Throwables.causeChain(null);
        answers[i++] = Throwables.causeChain(plain);
        answers[i++] = Throwables.causeChain(causeThrows);
        answers[i++] = Throwables.rootCause(plain);
        answers[i++] = Throwables.rootCause(causeThrows);
        answers[i++] = Throwables.isWrongName(null, "p.a");
        answers[i++] = Throwables.isWrongName(wrongName, "p.a");
        answers[i++] = Throwables.isWrongName(wrongNameLast, "p.a");
        answers[i++] = Throwables.isWrongName(brokenClass, "p.a");
        answers[i++] = Throwables.isWrongName(plain, "p.a");
        answers[i++] = Throwables.isWrongName(noMessage, "p.a");
        answers[i++] = Throwables.isMissingRegistration(null);
        answers[i++] = Throwables.isMissingRegistration(missing);
        answers[i++] = Throwables.isMissingRegistration(plain);
        answers[i++] = ImportLimits.importSizeProblem("java.util");
        answers[i++] = ImportLimits.importSizeProblem(manyParts);
        answers[i++] = ImportLimits.importSizeProblem(tooLong);
        answers[i++] = MessageText.leftOut(3);
        answers[i++] = MessageText.escape(text);
        answers[i++] = MessageText.truncate("short");
        answers[i++] = MessageText.truncate(longText);
        answers[i++] = MessageText.quote(text);
        answers[i++] = MessageText.quote(longName);
        answers[i++] = MessageText.escapeWithin(text, 12);
        answers[i++] = MessageText.escapeWithin(longText, 20, 40);
        answers[i++] = MessageText.quoteWithin(text, 12);
        answers[i++] = MessageText.quoteWithin("short", 40);
        answers[i++] = MessageText.escape(wide);
        answers[i++] = MessageText.quote(wide);
        answers[i++] = MessageText.escapeWithin(wide, 40);
        answers[i++] = MessageText.escapeWithin(wide, 6, 40);
        answers[i++] = MessageText.quoteWithin(wide, 40);
        // The builder's calls answer the builder itself, so each is kept as whether it did.
        answers[i++] = Rule.builder().ruleName("\u03c0rule").condition("x == 1").action("put hit true").tags(piTag)
                .build();
        answers[i++] = builder.fact("\u03c0fact", String.class) == builder;
        answers[i++] = builder.facts(piFacts) == builder;
        answers[i++] = builder.language(piLanguage) == builder;
        answers[i++] = options.withTags(piRunTag);
        answers[i++] = new EngineCompileContext(packages, Set.of(), loader).packageImports();
        Object rejected;
        try {
            rejected = new EngineCompileContext(malformed, Set.of(), loader);
        } catch (IllegalArgumentException e) {
            rejected = e;
        }
        answers[i++] = rejected;
        answers[i++] = new EngineCompileContext(packages, Set.of(), loader, Object.class, noOptions, intFact, false)
                .declaredFacts();
        answers[i++] = new EngineCompileContext(packages, Set.of(), loader, Object.class, noOptions, longFact, false)
                .declaredFacts();
        answers[i++] = new EngineCompileContext(packages, Set.of(), loader, Object.class, noOptions, stringFact, false)
                .declaredFacts();
        System.out.println(AFTER);

        for (Object answer : answers) {
            System.out.println(ANSWER + describe(answer));
        }
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new ToyExpressionLanguage()).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("x == 1").action("put hit true").build()));
        System.out.println(ENGINE + engine.run(new FactMap<>(new Fact<Object>("x", 1))));
        engine.close();
    }

    // An answer as one line: a throwable by its class and message, a list by its throwables, text escaped.
    private static String describe(Object answer) {
        if (answer instanceof Throwable thrown) {
            return thrown.getClass().getSimpleName() + "(" + MessageText.escape(String.valueOf(thrown.getMessage()))
                    + ")";
        }
        if (answer instanceof List<?> list) {
            List<String> described = new ArrayList<>();
            for (Object element : list) {
                described.add(describe(element));
            }
            return described.toString();
        }
        return answer instanceof String string ? MessageText.escape(string) : String.valueOf(answer);
    }
}
