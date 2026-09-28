# Artemis-observed Android UI pool

These are **actual Android 36 emulator UI observations**, collected with
Google's [Artemis](https://github.com/google/artemis) device driver and
UIAutomator2 at pinned source revision
`371aa6df56880643da57b30da936e9812fb0ec66`. They are **not Tasker/
AutoInput observations** and must not be presented as a validated replacement
for CyanBridge's phone-input contract. The agent/LLM portion of Artemis was not
used; deterministic navigation scripts selected an observed clickable node and
confirmed the next screen's title and changed UI before writing a supervised
row. No Gmail/email, personal accounts, paid apps or approved actions were
involved. Raw screenshots/XML are kept outside Git at
`/tmp/opencode/artemis-phone-captures/`.

Each JSONL row records a *pre-action* decision and a separate *post-action*
observation. Settings has **14 episodes / 42 decisions** across six sections:
28 grounded taps and 14 terminal DONE states, checked by destination page
titles. Clock has **5 episodes / 10 decisions**, with DONE checked by the
selected-tab flag. YouTube has **10 episodes / 30 decisions** that end after
entering an exact public query into the focused search field (10 taps, 10
literal entries, 10 DONE states); **no search submission, video selection, or
playback is verified**. The emulator's YouTube search submission produced a
RETRY screen and was excluded. Each `*.audit.json` lists exclusions from the
latest run. The bundled `pool.jsonl` and `manifest.json` were assembled by
`tools/benchmarks/assemble_artemis_phone_pool.py`: **29 episodes / 82
decisions**, 40 screen signatures, and just **three connected screen families**.
The collector rejects wrong AVDs, ambiguous labels, unobserved actions,
package changes and unverified destination titles. It uses the isolated
`CyanBridge_Artemis_Data` AVD, never the authenticated `Pixel_9a`.

To reproduce with the already provisioned isolated emulator and external
Artemis checkout:

```bash
/tmp/opencode/artemis/.venv/bin/python tools/benchmarks/collect_artemis_settings.py \
  --serial emulator-5560 --artemis-src /tmp/opencode/artemis
python3 tools/benchmarks/audit_artemis_phone_episodes.py \
  android/CyanBridge/training/artemis_real_v1/settings.jsonl \
  --report android/CyanBridge/training/artemis_real_v1/settings.audit_detail.json
python3 tools/benchmarks/assemble_artemis_phone_pool.py
```

The audit groups episodes sharing a package, page title and **candidate
layout**. Settings home, Clock tabs and YouTube Search each appear across
multiple goals; they form three connected screen families. Do not divide them
by row or by goal into a supposedly independent train/calibration/test split.
Collect new screen families and a separate Tasker/AutoInput corpus, including
missing-control negatives and verified outcomes, before training Laya or
Needle. The earlier six YouTube Tasker states and frozen generated benchmarks
remain outside training.

One separately verified Tasker Settings [diagnostic
episode](../tasker_real_v1/README.md) established candidate retention and
reversible navigation on the authenticated Pixel. Its observation provider and
Android version differ from these Artemis observations; the pools are never
merged into a claimed independent training set.
