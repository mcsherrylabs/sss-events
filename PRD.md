# Product Requirements Document - SSS Events Configuration & Type Safety

## Overview
This document outlines two critical refactoring tasks focused on proper configuration management and type safety for dispatcher names.

---

## Task 1: Implement Global Config Instance

### Objective
Refactor configuration loading to follow the `scala_config_load.md` rule: use a single global Config instance made available at initialization time.

### Current Problem
- `AppConfig.config` is a `lazy val` - created on first access, not at initialization time
- `AppConfig` object is in the same file as `EngineConfig` - not truly system-global
- Violates the principle of "system global, not in object methods specific to the implementation class"

### Requirements
- Create a truly system-global Config instance (not lazy, not in implementation files)
- Make the config instance available at initialization time
- Pass the config instance to components at startup (EventProcessingEngine, EngineConfig)
- The Config instance should be in a separate, top-level location (e.g., `AppConfig.scala` or similar)
- All components receive the config via constructor/parameter injection, not by accessing a lazy val

### Implementation Notes
- Move `AppConfig` to its own file: `src/main/scala/sss/events/AppConfig.scala`
- Change from `lazy val` to `val` to ensure initialization time creation
- Update `EventProcessingEngine.apply()` to explicitly use the global config
- Ensure `EngineConfig.load()` receives the config from the global instance

### Success Criteria
- [x] Config instance is in a separate top-level file
- [x] Config is not lazy - created at initialization time
- [x] All tests pass
- [x] No components create their own ConfigFactory instances

---

## Task 2: Make DispatcherName Truly Type-Safe

### Objective
Remove backwards compatibility compromises and make DispatcherName properly type-safe so only valid dispatcher names can be constructed.

### Current Problem
- `DispatcherName` has a private constructor but exposes `apply(name: String)` that accepts any string
- `Builder.withDispatcher(name: String)` allows arbitrary strings
- Type safety is illusory - any string can become a DispatcherName
- Backwards compatibility was assumed but is not required

### Requirements
Since backwards compatibility is NOT needed:
- Remove the public `apply(name: String)` factory method
- Remove `Builder.withDispatcher(name: String)` - force users to use `DispatcherName` instances
- Keep predefined constants: `DispatcherName.Default`, `DispatcherName.Subscriptions`
- Add a validated factory method that requires `EngineConfig` to create custom dispatcher names
- Or use sealed trait/enum approach with explicit variants

### Design
```scala
final case class DispatcherName private(value: String)

object DispatcherName {
  val Default: DispatcherName = new DispatcherName("")
  val Subscriptions: DispatcherName = new DispatcherName("subscriptions")

  // ONLY way to create custom dispatcher names - requires config validation
  def validated(name: String, config: EngineConfig): Option[DispatcherName] = {
    if (config.validDispatcherNames.contains(name)) {
      Some(new DispatcherName(name))
    } else {
      None
    }
  }

  // Remove: def apply(name: String): DispatcherName
  // Remove: def validated(name: String, validNames: Set[String]): Option[DispatcherName]
}
```

### Implementation Notes
- Remove `DispatcherName.apply(name: String)` method
- Remove old `validated(name: String, validNames: Set[String])` method
- Add new `validated(name: String, config: EngineConfig)` method
- Remove `Builder.withDispatcher(name: String)` method
- Keep `Builder.withDispatcher(name: DispatcherName)`
- Update all call sites that currently use `DispatcherName(string)` to use:
  - `DispatcherName.Default` for default dispatcher
  - `DispatcherName.Subscriptions` for subscriptions dispatcher
  - `DispatcherName.validated(name, config).get` for custom dispatchers (where config is available)
- Update tests that use string-based dispatcher assignment

### Success Criteria
- [ ] No public `apply(name: String)` method exists
- [ ] Cannot create `DispatcherName` from arbitrary strings
- [ ] All tests pass with updated dispatcher creation
- [ ] Custom dispatcher names require config validation

---

## Dependencies

- Task 2 depends on Task 1 (config instance must be available for validation)

---

## Testing Strategy

Each task requires:
- All existing tests pass
- No regressions in functionality
- Type system prevents invalid usage at compile time

---

## Notes

These tasks remove technical debt and establish proper patterns for:
1. Configuration management (global, eager initialization)
2. Type safety (compile-time prevention of invalid dispatcher names)
