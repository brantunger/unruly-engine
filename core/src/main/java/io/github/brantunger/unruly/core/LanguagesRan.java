package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether a run has finished with a language of each class one loaded rule list uses, which tells its runs whether
 * they need the room for a language's first run, which {@link StackHeadroom#checkFirstRun()} checks. The flags are
 * those of {@link RunClasses.Ran}, shared by every rule list that uses a language of the same class, read once here so
 * a run reads no class value. <b>Internal:</b> this class may change in any release.
 */
final class LanguagesRan {

    // The flags, in a list for needsRoom(), and by language name for completed().
    private final List<AtomicBoolean> flags;
    private final Map<String, AtomicBoolean> ranByLanguage;

    /**
     * Reads the flags of the languages a rule list uses.
     *
     * @param languages The languages the rules use, by name, whose first runs {@link #needsRoom()} tells of
     */
    LanguagesRan(Map<String, ExpressionLanguage> languages) {
        Map<String, AtomicBoolean> ran = new HashMap<>();
        for (Map.Entry<String, ExpressionLanguage> language : languages.entrySet()) {
            ran.put(language.getKey(), RunClasses.Ran.CLASSES.get(language.getValue().getClass()));
        }
        this.ranByLanguage = Map.copyOf(ran);
        this.flags = List.copyOf(ran.values());
    }

    /**
     * Tells whether a run of these rules needs the room for a language's first run, which
     * {@link StackHeadroom#checkFirstRun()} checks: whether a language they use is of a class that no run has finished
     * with yet in this JVM, as {@link #completed(List)} records it. A run that reads this while another records it may
     * still find it {@code true}, and only checks more room than it needs. A rule list that uses a language none of
     * whose actions ever completes in a run that returns (its rules never match, or its runs always throw) needs the
     * room on every run.
     *
     * @return {@code true} if a language these rules use hasn't finished a run
     */
    boolean needsRoom() {
        for (AtomicBoolean ran : flags) {
            if (!ran.get()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records that a run of these rules has finished for each of {@code languages}, so the runs of any rule list that
     * uses a language of the same class no longer need the room for its first run (see {@link #needsRoom()}). The
     * engine calls it when a run that needed that room has returned normally, with the languages of the rules whose
     * actions ran to completion. The room is for any first call the run makes, its conditions' too; an action that ran
     * to completion is the sign taken that the run made its language's first calls. A run that threw, whatever it
     * threw, records nothing, and a language none of whose actions the run ran isn't recorded. The run's first calls
     * are those of the rules it ran, not every shape its language's rules call.
     *
     * @param languages The names of the languages; a name these rules don't use is ignored
     */
    void completed(List<String> languages) {
        for (String language : languages) {
            AtomicBoolean ran = ranByLanguage.get(language);
            if (ran != null && !ran.get()) {
                ran.set(true);
            }
        }
    }
}
