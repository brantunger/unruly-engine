package io.github.brantunger.unruly.api.language;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A configurable stub expression language for tests: its rules always match and, unless a test says otherwise, do
 * nothing. A test that needs rules only as something for the engine to run configures one of these instead of
 * spelling out an {@link ExpressionLanguage} and an {@link ExpressionCompiler} of its own.
 *
 * <p>
 * By default it is named {@value #LANGUAGE_NAME}, every condition is true, every action returns
 * {@link ActionResult#done()} without touching the output, its session is {@link Session#none()} and every fact name
 * is accepted. Each knob returns a new language with that one thing changed, so a configured language is immutable
 * and can be shared between engines and threads, as {@link ExpressionLanguage} requires.
 * </p>
 *
 * <p>
 * It has no knob for the result of a condition, because every test that uses it wants every rule to match. A stub
 * whose conditions don't all match, or whose compiler is itself what the test is about, writes its own
 * {@link ExpressionCompiler}. For a test language that really evaluates its expressions, see
 * {@link ToyExpressionLanguage}.
 * </p>
 */
public final class StubExpressionLanguage implements ExpressionLanguage {

    /** The name the language has unless another is given. */
    public static final String LANGUAGE_NAME = "stub";

    private final String languageName;
    private final Function<Expression, CompiledAction> action;
    private final Supplier<Session> session;
    private final Consumer<String> factNameCheck;
    private final Consumer<CompileContext> compilerHook;

    /** Creates the default stub: named {@value #LANGUAGE_NAME}, matching every rule and doing nothing. */
    public StubExpressionLanguage() {
        this(LANGUAGE_NAME, expression -> (actionContext, ignored) -> ActionResult.done(), Session::none, name -> {
        }, compileContext -> {
        });
    }

    private StubExpressionLanguage(String languageName, Function<Expression, CompiledAction> action,
                                   Supplier<Session> session, Consumer<String> factNameCheck,
                                   Consumer<CompileContext> compilerHook) {
        this.languageName = languageName;
        this.action = action;
        this.session = session;
        this.factNameCheck = factNameCheck;
        this.compilerHook = compilerHook;
    }

    /**
     * Creates the default stub under another name, the name its rules then have to be written in.
     *
     * @param languageName The language's name
     * @return The language
     */
    public static StubExpressionLanguage named(String languageName) {
        return new StubExpressionLanguage().withName(languageName);
    }

    private StubExpressionLanguage withName(String newName) {
        return new StubExpressionLanguage(newName, action, session, factNameCheck, compilerHook);
    }

    /**
     * Returns a language whose every rule runs {@code compiledAction}, whatever the action's text is.
     *
     * @param compiledAction What an action does when it runs
     * @return The language
     */
    public StubExpressionLanguage action(CompiledAction compiledAction) {
        return compileAction(expression -> compiledAction);
    }

    /**
     * Returns a language that compiles each action with {@code compileAction}, for a test whose actions do different
     * things depending on their text.
     *
     * @param compileAction Turns an action's expression into what it does when it runs
     * @return The language
     */
    public StubExpressionLanguage compileAction(Function<Expression, CompiledAction> compileAction) {
        return new StubExpressionLanguage(languageName, compileAction, session, factNameCheck, compilerHook);
    }

    /**
     * Returns a language whose compilers hand out the sessions {@code newSession} creates, instead of
     * {@link Session#none()}.
     *
     * @param newSession Creates a session for one copy of the rules, and may throw to fail the run that needed it
     * @return The language
     */
    public StubExpressionLanguage newSession(Supplier<Session> newSession) {
        return new StubExpressionLanguage(languageName, action, newSession, factNameCheck, compilerHook);
    }

    /**
     * Returns a language that checks every fact name with {@code check}, instead of accepting them all.
     *
     * @param check Called with each fact name, and throws to reject it
     * @return The language
     */
    public StubExpressionLanguage checkFactName(Consumer<String> check) {
        return new StubExpressionLanguage(languageName, action, session, check, compilerHook);
    }

    /**
     * Returns a language that calls {@code hook} with the context of each compiler it creates, for a test about what
     * the engine compiles rules with.
     *
     * @param hook Called with the context passed to {@link #newCompiler(CompileContext)}, before the compiler is
     *             created
     * @return The language
     */
    public StubExpressionLanguage onNewCompiler(Consumer<CompileContext> hook) {
        return new StubExpressionLanguage(languageName, action, session, factNameCheck, hook);
    }

    @Override
    public String name() {
        return languageName;
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        compilerHook.accept(context);
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression expression) {
                return (evaluation, ignored) -> true;
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                return action.apply(expression);
            }

            @Override
            public Session newSession() {
                return session.get();
            }

            @Override
            public void checkFactName(String name) {
                factNameCheck.accept(name);
            }
        };
    }
}
