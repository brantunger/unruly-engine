# 🧶 Virtual threads

> [!NOTE]
> Describes 2.0.0, which isn't released yet. This page has no 1.8.0 version.

What changes when runs come from virtual threads: the copy limit that applies only there, the build slots that pace
new copies when there's no limit, what each costs, and what the JDK version changes.

**Who it's for:** application developers running rules on virtual threads.
**You'll be able to:** choose between the default limit, `maxCopies(n)` and `unlimitedCopies()`, tell why a run
waited for a build slot, and know which JDK versions deadlock or pin a carrier.
**Before you start:** [Compiled copies](compiled-copies.md), which says what a copy is and how a limit works.

[← Documentation index](README.md)

- [What changes on virtual threads](#-what-changes-on-virtual-threads)
- [Waiting for a build slot](#-waiting-for-a-build-slot)
- [What it costs, measured](#-what-it-costs-measured)
- [Class loading pins carriers](#-class-loading-pins-carriers)
- [Gotchas](#-gotchas)
- [Questions you might not think to ask](#-questions-you-might-not-think-to-ask)

---

## 🧭 What changes on virtual threads

> [!CAUTION]
> On JDK 21 to 23, a virtual thread waiting on a monitor keeps the platform thread carrying it. A language whose
> expressions contend on a lock shared by the whole JVM can then hold every carrier and deadlock. MVEL is one: read
> [MVEL on virtual threads](languages/mvel.md#-virtual-threads) before you raise the limit.

An engine's [copy limit](glossary.md#copy-limit) applies by default only to runs on virtual threads: one
[compiled copy](glossary.md#compiled-copy) for every two processors, at least one.
[Limiting the copies](compiled-copies.md#-limiting-the-copies) owns that rule.

With more than one processor, the default is **below the number of processors**, which is how many platform threads
carry virtual threads unless the scheduler is configured otherwise. That makes a deadlock less likely without ruling
it out: the limit is per engine, so several engines' limits add up, a run that gives up waiting takes an
[extra copy](glossary.md#extra-copy),
and `-Djdk.virtualThreadScheduler.parallelism` can leave fewer [carriers](glossary.md#carrier-thread) than the limit.
Nothing changes for a Tomcat, Jetty or executor pool: the default doesn't apply to runs on platform threads.

The default is **sized for rules that compute**. A rule that waits — on a database, a service, a file — holds its copy
while it waits, so a limit of `N` caps how many such runs make progress at once, however many virtual threads you
start. Build those engines with a `maxCopies(n)` about as large as the number of runs you want waiting at once. Each
copy costs memory and a compile of every rule, so size it to the waiting you expect rather than to the threads. With
MVEL on JDK 21 to 23, keep `n` below the number of carriers: see the caution above.

**`unlimitedCopies()` on virtual threads** makes copies as the runs need them, and paces the new ones with
[build slots](#-waiting-for-a-build-slot). That doesn't bound the copies: they follow how many runs are in progress at
once, which grows with how long the rules wait. With rules that wait it ran faster than `maxCopies(256)` in
[What it costs, measured](#-what-it-costs-measured), but made 311–320 copies on JDK 26 and about 2,100 on JDK 21.

So use `maxCopies(n)` when memory needs a hard bound, and `unlimitedCopies()` only if memory can take a copy for each
run in progress at your busiest moment. With MVEL on JDK 21 to 23, see the caution above first.

## 📑 Waiting for a build slot

An engine built with `unlimitedCopies()` has no limit, so its runs never wait for a copy. On virtual threads it paces
how fast new copies are made instead, because a new copy's first run is the slow one: in MVEL, a copy compiles each
expression the first time it runs it. Without that pacing, a virtual thread that found every copy in use made its
own, however many there were; see [What it costs, measured](#-what-it-costs-measured).

The engine has one **build slot** for each processor, counted once by `build()`. Each engine has its own, and every
rule list it loads shares them, across reloads too. A run on a virtual thread, not
[nested](glossary.md#nested-run) in another run on it:

1. takes an idle copy at once, if there is one, including one [made at load](compiled-copies.md#making-copies-at-load);
2. otherwise waits for a slot, then looks for an idle copy again, and gives the slot back if it finds one;
3. otherwise makes a new copy, which is kept, and holds the slot until that copy's first run ends, including any time
   its rules spend waiting.

A run whose rules' languages all return `Session.none()` gives the slot back at once, and every run shares one set of
*[sessions](glossary.md#session)*, as under a limit.

So the slots **pace** how many new copies are in their first run at once; they don't bound how many copies exist. A
run that gives up waiting (below) makes and runs its new copy without a slot, so a short run timeout weakens the
pacing.

The copies still grow to the most runs in progress at once, only more slowly;
[What it costs, measured](#-what-it-costs-measured) has the numbers. Runs on platform threads, nested runs, and every
run on an engine with the default limit or `maxCopies(n)` never wait for a slot.

| When | What happens |
| --- | --- |
| The thread is already interrupted | It doesn't wait: it makes its copy, and the run stops at its first rule |
| The run has a deadline | It waits at most half the time it had left when it started waiting, then makes its copy without a slot, so its rules keep the other half |
| Five seconds pass without one slot coming back | It makes its copy without a slot: runs waiting for other runs of this engine look like that, as in [Runs that don't wait](compiled-copies.md#runs-that-dont-wait) |
| The thread is interrupted while it waits | `run()` throws a `RuleExecutionException` caused by the `InterruptedException`, and the interrupt status stays set |

The message is `run() was interrupted while waiting to make a compiled copy of the rules: every build slot
was in use`, and listeners get `beforeRun` and then `onRunError`, as for any stopped wait. A passed deadline never
fails this wait, so an engine without a limit never fails a run for want of a copy. Giving up on a slot isn't logged.

Without a deadline, a run waits for as long as slots keep coming back. Unlike places under a limit, slots are handed
out first come, first served, so a run that waits isn't overtaken for ever by runs that arrive after it.

A waiting run isn't woken by any copy given back by a run that holds no slot, such as a run on a copy that had
already run, a run on a platform thread, a nested run, or a run that gave up waiting for a slot. It waits until a
slot comes back, or it gives up as the table above says, and runs that arrive meanwhile take those idle copies first.

## 📋 What it costs, measured

Measured with 21 MVEL rules on 32 processors under Windows, with build slots, three times each. With rules that
wait, one condition waits 1 ms and 10,000 virtual threads share 200,000 runs; with rules that compute, 100,000 virtual
threads share 400,000 runs. Each cell is the copies made, then the runs a second:

| Rules, JDK | `unlimitedCopies()` | `maxCopies(256)` | Default limit, 16 there |
| --- | --- | --- | --- |
| Wait, JDK 26 | 311–320, about 99,000 | 256, about 87,700 | 16, about 7,700 |
| Wait, JDK 21 | 2,110–2,123, about 41,000 | 256, about 15,400 | 16, about 1,050 |
| Compute, JDK 26 | 98–102, about 153,000 | Not measured | 16, about 200,000 |
| Compute, JDK 21 | 33, 108,000–142,000 | Not measured | 16, about 85,000 |

Before build slots, `unlimitedCopies()` made a copy for each virtual thread whenever runs blocked: 10,000 copies and
about 10,500 runs a second for rules that wait, in one measurement on each JDK, and 100,000 copies and about 2,900
runs a second for rules that compute on JDK 26, in two.

**On JDK 21, build slots cost rules that compute some throughput.** There, a virtual thread waiting on a monitor
keeps its carrier, as the caution under [What changes on virtual threads](#-what-changes-on-virtual-threads) says,
and the copies never piled up: 33 to 37 before build slots, 33 with them. Runs still wait for slots, though. Over
20,000,000 runs, `unlimitedCopies()` ran 1,150,000–1,380,000 runs a second, against about 1,395,000 in three
measurements before, up to about 17% fewer. Over 400,000 runs it ran 108,000–142,000, against about 216,000 in the
one measurement before.

## 📌 Class loading pins carriers

**Known issue on JDK 24 and later.** A virtual thread still keeps its carrier while the JVM loads a class. MVEL loads
classes while it compiles, and generates accessor classes during a copy's first runs, so a run that makes a new
compiled copy can pin its carrier for as long as that takes. Since
[#387](https://github.com/brantunger/unruly-engine/pull/387), MVEL's class lookups no longer wait on each other, which
shortens the waiting without removing the pinning.

[`copiesAtLoad(n)`](compiled-copies.md#making-copies-at-load) moves that compiling, and the classes it loads, into
`load()` for the copies it makes. It doesn't move the accessor classes, which MVEL still generates during each copy's
first runs, or the compiling of a copy a run makes itself.

Measured on JDK 26.0.1 with 32 cores, 100,000 virtual threads sharing 2,000,000 runs of 21 MVEL rules, and
`copiesAtLoad(16)`, the default limit there, against copies made by runs: pinned events fell by about a third, from
1,214–1,316 to 775–842 over three runs, a one-time cost either way. Throughput didn't change, at about
690,000–745,000 runs a second. On JDK 21 there were no pins either way.

Without a limit, that pinning is what made the copies pile up before build slots: the run loading a class keeps its
carrier, but on JDK 24 and later the runs waiting for that class give theirs up, so more virtual threads start and
find no idle copy. Now those runs wait for a slot. Over 20,000,000 runs of rules that compute on JDK 26,
`unlimitedCopies()` kept about 100 copies; two of three measurements ran 640,000–690,000 runs a second, one 304,000.

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **`unlimitedCopies()` doesn't bound copies on virtual threads** | Build slots only pace new copies; copies grow with the runs in progress, about 2,100 for 10,000 waiting threads on JDK 21 | `maxCopies(n)` when memory needs a hard bound; see [What changes on virtual threads](#-what-changes-on-virtual-threads) |
| **`unlimitedCopies()` runs can wait on virtual threads** | With no idle copy, a run waits for a [build slot](#-waiting-for-a-build-slot): at most half its time left, or while slots keep coming back | A timeout caps the wait; `copiesAtLoad(n)` gives the first runs idle copies, taken without a slot |

## ❓ Questions you might not think to ask

### Can a run wait on an engine built with `unlimitedCopies()`?

Yes, on a virtual thread: a run that finds no idle copy waits for one of the engine's build slots, one for each
processor, before it makes a copy. The slots pace new copies without bounding them: copies still grow with the runs in
progress. See [Waiting for a build slot](#-waiting-for-a-build-slot).

### Does the default limit apply to runs from my Tomcat or Jetty pool?

No. It applies only to runs on virtual threads; a platform thread pool bounds the copies by its own size.
`maxCopies(n)` limits every kind of thread. See [What changes on virtual threads](#-what-changes-on-virtual-threads).

### Do two engines' limits add up?

Yes. Each engine counts its own copies and has its own build slots, so two engines with the default limit allow twice
as many copies as one. See [What changes on virtual threads](#-what-changes-on-virtual-threads).
