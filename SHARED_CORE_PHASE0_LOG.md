# Shared Core Phase 0 Log

Date: 2026-03-10

## Baseline verification

### Ninja Concepts Manager (private)

- Repo path: `/home/fertroll10/Documents/ML/NinjaConceptsSDK`
- Command: `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest`
- Result: PASS

### CyanBridge (public)

- Repo path: `/home/fertroll10/Documents/ML/HeyCyanSmartGlassesSDK`
- Command: `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (run in `android/CyanBridge`)
- Result: PASS

## Inventory artifacts

- Shared overlap inventory: `SHARED_CORE_INVENTORY.md`
- Regeneration script: `tools/shared_core_inventory.py`
- Boundary policy + candidate labels: `SHARED_CORE_BOUNDARIES.md`

## Notes

- Direct namespace-mapped overlaps identified: 58 Java/Kotlin files.
- Overlap is concentrated in Wi-Fi/P2P and shared UI-support code paths.
- Ninja-only packages (largest): `ai`, `localagent`, `data`, `privacy`.
- CyanBridge-only files (at snapshot time):
  - `com/fersaiyan/cyanbridge/ui/LocalDataCleaner.kt`
  - `com/fersaiyan/cyanbridge/ui/PrivacySettings.kt`
