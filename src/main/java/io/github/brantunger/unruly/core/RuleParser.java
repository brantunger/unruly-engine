package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import lombok.extern.slf4j.Slf4j;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RuleParser is an MVEL based rule parser implementation. The Parser parses and evaluates conditional expressions and
 * action expressions. When the condition expression evaluates to <strong>true</strong> the action expression can be
 * executed by the {@link RulesEngine}.
 *
 * @param <O> The output object type to instantiate
 */
@Slf4j
public class RuleParser<O> implements Parser<O> {

    private static final String OUTPUT_KEYWORD = "output";
    private ParserContext parserContext;

    /**
     * This method adds package imports to the #{@link org.mvel2.ParserContext} in order to speed up the execution of
     * rules and simplify the rule expression. You may want to use this if many of your rules require the same packages.
     *
     * @param packages A set of packages to import
     * @return A reference to this #{@link RuleParser}
     */
    @Override
    public Parser<O> addImports(Set<String> packages) {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setPackageImports(new HashSet<>(packages));
        this.parserContext = new ParserContext(parserConfiguration);
        return this;
    }

    /**
     * This adds a single package to the #{@link org.mvel2.ParserContext} in order to speed up the execution of
     * rules and simplify the rule expression. Add the fully qualified name of the package as a string. You may want to
     * use this if many of your rules require the same packages.
     *
     * @param packageString The package to import. Example: "java.util.Set"
     * @return A reference to this parser.
     */
    @Override
    public Parser<O> addImport(String packageString) {
        if (this.parserContext != null) {
            Set<String> packages = this.parserContext.getParserConfiguration().getPackageImports();
            this.parserContext.getParserConfiguration().getPackageImports().clear();
            return addImports(packages);
        } else {
            ParserConfiguration parserConfiguration = new ParserConfiguration();
            parserConfiguration.addPackageImport(packageString);
            this.parserContext = new ParserContext(parserConfiguration);
            return this;
        }
    }

    /**
     * Parse the condition field within a {@link Rule} from a pre-compiled expression.
     *
     * @param expression The serialized MVEL expression to evaluate
     * @param facts      The input object store representing facts
     * @return A boolean value that the condition resolves to
     */
    @Override
    public boolean parseCondition(Serializable expression, FactStore<Object> facts) {
        Map<String, Object> entryMap = facts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getValue()));
        try {

            if (this.parserContext != null)
                return MVEL.executeExpression(expression, this.parserContext, entryMap, Boolean.class);
            else return MVEL.executeExpression(expression, entryMap, Boolean.class);
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression Error: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Parse the action field within a {@link Rule} from a pre-compiled rule
     *
     * @param compiledExpression The serialized MVEL expression to evaluate
     * @param outputResult       The output object to assign values to
     * @return The outputResult of parsing the action
     */
    @Override
    public O parseAction(Serializable compiledExpression, O outputResult) {
        Map<String, Object> input = new HashMap<>();
        input.put(OUTPUT_KEYWORD, outputResult);
        try {
            if (this.parserContext != null) MVEL.executeExpression(compiledExpression, this.parserContext, input);
            else MVEL.executeExpression(compiledExpression, input);
            return outputResult;
        } catch (Exception e) {
            log.error("Can not parse MVEL Expression Error: {}", e.getMessage());
            throw e;
        }
    }
}
