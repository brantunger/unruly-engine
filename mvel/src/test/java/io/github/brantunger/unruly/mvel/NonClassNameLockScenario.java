package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run as its own JVM by {@link NonClassNameLockTest}, with {@code --add-opens java.base/java.lang=ALL-UNNAMED} to read
 * the lock objects the JDK's application and platform class loaders keep, one per name they were asked to load. The
 * thread's context class loader is the application class loader, as in a plain application. For each case it prints
 * how many names the case left a lock object for that no class has a class file for, and the first few.
 */
final class NonClassNameLockScenario {

    static final String MESSAGE = "MESSAGE ";

    private static final int RULES = 20;

    private static final String INLINE_IMPORT = "import java.util.*; ";

    private static final String OBJECT_NESTED = Object.class.getName() + "$";

    private NonClassNameLockScenario() {
    }

    private static Set<String> locked() throws ReflectiveOperationException {
        VarHandle locks = MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
                .findVarHandle(ClassLoader.class, "parallelLockMap", ConcurrentHashMap.class);
        Set<String> names = new TreeSet<>();
        for (ClassLoader loader : List.of(ClassLoader.getSystemClassLoader(), ClassLoader.getPlatformClassLoader())) {
            for (Object name : ((Map<?, ?>) locks.get(loader)).keySet()) {
                names.add(name.toString());
            }
        }
        return names;
    }

    // The names locked since, that the application class loader serves no class file for, which takes no lock. A
    // property read through a value MVEL types as Object, looked up as a class nested in Object, is counted apart:
    // after the rule list's class loader refuses it, MVEL asks the thread's context class loader for it itself.
    private static void print(String label, Set<String> before) throws ReflectiveOperationException {
        List<String> added = new ArrayList<>();
        int nestedInObject = 0;
        for (String name : locked()) {
            if (!before.contains(name)
                    && ClassLoader.getSystemClassLoader().getResource(name.replace('.', '/') + ".class") == null) {
                if (name.startsWith(OBJECT_NESTED)) {
                    nestedInObject++;
                } else {
                    added.add(name);
                }
            }
        }
        System.out.println(MESSAGE + label + " +" + added.size() + " " + added.subList(0, Math.min(3, added.size()))
                + ", nested in Object +" + nestedInObject);
    }

    private static RulesEngine<Map<String, Object>> engine() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
    }

    private static FactMap<Object> facts() {
        return new FactMap<>(new Fact<>("amountDue", 42));
    }

    private static List<Rule> rules(String condition, String action) {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < RULES; i++) {
            rules.add(Rule.builder().ruleName("r" + i).condition(condition.formatted(i))
                    .action(action.formatted(i)).build());
        }
        return rules;
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        // The imported package's own name, looked up once per package, isn't part of what is measured.
        try (RulesEngine<Map<String, Object>> engine = engine()) {
            engine.load(List.of(Rule.builder().ruleName("w").condition(INLINE_IMPORT + "true")
                    .action(INLINE_IMPORT + "output.put('w', 1)").build()));
            engine.run(facts());
        }
        // An inline package import: MVEL tries whole statements and calls as classes in it, when the rule first runs.
        Set<String> before = locked();
        try (RulesEngine<Map<String, Object>> engine = engine()) {
            engine.load(List.of(Rule.builder().ruleName("a").condition(INLINE_IMPORT + "amountDue > 0")
                    .action(INLINE_IMPORT + "output.put('k', new ArrayList(List.of(amountDue)))").build()));
            engine.run(facts());
        }
        print("inline import", before);
        // Rules that differ only in a literal, which is in the statement MVEL tries as a class.
        before = locked();
        try (RulesEngine<Map<String, Object>> engine = engine()) {
            engine.load(rules(INLINE_IMPORT + "amountDue > 0",
                    INLINE_IMPORT + "output.put('k%d', new ArrayList(List.of(amountDue)))"));
            engine.run(facts());
        }
        print("literals", before);
        // A property chain on a fact, with no import: MVEL tries the chain and each of its prefixes as a class, with
        // their dots turned into $, while it compiles.
        for (String shape : List.of("f.p%d == 1", "f.p%d.q == 1", "f.p%d.q.r.s == 1")) {
            before = locked();
            try (RulesEngine<Map<String, Object>> engine = engine()) {
                engine.load(rules(shape, "output.put('k', 1)"));
            }
            print(shape, before);
        }
    }
}
