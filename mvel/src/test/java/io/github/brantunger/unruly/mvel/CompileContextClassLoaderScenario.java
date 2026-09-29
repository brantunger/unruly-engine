package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.impl.asm.ASMAccessorOptimizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run as its own JVM by {@link CompileContextClassLoaderTest}, so that the first rule list it loads is the first thing
 * in the JVM to use MVEL, with a class loader of the application's own, as a plug-in's, as the thread's context class
 * loader. MVEL's optimizer, as it sets up, makes a JVM-wide class loader whose parent is the thread's context class
 * loader at that moment, and keeps it for the life of the JVM. MVEL sets it up while it compiles an inline list, as in
 * the rule's condition, which would make it the rule list's class loader if the optimizer weren't set up before, and
 * the plug-in's if it were set up with the thread's. It prints what that parent is once a rule list has loaded and run.
 */
final class CompileContextClassLoaderScenario {

    static final String PARENT = "MESSAGE MVEL's class loader's parent is ";

    static final String MVEL_OWN = "MVEL's own class loader";

    private CompileContextClassLoaderScenario() {
    }

    public static void main(String[] args) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new ClassLoader(previous) {
        });
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).build()) {
            engine.load(List.of(Rule.builder().ruleName("r").condition("[1, 2].contains(f.p)")
                    .action("output.put('k', 1)").build()));
            engine.run(new FactMap<>(new Fact<>("f", Map.of("p", 1))));
        } finally {
            thread.setContextClassLoader(previous);
        }
        ClassLoader parent = ((ClassLoader) ASMAccessorOptimizer.getMVELClassLoader()).getParent();
        System.out.println(PARENT + (parent == OptimizerFactory.class.getClassLoader() ? MVEL_OWN : parent));
    }
}
