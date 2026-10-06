package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The contract test for the toy, opted in to both of #1046's narrowings: it reserves {@code self} only for the rule
 * lists that use it, its compiler says which facts its rules read, and it rejects a fact named {@code end}, a keyword
 * of its own, so that every check that looks at fact names runs against a language that opts in.
 */
@DisplayName("the test-only language, reserving its names only where it's used and saying which facts its rules read,"
        + " keeps the expression-language contract")
class ToyScopedFactNamesContractTest extends ToyExpressionLanguageContractTest {

    /** An identifier in a toy expression, or the fact before the dot of a property read. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Wraps a language so that it reserves {@code self} only for the rule lists that use it, rejects a fact named
     * {@code end}, and says its rules read every identifier in their expressions, which holds every fact they read.
     */
    static ExpressionLanguage scoped(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public Set<String> reservedFactNames() {
                return Set.of("self");
            }

            @Override
            public boolean reservesForEveryRuleList() {
                return false;
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                Set<String> read = new HashSet<>();
                return new ForwardingExpressionCompiler(language.newCompiler(context)) {
                    @Override
                    public CompiledCondition compileCondition(Expression expression) {
                        record(expression);
                        return super.compileCondition(expression);
                    }

                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        record(expression);
                        return super.compileAction(expression);
                    }

                    // load() compiles on one thread.
                    private void record(Expression expression) {
                        Matcher matcher = NAME.matcher(expression.text());
                        while (matcher.find()) {
                            read.add(matcher.group());
                        }
                    }

                    @Override
                    public void checkFactName(String name) {
                        if ("end".equals(name)) {
                            throw new IllegalArgumentException("'end' is a keyword: a toy rule can't refer to it");
                        }
                    }

                    @Override
                    public Set<String> factNamesRead() {
                        return read;
                    }
                };
            }
        };
    }

    @Override
    protected ExpressionLanguage language() {
        return scoped(new ToyExpressionLanguage());
    }

    @Override
    protected String unusableFactName() {
        return "end";
    }
}
