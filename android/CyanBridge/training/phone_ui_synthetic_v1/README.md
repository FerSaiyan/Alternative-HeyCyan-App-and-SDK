# Phone UI decision pilot data (synthetic, English)

This folder is **generated, not Tasker-collected data**. It tests whether Laya
and Needle can learn a short current-step/observed-tools contract. It does not
support claims about phone automation safety, Gmail sending or real-device task
completion. Regenerate with `python3 tools/benchmarks/make_phone_ui_training.py`.

## Provenance and isolation

- Train: 576 decision rows / 192 scenario episodes / 24 invented names;
  validation and test: 128 rows / 64 episodes / eight other invented names
  each. The eight stages are launch, open search, select exact suggestion,
  submit already-filled query, select channel, select channel video, no matching
  control, and ambiguous duplicate result. Every screen offers three observed
  controls; Laya also sees a shuffled defer option. Needle is expected to
  return `[]` on no-match/ambiguous cases.
- Names and episodes are disjoint by split; shuffles and paraphrases stay in
  their episode. Tool IDs for competing candidates change with shuffle to
  prevent a fixed ID from revealing the answer. See `manifest.json` for row
  counts and SHA-256 checksums. The distinct-name test split still shares
  **templates**, so it cannot measure novel app/layout generalization. Results
  have now been inspected: further dataset/prompt/epoch revisions need **new
  independent episodes** for an unexamined final test.
- The frozen `android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl`
  was **not** used for training. The six actual YouTube Tasker states in
  `/tmp/opencode/jev-laya-needle-youtube-shadow-trace.log` are also reserved
  for shadow evaluation and never enter this generator. No Gmail personal
  content is used.

## Formats and local reproduction

