# 🧊 Native image

How to build an application that runs MVEL rules into a GraalVM native image: the one setting it needs, the
reflection it must register, and what the errors mean when something is missing.

**Who it's for:** application developers building a native executable with GraalVM's `native-image`.
**You'll be able to:** build and run an image whose rules work, find the classes and methods to register, and read
the errors an image gives when one is missing.
**Before you start:** the [Quick start](../README.md#-quick-start), and GraalVM's own
[native image guide](https://www.graalvm.org/latest/reference-manual/native-image/).

[← Documentation index](README.md)

- [Quick start](#-quick-start)
- [MVEL's JIT must be off](#-mvels-jit-must-be-off)
- [Registering your classes](#-registering-your-classes)
- [Gotchas](#-gotchas)
- [Errors and what they mean](#-errors-and-what-they-mean)
- [Strict metadata](#-strict-metadata)
- [Flight Recorder events](#-flight-recorder-events)
- [Other expression languages](#-other-expression-languages)
- [What was tested](#-what-was-tested)

---

## 🚀 Quick start

The engine and MVEL work in a native image when two things are true:

1. MVEL runs with its JIT off: the executable is started with `-Dmvel2.disable.jit=true`.
2. The image registers, for reflection, every class and method your rules read, write or call.

The [native-smoke](../native-smoke/src/main/java/com/example/nativesmoke/Main.java) application in this repository
does both, and CI builds and runs it on every pull request. These are CI's commands, run from the repository's root;
`-H:+ReportExceptionStackTraces` only makes a failed build print its stack traces:

```bash
./gradlew :native-smoke:installDist
mkdir -p native-smoke/build/native
native-image --no-fallback -H:+ReportExceptionStackTraces -cp 'native-smoke/build/install/native-smoke/lib/*' \
    -o native-smoke/build/native/native-smoke com.example.nativesmoke.Main
native-smoke/build/native/native-smoke -Dmvel2.disable.jit=true
```

The engine logs every fact name it rejects at ERROR, so those lines come before the status line a correct image
ends with:

```text
native smoke OK: bean=prime map=standard,raised factName=rejected,prime hidden=prime,gold,standard deadline=timeout,virtual dateResource=false dateFactValue=rejected image=true jit=off
```

`bean` and `map` are what the rules produced, `image=true` says the binary is running as a native image, and `jit=off`
that MVEL's JIT is off. `factName=rejected` means the image rejected a fact named after a class in an imported package,
as the JVM does; MVEL checks only names its rules' text holds, so the smoke's rule names `Date` in a comment. `prime`
beside it is the control: an engine that imports the application's own class and no package ran its rule as it should.

`hidden=prime,gold,standard` says the default output writer set two properties, and `FactProperties` read one, on
classes that aren't public, through each class's own methods: the image has no metadata for the public types above
them.

`deadline=timeout,virtual` says a run whose action spins until an action it registered with
[`onCancel`](languages/custom.md#-stopping-a-run) stops it failed with its 200 ms timeout, and that the engine ran
that action on a virtual thread.

`dateResource` and `dateFactValue` are diagnostics, and no result is held to them. `dateResource=false` says the
image served no class file for `java.util.Date` as a resource; the check doesn't ask an image for one, which is why
the name is rejected all the same. `dateFactValue=rejected` says a rule reading `Date` never ran, because the check
rejected the fact first. The same code on the JVM prints `dateResource=true` and `image=false`, and every other
value the same; run it with
`java -Dmvel2.disable.jit=true -cp 'native-smoke/build/install/native-smoke/lib/*' com.example.nativesmoke.Main`.

Nothing else is needed to build and run an image: no `native-image` option beyond the usual ones, no metadata for
`unruly-engine-core`, and no change to how you build engines or load rules. `native-image` finds MVEL through its
`META-INF/services` file, as the JVM does. An image built to fail on a class it has no metadata for works too, given
the package list [Strict metadata](#-strict-metadata) describes.

## ⚡ MVEL's JIT must be off

MVEL's JIT optimizer generates classes while rules run. A native image can't load a class that wasn't in it when it
was built, so with the JIT on the first condition fails. With it off, MVEL uses its reflective accessors, which work
in an image as long as their targets are [registered](#-registering-your-classes).

> [!IMPORTANT]
> Start every native executable that runs MVEL rules with `-Dmvel2.disable.jit=true`. The engine doesn't set it for
> you: the property is MVEL's, for the whole process, and the engine never changes MVEL's global settings.

To make it part of the application instead of its command line, set the property first thing in `main`, before the
application builds an engine or uses MVEL in any other way:

```java
public static void main(String[] args) {
    System.setProperty("mvel2.disable.jit", "true"); // before anything touches MVEL
    // build engines and load rules after this
}
```

MVEL reads the property once, when its `OptimizerFactory` class initializes, and ignores later changes. Passing the
property on the command line is what CI tests; setting it in `main` isn't tested. Passing it to `native-image` as a
build argument isn't tested either, so don't rely on it. [Compiled copies](languages/mvel.md#-compiled-copies) covers
the JIT on the JVM.

## 🪞 Registering your classes

A native image keeps reflection only for what it was told about. MVEL reads facts and writes the output by
reflection, so the image must register those classes.

**What the `unruly-engine` jar ships.** `META-INF/native-image/io.github.brantunger/unruly-engine/reflect-config.json`
registers what MVEL itself needs with the JIT off: the no-argument constructor of
`org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer`, and `org.mvel2.asm.ClassWriter`, a class MVEL looks up
when it starts. Without that entry, an image built with [strict metadata](#-strict-metadata) logs a `WARNING` with a
stack trace there. It also registers `io.github.brantunger.unruly.mvel.warmup.WarmUpTarget`, the engine's own class that
preparing MVEL reads a property of, calls two methods on, one taking a `String`, and asks for a missing method.
`native-image` reads the file from the class path; you do nothing.

**What your application registers.** Everything your rules reach, in your own
`src/main/resources/META-INF/native-image/<group>/<artifact>/reflect-config.json`, which `native-image` also finds on
the class path. For the sample's rules:

| The rules do | Register | Sample entry |
| --- | --- | --- |
| Read a record fact's component: `applicant.creditScore` | The record's accessor methods | `Main$Applicant`: `creditScore()`, `income()` |
| Set a bean output's property: `output.rate = 'prime'` | The setter | `Main$LoanDecision`: `setRate(String)` |
| Call a method on a JDK output: `output.put('rate', 'standard')` | That method of the JDK class | `java.util.HashMap`: `put(Object, Object)` |
| Call a JDK static method: `Math.max(applicant.income, 0)` | That method | `java.lang.Math`: `max(int, int)` |

The sample also sets `queryAllPublicMethods` on each class its rules use, and `allPublicFields` on `Main$Applicant`
and `Main$LoanDecision`. Its `reflect-config.json` is
[the full file](../native-smoke/src/main/resources/META-INF/native-image/com.example/native-smoke/reflect-config.json).
Rules reading a bean's getters, a `Map` fact or a public field weren't tested.

### Finding what to register

GraalVM's tracing agent records the reflection an application uses while it runs on the JVM. Run it with GraalVM's
`java`, with the JIT off, and with facts that make every rule's condition and action run:

```bash
java -agentlib:native-image-agent=config-output-dir=native-smoke/build/agent-config \
    -Dmvel2.disable.jit=true -cp 'native-smoke/build/install/native-smoke/lib/*' com.example.nativesmoke.Main
```

The agent overwrites the files in its output directory, so point it at a scratch directory, not at your
`reflect-config.json`. Then compare what it wrote with your file, and copy what's new. For the sample, the agent
recorded `put` on `java.util.Map`, the interface, and `java.util.HashMap` only for looking its methods up, and an
image built from its output ran. A hand-written file that listed `HashMap` with no `put` anywhere failed with
`MissingReflectionRegistrationError` naming `HashMap.put`, and registering `put` on `HashMap` fixed it. When you
write an entry by hand, register the method on the class the error names.

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **The agent saw only the rules that ran** | A first-match engine stops at the first match, so the agent records nothing for the rules below it. The image fails when production facts reach them | Run the agent with facts that fire each rule, or register by hand |
| **Rules loaded after the build** | Rules reloaded from a database or a file can use a class or method the image doesn't register. They can load, then fail when they run | Register what new rules may use, and test each rule list in the image before loading it in production |
| **An entry that only allows lookups** | `queryAllPublicMethods` alone lets MVEL, or the engine on a supertype, find a method but not call it | List each called method under `methods` |

## 🚨 Errors and what they mean

A condition or action that fails in an image fails the run like any other: `run()` throws a
`RuleExecutionException`, `Failed to evaluate condition for rule '...'` or `Failed to execute action for rule '...'`,
with GraalVM's or MVEL's error as its cause. See [Error handling](error-handling.md#-handling-failures). The last row
is a `load()` that fails instead.

| The cause contains | Why | Fix |
| --- | --- | --- |
| `UnsupportedFeatureError: No classes have been predefined during the image build to load from bytecodes at runtime` | MVEL's JIT is on and tried to generate a class | Start the executable with `-Dmvel2.disable.jit=true`; see [MVEL's JIT must be off](#-mvels-jit-must-be-off) |
| `unable to instantiate accessor compiler`, caused by `NoSuchMethodException: org.mvel2.optimizers.dynamic.DynamicOptimizer.<init>()` | MVEL's JIT is on, and its optimizer isn't registered | The same: turn the JIT off. Registering the optimizer only leads to the error above |
| `MissingReflectionRegistrationError: The program tried to reflectively invoke method ...` | A method the rule calls isn't registered | Add it to your `reflect-config.json`; see [Registering your classes](#-registering-your-classes) |
| `RuleCompilationException` from `load()`: `The program tried to reflectively access class applicant.creditScore`, a name made from the rule's text | The image was built with strict metadata for all code, MVEL's included | List packages instead; see [Strict metadata](#-strict-metadata) |

## 🔒 Strict metadata

By default, an image answers a class lookup it has no metadata for as if the class weren't there: `Class.forName`
throws `ClassNotFoundException`. Built with strict metadata, it throws GraalVM's `MissingReflectionRegistrationError`
instead, so a missing entry fails where it's used. GraalVM says this will become the default. On GraalVM for JDK 21
the option is experimental. These are CI's commands for its second image:

```bash
packages=io.github.brantunger.unruly.api,io.github.brantunger.unruly.api.exception
packages=$packages,io.github.brantunger.unruly.api.language,io.github.brantunger.unruly.core
packages=$packages,io.github.brantunger.unruly.mvel,io.github.brantunger.unruly.mvel.warmup
native-image --no-fallback -H:+ReportExceptionStackTraces \
    -H:+UnlockExperimentalVMOptions "-H:ThrowMissingRegistrationErrors=$packages" \
    -cp 'native-smoke/build/install/native-smoke/lib/*' -o native-smoke/build/native/native-smoke-strict \
    com.example.nativesmoke.Main
native-smoke/build/native/native-smoke-strict -Dmvel2.disable.jit=true
```

> [!WARNING]
> With MVEL rules, always give the option a list. With none, `-H:ThrowMissingRegistrationErrors=` covers MVEL's own
> code too. MVEL looks up names it makes from a rule's text, such as `applicant.creditScore`, with `Class.forName`, and
> no metadata can list them, so every rule that reads a property fails to compile and `load()` throws.

How the list works on GraalVM for JDK 21, from GraalVM's source:

- Each entry is a package or a class, matched exactly, not as a prefix. `io.github.brantunger` covers none of the
  library's classes, so CI lists each of its six packages. The build's `checkStrictImagePackages` task, which
  `./gradlew build` runs, fails when that list misses a package of the `core` or `mvel` jars, or names another.
- The list picks whose `Class.forName` and other reflection calls throw. A lookup through a class loader's
  `loadClass` throws for any name the image has no metadata for, whatever the list.

CI lists only the library's packages. Adding your application's packages checks its own lookups too, which wasn't
tested. With CI's list, the sample needed no metadata beyond what its default image uses. GraalVM for JDK 23 and later
name the option `--exact-reachability-metadata`, which wasn't tested.

The engine reads `MissingReflectionRegistrationError` as "no such class" wherever it looks a class up by name, as the
JVM reads `ClassNotFoundException`. So a package import such as `"java.util"` works; on GraalVM for JDK 21, earlier
releases of the engine failed `build()` there. MVEL's lookups through the engine's class loader get a
`ClassNotFoundException`, so a nested class or a static member in a rule resolves as on the JVM. For a method looked
up on a supertype, or the methods of a bridge's class, the error means "not found".

The engine looks a method up on a supertype for a getter or setter of a non-public fact or output class, and to
find a bridge setter's target. MVEL rules never get there; another language's `ActionResult.set(...)` does, as do
`OutputWriter.beansAndMaps()` and `FactProperties` called directly. A supertype your metadata doesn't cover is
skipped, and the next public supertype that declares the method, or else the class's own, is called. So
[registering your class](#-registering-your-classes) is enough. A supertype registered for lookups only can't have its
method called; see [Gotchas](#-gotchas).

An image initializes no class by name. The engine knows the error by its class name,
`org.graalvm.nativeimage.MissingReflectionRegistrationError`, and handles any other error as it did before.

To list every lookup that would throw, without stopping the run, add `-H:MissingRegistrationReportingMode=Warn` to
the build. Built unscoped that way, the sample printed each site, and its run still passed.

## 📡 Flight Recorder events

`native-image` builds an image without Flight Recorder support unless you pass `--enable-monitoring=jfr`. In such an
image, the engine records none of its [Flight Recorder events](listeners-and-logging.md#-flight-recorder-events), and
runs work as usual. It checks once, and logs this at DEBUG on the `io.github.brantunger.unruly.engine` logger,
followed by the error that stopped the events loading:

```text
The engine records no Flight Recorder events here, because they can't be loaded: ...
```

Built with `--enable-monitoring=jfr`, the image can load the event classes, so the engine uses them as it does on the
JVM. Recording them in an image wasn't tested.

## 🧩 Other expression languages

Only MVEL ships with the engine. A language of your own works in an image if it generates no classes while rules run,
and registers the reflection it uses itself; see [Native image](languages/packaging.md#-native-image) for language
authors. Your application still registers its fact and output classes, as above. No other language has been tested in an
image.

## 🧪 What was tested

CI's `native-image` job builds the native-smoke application with GraalVM Community Edition for JDK 21 (21.0.2) on
Linux, and runs it. It then builds and runs it again with [strict metadata](#-strict-metadata) for the library's six
packages. The application checks its own results and exits with 1 if one is wrong. It covers:

- a first-match engine with a class and a package import, a record fact read by property and a bean output an
  action sets;
- an all-matches engine with a `HashMap` output and a call to `Math.max`;
- 200 runs of each engine, well past the about 50 runs after which MVEL's JIT would step in;
- loading rules, which computes their checksum with SHA-256, and finding MVEL with `ServiceLoader`;
- an image without Flight Recorder support.

With `OutputWriter.beansAndMaps()`, it sets two properties on an output class that isn't public: one declared by a
public interface, and one through a bridge setter over a public generic superclass. It reads a bean fact that isn't
public with `FactProperties.read`. Only the classes that aren't public are registered, not the public interface and
superclass, so the strict image checks that the engine skips those.

It also runs a fact named after a class in an imported package, the one path where the fact-name check resolves a class
name: an image rejects such a name where a rule's text holds it, as the JVM does.

The application checks that outcome, so a change either way turns CI red. A fact named after a single imported class is
matched against the simple names of the classes the engine already loaded, so it never reaches that lookup; that is from
reading the code, and no image has run it.

Not tested: Oracle GraalVM, GraalVM for other JDK versions, Windows and macOS images, the module path, frameworks'
own native support such as Spring Boot's, listeners, timeouts, the other builder options, a fact named after a
single imported class, and recording Flight Recorder events in an image. Nor, with strict metadata: GraalVM 23 and
later, any other package list, and a broken `META-INF/services` listing.
