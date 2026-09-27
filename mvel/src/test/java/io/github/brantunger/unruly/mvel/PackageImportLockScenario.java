package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run as its own JVM by {@link PackageImportLockTest}, with {@code --add-opens java.base/java.lang=ALL-UNNAMED} to
 * read the lock objects the JDK's application and platform class loaders keep, one per name they were asked to load.
 * The thread's context class loader is the application class loader, as in a plain application. For a rule list
 * with {@code java.util} imported by the engine, loaded and run, then one that imports it in its own text, loaded,
 * it prints the names each loader keeps a lock object for that hold the rule's fact name.
 */
final class PackageImportLockScenario {

    static final String MESSAGE = "MESSAGE ";

    private PackageImportLockScenario() {
    }

    private static TreeSet<String> locked(String part) throws ReflectiveOperationException {
        VarHandle locks = MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
                .findVarHandle(ClassLoader.class, "parallelLockMap", ConcurrentHashMap.class);
        TreeSet<String> names = new TreeSet<>();
        for (ClassLoader loader : List.of(ClassLoader.getSystemClassLoader(), ClassLoader.getPlatformClassLoader())) {
            for (Object name : ((Map<?, ?>) locks.get(loader)).keySet()) {
                if (name.toString().contains(part)) {
                    names.add(loader.getName() + " " + name);
                }
            }
        }
        return names;
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).imports("java.util").build()) {
            engine.load(List.of(rule("engineFact > 1", "output.put('k', new ArrayList(List.of(engineFact)))")));
            System.out.println(MESSAGE + "engine " + engine.run(new FactMap<>(new Fact<>("engineFact", 5))) + " "
                    + locked("engineFact"));
        }
        // Only what compiling the rule locks: when it runs, MVEL looks up what it reads through an inline package
        // import again, straight from the class loader, without asking the configuration.
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).build()) {
            engine.load(List.of(rule("true",
                    "import java.util.*; output.put('k', new ArrayList(List.of(inlineFact)))")));
            System.out.println(MESSAGE + "inline " + locked("inlineFact"));
        }
    }
}
