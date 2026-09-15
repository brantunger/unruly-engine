# ⬆️ Migrating from 1.x to 2.0

2.0 is a breaking release. This guide lists every change that can affect code written for 1.x. Each section says
what changed, who is affected, and what to change.

## 🤔 Should you upgrade?

- **2.0 requires Java 21.** On Java 17 to 20, stay on 1.x.
- **Upgrade to 1.8.0 first**, the last 1.x release, and fix its deprecation warnings. Each deprecated member names
  its replacement, so most of the work can be done while you're still on 1.x.

## ☕ Java 21 is required

**What changed:** the library is compiled for Java 21 (class file version 65). 1.x was compiled for Java 17.

**Who is affected:** projects built with, or running on, Java 17, 18, 19 or 20.

- A JDK 17 compiler can't read the engine's classes:
  ```
  error: cannot access RulesEngineBuilder
    bad class file: .../io/github/brantunger/unruly/api/RulesEngineBuilder.class
      class file has wrong version 65.0, should be 61.0
  ```
- An application built with a newer JDK but run on Java 17 fails when it first uses the engine:
  ```
  java.lang.UnsupportedClassVersionError: io/github/brantunger/unruly/api/RulesEngineBuilder has been compiled by a
  more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes
  class file versions up to 61.0
  ```

**What to change:** run your application on Java 21 or later, and compile for it.

- Gradle:
  ```groovy
  java {
      toolchain {
          languageVersion = JavaLanguageVersion.of(21)
      }
  }
  ```
- Maven:
  ```xml
  <properties>
      <maven.compiler.release>21</maven.compiler.release>
  </properties>
  ```

Spring Boot 3 and 4 both run on Java 21. The engine is tested on Java 21 and 25.

## 🔎 Expression languages are found with ServiceLoader

**What changed:** the engine no longer creates MVEL itself. Each time `setRuleList()` is called, it finds expression
languages with `java.util.ServiceLoader`, from
`META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage` files, and MVEL is one of them. A rule
without a `language` is still written in MVEL.

**Who is affected:**

- **Class paths with another language listed in such a file.** That language can now be used by rules without
  `registerLanguage()`. Two found languages with the same name fail `setRuleList()`.
- **Applications repackaged into one jar** (a shaded or "uber" jar) that keep only one of several `META-INF/services`
  files with the same name. If MVEL's entry is lost, a rule without a `language` fails:
  `Rule 'prime-rate' is written in 'mvel', which isn't a registered expression language. Registered languages: []`.
- **Class paths without `mvel2`.** Creating an engine now succeeds, and `setRuleList()` fails instead.

**What to change:** usually nothing. When you repackage the library, merge service files, for example with the Maven
Shade plugin's `ServicesResourceTransformer`, or register MVEL yourself with
`engine.registerLanguage(new MvelExpressionLanguage())`.
