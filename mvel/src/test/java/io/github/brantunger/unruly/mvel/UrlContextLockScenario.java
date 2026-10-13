package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Run as its own JVM by {@link UrlContextLockTest}, with {@code --add-opens java.base/java.lang=ALL-UNNAMED} to read
 * the lock objects the JDK's application and platform class loaders keep, one per name they were asked to load. Rule
 * lists whose fact names are new in each list are loaded with a plain {@code URLClassLoader}, a subclass of one, or
 * the application class loader as the thread's context class loader, each URL class loader made for one load and
 * dropped after, as a plugin's or a tenant's would be. For each case it prints how many names the loads left a lock
 * object for in either loader, how many of those the application class loader serves no class file for, how many of
 * those are in a {@code java.} package, the first few, and MVEL's default optimizer.
 */
final class UrlContextLockScenario {

    static final String MESSAGE = "MESSAGE ";

    private static final int LISTS = 3;

    private static final int RULES = 50;

    private static final String JAVA = "java.";

    // A fact name no list has used yet, so each list adds what its names cost.
    private static int next;

    private UrlContextLockScenario() {
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

    // With the package import, MVEL tries each name as a class in java.util too.
    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < RULES; i++) {
            int n = next++;
            rules.add(Rule.builder().ruleName("r" + n).condition("f%1$d.p%1$d.q == 1 || v%1$d > 0".formatted(n))
                    .action("output.put('k', w%1$d)".formatted(n)).build());
        }
        return rules;
    }

    private static void load(ClassLoader context) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(context);
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new).imports("java.util").build()) {
            engine.load(rules());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void loadWith(Supplier<ClassLoader> contexts) throws IOException {
        ClassLoader context = contexts.get();
        load(context);
        if (context instanceof URLClassLoader urls) {
            urls.close();
        }
    }

    // One list first, which loads the classes the case's first load would, so only the names that aren't classes are
    // counted.
    private static void measure(String label, Supplier<ClassLoader> contexts)
            throws IOException, ReflectiveOperationException {
        loadWith(contexts);
        Set<String> before = locked();
        for (int list = 0; list < LISTS; list++) {
            loadWith(contexts);
        }
        int all = 0;
        List<String> noClassFile = new ArrayList<>();
        int java = 0;
        for (String name : locked()) {
            if (before.contains(name)) {
                continue;
            }
            all++;
            if (ClassLoader.getSystemClassLoader().getResource(name.replace('.', '/') + ".class") == null) {
                noClassFile.add(name);
                if (name.startsWith(JAVA)) {
                    java++;
                }
            }
        }
        System.out.println(MESSAGE + label + " locked +" + all + ", no class file +" + noClassFile.size() + ", java. +"
                + java + " " + noClassFile.subList(0, Math.min(3, noClassFile.size())) + ", optimizer "
                + OptimizerFactory.getDefaultAccessorCompiler().getClass().getSimpleName());
    }

    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        System.out.println("java.version " + System.getProperty("java.version") + ", " + LISTS + " lists of " + RULES
                + " rules each, mvel2.disable.jit=" + System.getProperty("mvel2.disable.jit"));
        measure("app", ClassLoader::getSystemClassLoader);
        measure("url", () -> new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader()));
        measure("subclass", () -> new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader()) {
        });
    }
}
