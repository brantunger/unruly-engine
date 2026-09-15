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
