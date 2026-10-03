# 📦 Packaging a language

How to ship an expression language in its own jar, so an engine finds it on the class path, on the module path and in a
native image.

**Who it's for:** language authors.
**You'll be able to:** declare your language as a service both ways, write its `module-info.java`, tell applications
which packages to export, and make it work in a GraalVM native image.
**Before you start:** [Writing an expression language](custom.md).

[← Documentation index](../README.md)

---

## 📦 Declaring the language

A language in its own jar needs only `unruly-engine-core`, the engine without MVEL. An engine built without
`language(...)` finds a language when its jar declares it as a service, and the class needs a public no-argument
constructor. Declare it both ways:

- **Class path:** a file `META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` that contains
  the class name, such as `com.example.lang.MyLanguage`.
- **Module path:** a `provides` clause. The engine's module `uses` the service, and `ServiceLoader` runs on every
  `build()`, so a constructor that throws fails every engine built without `language(...)`.

```java
module com.example.lang {
    requires io.github.brantunger.unruly.core;   // transitive, if your public API exposes engine types

    provides io.github.brantunger.unruly.api.language.ExpressionLanguage
            with com.example.lang.MyLanguage;

    // exports com.example.lang;                 // only if applications call language(new MyLanguage())
}
```

## 🧩 Exports for facts and output

The engine, not your module, reads facts with `FactProperties` and writes the output with its default
`OutputWriter`, from the module `io.github.brantunger.unruly.core`. So an application whose rules use your language
exports or opens its fact and output packages to that module, and needn't export them to yours:

```java
module com.example.app {
    requires io.github.brantunger.unruly.core;
    exports com.example.app.model to io.github.brantunger.unruly.core;   // or opens, for classes that aren't public:
                                                                          // calling their methods is deep reflection
}
```

A language that reflects on facts itself needs its own access, as MVEL needs an export with no `to` clause; see the
root README's [Installation](../../README.md#-installation). A test module that `requires` the contract kit opens its
package `to org.junit.platform.commons`.

## 🧊 Native image

A language works in a GraalVM native image if it generates no classes while rules run: an image can't load a class
that wasn't in it when it was built. Register the reflection the language itself needs in its jar, in
`META-INF/native-image/<group>/<artifact>/reflect-config.json`, which `native-image` reads from the class path. The
`unruly-engine` jar does this for MVEL. `native-image` registers the provider in your `META-INF/services` file, so
`ServiceLoader` finds the language as on the JVM.

The application registers its own fact and output classes, because `FactProperties` and the default `OutputWriter`
read and write them by reflection. Only MVEL has been tested in an image; see [Native image](../native-image.md).
