# Tasker-observed Settings navigation diagnostic

`settings_network.json` contains **one** observed, reversible Settings
navigation episode from the authenticated `Pixel_9a` AVD (Android 37), on
`emulator-5562`. It was captured by CyanBridge's real Tasker/AutoInput
observation and execution backend. The pre-action Tasker observation showed
the Settings **home** controls, including `Search Settings` and the bound
`Network & internet` target; `Internet` and `SIMs` were absent. After Tasker
executed the tap, its fresh observation showed `Internet` and `SIMs`, no home
search, and a changed snapshot hash. A separate ADB UIAutomator dump confirmed
the `Network & internet` page title plus `Internet`, `SIMs` and `Airplane mode`.
This external oracle is labeled `ADB_UIAUTOMATOR_DUMP`, never Tasker.

The export contains only allowlisted, public Settings labels, node indexes,
snapshot hashes and timing metadata. Raw Tasker text, credentials, account
details, coordinates and the independent XML dump remain outside Git. The
audit in `tools/benchmarks/audit_tasker_settings_episode.py` rejects an
already-open destination, unchanged/stale observations, wrong source or
device, missing bindings, and unexpected raw fields. An earlier trial did
reopen Settings on the destination and was **discarded** rather than counted
as a completed navigation. The pinned, verified episode took 1,821 ms from
pre-observation timestamp to post-observation timestamp (1,734 ms from action
start to post-verification). These are **one-task diagnostic timings**, not a
comparison with the incumbent planner.

The HIL requires `hil_artemis_parity=true` and runs on the authenticated AVD;
its deterministic root-screen setup uses Android's Settings launcher, while
the labeled tap and both observations go through Tasker/AutoInput. The AVD
must be checked by name **before and after** an instrumentation attempt. The
HIL runner enforces this for `CYANBRIDGE_HIL_ARTEMIS_PARITY=true`:

```bash
adb -s emulator-5562 emu avd name
CYANBRIDGE_HIL_ARTEMIS_PARITY=true bash tools/hil/run_instrumentation.sh \
  emulator-5562 hardware com.fersaiyan.cyanbridge.hil.TaskerSettingsEpisodeHilTest
adb -s emulator-5562 exec-out run-as com.fersaiyan.cyanbridge \
  cat files/tasker_settings_episode_v1.json > /tmp/opencode/tasker-settings-episode.json
python3 tools/benchmarks/export_tasker_settings_episode.py \
  --serial emulator-5562 --input /tmp/opencode/tasker-settings-episode.json
```

The exporter checks the actual AVD name and paid-app packages, validates the
Tasker row and independently inspects the current Settings page before writing
the allowlisted output. **Do not reuse a serial without checking its AVD
identity**: the emulator-recovery harness may start a different AVD on the
same port. This single Settings episode is separate from
[`artemis_real_v1`](../artemis_real_v1/README.md), has no missing-control
negatives and cannot establish Tasker policy accuracy or support a model
train/calibration/test split. Gmail remains read-only until composer
observation works; no Send action was involved here.