- `*.laya.jsonl`: [Laya eval format](https://nandhakishorm.github.io/laya/evals/):
  `state`, `questions`, `expected` and case/episode metadata. The head trainer
  reads these rows, freezes the encoder and supervises the choice head with
  cross-entropy; this is deliberately a smaller pilot than upstream full-model
  [RLCD fine-tuning](https://nandhakishorm.github.io/laya/finetune_browser_agent/).
- `*.needle.jsonl`: [Needle 3 local fine-tune format](https://cactuscompute.com/blog/finetuning-needle):
  `query`, `system`, `tools`, `answers`, `reasoning` and metadata. All six files
  fit the pinned Needle 3 tokenizer's 256-token training cap in the initial
  audit (longest rendered example: 195 tokens). Recheck on engine upgrades.

Keep downloads and checkpoints under `/tmp/opencode/` and dependencies in a
non-base environment. The example commands use already-provisioned local
checkpoints; no device actions occur:

```bash
python3 tools/benchmarks/test_make_phone_ui_training.py
/tmp/opencode/laya-export-venv/bin/python tools/benchmarks/train_laya_phone_ui.py \
  --source /tmp/opencode/laya-current \
  --base /tmp/opencode/laya-multilingual \
  --data android/CyanBridge/training/phone_ui_synthetic_v1 \
  --out /tmp/opencode/phone-ui-models/laya-head-v1 --epochs 4

# Run the official CLI in a dedicated environment with Needle training extras:
PYTHONPATH=/tmp/opencode/needle-source /tmp/opencode/needle-train-venv/bin/python \
  -c 'from needle.cli import main; main()' finetune \
  android/CyanBridge/training/phone_ui_synthetic_v1/train.needle.jsonl \
  --checkpoint /tmp/opencode/needle3/needle3.safetensors \
  --max-len 256 --val-split 0 --epochs 10 \
  --out /tmp/opencode/phone-ui-models/needle-phone-ui-lora.safetensors

PYTHONPATH=/tmp/opencode/needle-source /tmp/opencode/needle-train-venv/bin/python \
  -c 'from needle.cli import main; main()' build \
  /tmp/opencode/needle3/needle3.safetensors \
  --lora /tmp/opencode/phone-ui-models/needle-phone-ui-lora.safetensors \
  --layers 20 --out /tmp/opencode/phone-ui-models/needle-phone-ui-20l.cact

python3 tools/benchmarks/eval_phone_ui_needle.py \
  --library /tmp/opencode/needle3/wheel/needle/libneedle3.so \
  --model /tmp/opencode/phone-ui-models/needle-phone-ui-20l.cact \
  --data android/CyanBridge/training/phone_ui_synthetic_v1 \
  --split test --report /tmp/opencode/phone-ui-needle-tuned-test.json
```

`--val-split 0` avoids the CLI's default random **row** holdout, which would
mix permutations of a scenario episode across train/validation. Score the
dedicated validation and test files as separate processes. The local Needle
LoRA build does not include a calibrated confidence head (`confidence: None`).
Compare 20-layer tuned weights to the released **20-layer** base first;
smaller deployment depths need a separate same-depth baseline.
Neither the synthetic test score nor a high confidence on a single real trace
permits model-selected actions; the existing Gemma/Pro planner and approval
path remain authoritative.

## Data-readiness audit after the pilot

This is **not a successful phone fine-tune**. A search of the available local
Tasker logs found only six structured `JEV_SHADOW_STATE` decision frames, all
from one YouTube episode. Older Chrome HIL logs contain repeated candidate
lists, but those runs failed and do not supply independently verified action
labels or a matching episode/post-action observation contract. Duplicating
those frames, relabeling a failed candidate as a correct action, or tuning
against the now-inspected real/frozen test sets would overfit rather than
improve real-phone reliability.

Before another fine-tune, collect multiple distinct, consented Tasker episodes
for YouTube and a separate non-email app, with several plausible visible
controls and a verified after-action screen. Include explicit missing-control,
mid-task and independently verified DONE states. Split whole episodes and
task goals **before** training, keep the final test unopened, then measure
candidate recall and verified end-to-end task success as well as decision
accuracy. See [`LAYA_NEEDLE_PHONE_TRAINING.md`](../../docs/LAYA_NEEDLE_PHONE_TRAINING.md)
for the per-step labeling contract. No additional checkpoint is justified by
the current data.

## Initial measurements (host only)

- Laya multilingual base: **72/128 (56.3%)** synthetic test decisions; the
  frozen-encoder trained head: **111/128 (86.7%)**. Selected the best of four
  epochs using the separate synthetic validation group. Even there it deferred
  on just **12/16** missing-control and **15/16** ambiguous-result screens.
  Training report and weights: `/tmp/opencode/phone-ui-models/laya-head-v1/`.
  Source revision
  `4066d5d5fbf08b66c6757ddeedbd797bd7655bc0`; trained
  `model.safetensors` SHA-256
  `7fd86016c188b971e098fcab7adf08f6421b6a3fc7207c4f04a90b95f186d15a`.
- The six **untouched Tasker-observed YouTube decisions** go the other way:
  base Laya **5/6**, trained head **4/6**, with both incorrectly proposing a
  player tap instead of deferring at the already-playing screen. Neither result
  is statistically representative; the real snapshots expose seven choices
  (including Scroll and Back) with noisy Tasker labels, versus four clean
  choices in training. The trained model wrongly preferred Scroll at the
  channel screen; the observed screen did contain the requested video. Replay:
  `tools/benchmarks/eval_phone_ui_real_laya.py` with the external trace. Reports:
  `/tmp/opencode/phone-ui-laya-base-real-shadow.json` and
  `/tmp/opencode/phone-ui-laya-real-shadow-v1.json`.
- As a presentation-order stress test, reversing the seven original options
  without changing their labels or meanings produced **3/6 for both** Laya
  base and trained head. This is the same six screens, not new observations;
  reports: `/tmp/opencode/phone-ui-laya-base-real-shadow-reversed.json` and
  `/tmp/opencode/phone-ui-laya-head-real-shadow-reversed.json`.
- On the **untouched 13-locale frozen generated corpus**, the trained head
  scored **45.0% raw top-1** (18/40 held-out model cases), versus the earlier
  multilingual base's **42.5%** (17/40). Neither reached the corpus's 98%
  precision gate with any automatic-action coverage (**0 actions**). This
  corpus uses a different full-goal input contract and translated templates;
  the one-point difference is not meaningful evidence of phone improvement.
  Report: `/tmp/opencode/phone-ui-laya-frozen-corpus.json`.
- Needle 3 base: **59/128 (46.1%)** synthetic exact calls, including **0/32**
  on no-match/ambiguous cases; serial host p50 ~394 ms. Report:
  `/tmp/opencode/phone-ui-needle-base-serial-test.json`. This is not a
  device benchmark. Pinned trainer revision
  `94df9999d58a67ff29f032a41f31307c05554bd6`.
- Needle 3 full-depth LoRA (rank 16, 10 epochs, validation split disabled in
  trainer): **80/128 (62.5%)** separate synthetic validation, **83/128
  (64.8%)** held-out synthetic exact calls. It still returned **0/16** correct
  no-calls when the requested control was missing (11/16 on ambiguous
  duplicates); all 11 no-calls on the test split occurred in the ambiguous
  group, with 117 single-call outputs. Training adapter:
  `/tmp/opencode/phone-ui-models/needle-phone-ui-lora.safetensors` (SHA-256
  `b6fe8027cf40d0c9ab17a5a26bdb8757d4cd3b4773c3213114c686b46d7350c8`);
  merged 20-layer export: `/tmp/opencode/phone-ui-models/needle-phone-ui-20l.cact`
  (SHA-256 `ea3a8481c32b0c023a95f8ac833b01fa17033fed1884ce7599d003a398493ccd`).
  Synthetic validation/test reports:
  `/tmp/opencode/phone-ui-needle-tuned-validation.json` and
  `/tmp/opencode/phone-ui-needle-tuned-test.json`. That first tuned test ran
  alongside validation and a real-trace probe, so its CPU latency is
  contention-affected. A separate serial same-host rerun confirmed **59/128 →
  83/128** and measured p50 **394 ms base vs. 357 ms tuned** (p95 both ~470 ms).
  This does **not** represent on-device or full Tasker latency. Comparable
  reports: `/tmp/opencode/phone-ui-needle-base-serial-test.json` and
  `/tmp/opencode/phone-ui-needle-tuned-serial-test.json`.
- On the same six Tasker states, a separate **read-only first-four-controls**
  Needle projection got **1/6** with the 20-layer base. The chosen control
  differed from Laya's full seven-choice probe; three other controls were
  omitted at every step, although all five actionable gold controls happened
  to remain in the first four on this episode. The result is format-dependent
  and not an Android runtime measurement. Replay via
  `tools/benchmarks/eval_phone_ui_real_needle.py`;
  baseline report: `/tmp/opencode/phone-ui-needle-base-real-shadow.json`.
  The tuned model reached **2/6** with the same renderer and still called a
  tool at the already-playing screen; report:
  `/tmp/opencode/phone-ui-needle-tuned-real-shadow.json`.
- On the **untouched frozen 13-locale generated corpus**, the 20-layer Needle
  base scored **28/40 (70.0%)** raw top-1 on held-out model cases; the tuned
  model fell to **19/40 (47.5%)**. Neither produced automatic actions at the
  98% precision gate (zero coverage). The tuned checkpoint's confidence head
  is not calibrated and reports `None` in this engine, so the benchmark treats
  it as zero and must not use that threshold as evidence of calibrated safety.
  Reports: `/tmp/opencode/needle3/mobile-actions-recheck-20260927.json` and
  `/tmp/opencode/phone-ui-needle-frozen-corpus.json`. This synthetic fine-tune
  **regressed** substantially outside its training templates.
