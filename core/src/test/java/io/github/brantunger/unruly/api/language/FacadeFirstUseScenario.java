package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.graalvm.nativeimage.MissingReflectionRegistrationError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run by {@link FacadeFirstUseInitializationTest} in a JVM that logs every class it initializes, and builds no engine
 * before it prints {@link #AFTER}. It makes what it reads first, then prints {@link #BEFORE}, calls every public
 * method of {@link Throwables}, {@link ImportLimits} and {@link MessageText} once or more, so each takes each of its
 * paths, and prints {@link #AFTER}. Between the two it concatenates no string and links no lambda, as the test's own
 * code does both through a call site the JVM links on its first use, and it keeps the answers in an array. Then it
 * prints each answer after {@link #ANSWER}, builds an engine and runs it, and prints {@link #ENGINE} and what the run
 * returned. Its text is Latin-1 only: escaping a character above U+00FF reads the JDK's table for it, which no engine
 * initializes either (#1286).
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
        String longText = "x".repeat(MessageText.MAX_TEXT_LENGTH + 100);
        String longName = "n".repeat(MessageText.MAX_NAME_LENGTH + 10);
        Object[] answers = new Object[49];
        int i = 0;

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
