package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ClassFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1244: the rule sets an engine still has to retire are linked through two fields of {@link CopyPool},
 * {@code nextUnretired} and {@code retiringClaim}, which its {@link RuleSetLifecycle}'s lock guards, and only
 * {@link RetireQueue} reads or writes them, so what keeps the list whole (each rule set in it once, claimed by one call
 * at a time, and given up however retiring ends) is in one class. This reads the class files of every class in the core
 * package, and those of their nested classes, from the directory or jar they were loaded from, and fails if one other
 * than {@code RetireQueue} has a field reference to either, as its constant pool does for a field its code reads or
 * writes; {@code CopyPool} declares them, which needs none. It fails too if {@code RetireQueue} has none to either, or
 * if {@code RuleSetLifecycle} or {@code CopyPool} isn't read, so it can't pass by reading nothing.
 */
@DisplayName("only RetireQueue touches the links of the rule sets still to retire (#1244)")
class RetireLinksTest {

    private static final String OWNER = "io/github/brantunger/unruly/core/CopyPool.";
    private static final List<String> LINKS = List.of(OWNER + "nextUnretired", OWNER + "retiringClaim");

    @Test
    @DisplayName("no class of the core package but RetireQueue reads or writes CopyPool's nextUnretired or "
            + "retiringClaim")
    void onlyTheQueueTouchesTheLinks() throws IOException, URISyntaxException {
        Map<String, byte[]> classFiles = ClassFiles.ofPackage(RetireQueue.class);
        String queue = entry(RetireQueue.class);
        List<String> touching = new ArrayList<>();
        List<String> queueTouches = new ArrayList<>();
        for (Map.Entry<String, byte[]> classFile : classFiles.entrySet()) {
            for (String field : ClassFiles.fieldRefs(classFile.getValue())) {
                if (!LINKS.contains(field)) {
                    continue;
                }
                if (classFile.getKey().equals(queue)) {
                    queueTouches.add(field);
                } else {
                    touching.add(classFile.getKey() + " " + field);
                }
            }
        }

        assertTrue(classFiles.containsKey(entry(RuleSetLifecycle.class)), "RuleSetLifecycle not read: "
                + classFiles.keySet());
        assertTrue(classFiles.containsKey(entry(CopyPool.class)), "CopyPool not read: " + classFiles.keySet());
        assertEquals(List.of(), touching, "classes other than RetireQueue that use a link");
        assertTrue(queueTouches.containsAll(LINKS), "the links RetireQueue uses: " + queueTouches);
    }

    private static String entry(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }
}
