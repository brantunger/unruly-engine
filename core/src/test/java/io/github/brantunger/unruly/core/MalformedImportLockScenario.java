package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run as its own JVM by {@link MalformedImportLockTest}, with {@code --add-opens java.base/java.lang=ALL-UNNAMED} to
 * read the lock objects the JDK's application and platform class loaders keep, one per name they were asked to load.
 * The thread's context class loader is the application class loader, as in a plain application. For each import in
 * {@link #NAMES}, it builds an engine with it and prints, after {@link #ROW} and the import's index, whether
 * {@code build()} was rejected and, for each form of the import {@code build()} would look up, the name itself and
 * each with a dot from the right replaced by {@code $} in turn, whether either loader kept a lock object for it before
 * the build and after it.
 */
final class MalformedImportLockScenario {

    static final String ROW = "ROW ";

    // The imports build() rejects as malformed, then one of a package's shape that no class has, which it accepts.
    static final List<String> NAMES = List.of("zq..b", ".zq", "zq.", " zq", "", "1zq", "zq-b", "zq/b", "zq\u00a7b",
            "java/util", "com.zqnope");

    private MalformedImportLockScenario() {
    }

    // The forms ImportResolver looks a name up by: the name, then each dot from the right but the first character
    // replaced by '$' in turn.
    static List<String> forms(String name) {
        List<String> forms = new ArrayList<>(List.of(name));
        String binaryName = name;
        for (int dot = name.lastIndexOf('.'); dot > 0; dot = binaryName.lastIndexOf('.')) {
            binaryName = binaryName.substring(0, dot) + '$' + binaryName.substring(dot + 1);
            forms.add(binaryName);
        }
        return forms;
    }

    private static List<Boolean> locked(VarHandle locks, List<String> forms) {
        List<Boolean> locked = new ArrayList<>();
        for (String form : forms) {
            for (ClassLoader loader : List.of(ClassLoader.getSystemClassLoader(),
                    ClassLoader.getPlatformClassLoader())) {
                locked.add(((Map<?, ?>) locks.get(loader)).containsKey(form));
            }
        }
        return locked;
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        VarHandle locks = MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
                .findVarHandle(ClassLoader.class, "parallelLockMap", ConcurrentHashMap.class);
        Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
        for (int i = 0; i < NAMES.size(); i++) {
            String name = NAMES.get(i);
            List<String> forms = forms(name);
            List<Boolean> before = locked(locks, forms);
            String built;
            try {
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                        .language(new ToyExpressionLanguage()).imports(name).build().close();
                built = "built";
            } catch (IllegalArgumentException e) {
                built = "rejected";
            }
            System.out.println(ROW + i + " " + built + " before " + before + " after " + locked(locks, forms));
        }
    }
}
