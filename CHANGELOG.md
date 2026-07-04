# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Action Fact Access**: Rule actions now have full read access to all input facts via the pre-built variable map. This allows dynamic computations (e.g., `output.put("discount", amount * 0.10)`).
- **Custom Exceptions**: Introduced a formal exception hierarchy (`UnrulyException`, `RuleCompilationException`, `RuleExecutionException`) for safer and clearer error handling.
- **Static Analysis**: Integrated PMD and Checkstyle into the Gradle build pipeline.

### Changed
- **Performance Optimization**: Extracted fact unwrapping to run exactly once per execution engine run. This resolves an `O(N * M)` bottleneck and significantly reduces GC overhead.
- **Security Enhancements**: Prevented MVEL's internal compiler context (`ParserContext`) from leaking into the execution environment as the `this` reference.
- **Build**: Locked Jacoco minimum test coverage at 100%.

### Fixed
- Addressed multiple instances of potential NPEs when processing rules with null or blank conditions/actions.
- Hardened input validation for the `FactMap` and generic `Fact` implementations.
