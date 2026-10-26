# iOS build checks

The shared KMP module (`android/CyanBridge/shared`) builds the iOS framework that `ios/CyanBridgeKMPHost` embeds. The Android verify compiles only the Android and `portability` JVM targets, so a JVM-only API in `commonMain` (`javaClass`, `String.format`, `@Synchronized`, `java.*`) still passes there and only fails in the macOS CI (`.github/workflows/ios-kmp-host.yml`).

## iOS compile check (Linux, no Mac needed)

Kotlin 2.3 cross-compiles Apple klibs on Linux. From `android/CyanBridge`:

```
flock /tmp/cyan-refactor-gradle.lock ./gradlew -PenableAppleTargets=true :shared:iosCompileCheck
```

`iosCompileCheck` runs Room's KSP and compiles `iosSimulatorArm64`, `iosArm64` and `iosX64` main sources, plus the common tests for `iosSimulatorArm64`. The first run downloads the Kotlin/Native toolchain to `~/.konan`. After that it takes a few minutes. Run it next to the Android verify whenever `commonMain` or `iosMain` changes.

It does not link. Linking the framework (`linkDebugFrameworkIosSimulatorArm64`), the Xcode build and the Swift host still need macOS: CI, or a Mac with `./gradlew -PenableAppleTargets=true :shared:linkDebugFrameworkIosSimulatorArm64`.

## Common-code rules the check enforces

- Locks: use `kotlinx.atomicfu.locks.SynchronizedObject` with `synchronized(lock) { }` (a private `lock` per class). On Android it is the JVM monitor. On iOS it is a re-entrant native lock, so a locked method may call another locked method of the same class. `kotlin.jvm.Synchronized` is a compile error on Native.
- Formatting: no `String.format`. Use `shared/format/TextFormat.kt` (`formatFixed`, local dates) or `toString(16).padStart(...)` for hex.
- Class names: `this::class.simpleName`, not `javaClass`.
- `@Volatile` must come from `kotlin.concurrent`, not `kotlin.jvm`.
