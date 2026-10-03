package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Run as its own JVM by {@link ErrorUtilInitializationTest}: loads a deeply nested rule on a thread with a small stack,
 * then a rule with a syntax error on the main thread, and prints what each {@code load()} threw, by simple class
 * name, or {@code accepted}.
 */
final class DeepRuleScenario {

    static final String OUTCOMES = "OUTCOMES ";

    private static final int DEPTH = 5_000;
    /**
     * Small enough that the deep rule overflows, which it does on a stack of 1 MB too, and large enough for what the
     * thread does first: building the JVM's first engine and first using MVEL, each of which checks the stack has room
     * for initializing classes, about 160 KB (see StackHeadroom), beyond the pages the JVM keeps at the stack's end,
     * which are larger on some platforms, such as macOS on ARM. 256 KB left too little room there.
     */
    private static final long SMALL_STACK_BYTES = 512L * 1024;

    private DeepRuleScenario() {
    }

    public static void main(String[] args) throws InterruptedException {
        List<String> outcomes = new CopyOnWriteArrayList<>();
        String deep = "(".repeat(DEPTH) + "1" + ")".repeat(DEPTH) + " > 0";
        Thread small = new Thread(null, () -> outcomes.add(load("deep", deep)), "small-stack", SMALL_STACK_BYTES);
        small.start();
        small.join();
        outcomes.add(load("syntax", "x >="));
        System.out.println(OUTCOMES + String.join(",", outcomes));
    }

    private static String load(String name, String condition) {
        try {
            RulesEngineBuilder.firstMatch(Object::new).build()
                    .load(List.of(Rule.builder().ruleName(name).condition(condition).action("1").build()));
            return "accepted";
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }
}
