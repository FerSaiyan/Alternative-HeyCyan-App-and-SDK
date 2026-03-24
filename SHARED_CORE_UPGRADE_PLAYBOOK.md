# Shared Core Upgrade Playbook

This playbook describes how to bump app repos to a newer `heycyan-android-core` artifact.

## Preconditions

- New shared-core release/tag is available.
- Release notes identify breaking vs non-breaking changes.

## Upgrade steps (per app repo)

1. Update `heycyanCoreConnectivityVersion` in `gradle.properties`.
2. Run with artifact mode (composite disabled):

   ```bash
   JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest
   JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false assembleDebug
   ```

3. Run standard local mode validation:

   ```bash
   JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
   ```

4. Smoke check connectivity flows (Wi-Fi/P2P path).
5. Commit only the version bump + any required adapter changes.

## Rollback

If regression appears after upgrade:

1. Revert `heycyanCoreConnectivityVersion` to the last known-good value.
2. Re-run test/build commands above.
3. Open an issue in shared-core with reproduction details.
