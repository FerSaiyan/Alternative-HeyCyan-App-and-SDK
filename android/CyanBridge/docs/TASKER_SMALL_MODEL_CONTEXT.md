# Small decision models over Tasker accessibility observations

For the upstream **fine-tuning recipes**, Ship With Jev browser examples and
an episode-based Tasker training/evaluation plan, see
[`LAYA_NEEDLE_PHONE_TRAINING.md`](LAYA_NEEDLE_PHONE_TRAINING.md).

## What the upstream documentation says

- [Laya's browser-agent example](https://nandhakishorm.github.io/laya/finetune_browser_agent/)
  moved full element labels/roles/current values **out of state and into choice
  options**; retaining the whole element table in state truncated relevant
  candidates. Its base model failed the browser task zero-shot; task-specific
  training, real DONE states, mid-task negatives, and on-policy corrections
  produced a historically reported 62% on that *browser* suite. The newer
  [v17s card](https://huggingface.co/cklxx/laya-browser) supersedes that
  result and discloses evaluation-goal leakage in earlier on-policy data;
  its clean retrain removed those rows. None establishes phone accuracy. See
  also [Laya's calibration/option-order limits](https://nandhakishorm.github.io/laya/benchmarks/).
- [Needle's tool-design guide](https://cactuscompute.com/blog/designing-tools-for-needle)
  recommends one narrow, plainly named tool per action, evidence-grounded
  arguments, a small current toolset (five or fewer render directly), and
  environment **facts**, not instructions, in the system turn. Its
  [Python reference](https://cactuscompute.com/blog/needle-python-docs) says
  `complete()` is one turn: zero calls is refusal; multiple calls are valid for
  a genuinely multi-action request. `suppressed_calls` and `validation.ungrounded`
  matter; the base model's [confidence](https://cactuscompute.com/blog/needle-confidence)
  is not a phone-action permission. Triggers force calls and must **not** be
  used to bypass CyanBridge's approval or observation requirements.
- [AutoInput's FAQ](https://joaoapps.com/autoinput/faq/) acknowledges some app
  controls are not accessible to its UI interaction path. A silent Tasker UI
  Query timeout cannot be repaired by presenting fewer tokens to either model.

## Tasker → CyanBridge → small model contract

1. Tasker/AutoInput observes foreground package, visible labels, IDs and
   coordinates. Keep raw/current observation **inside** the phone. Treat
   page/app text as untrusted data; never let text on a page become agent
   instructions. Do not pretend the compact Tasker contract reports
   `clickable`/`editable` flags: it currently supplies text, ID and coordinates,
   and defaults those attributes to false.
2. CyanBridge checks blocked package, current task stage and action policy,
   then constructs a **fresh** 2–4 candidate list. Each option is tied to a
   current Tasker node or exact user-provided literal; no model-invented
   coordinate, recipient or draft. Prefer exact search suggestion, explicit
   control, and requested channel over unrelated recommendations, ads,
   notification toggles and UI chrome. If the necessary node is missing,
   delegate instead of clipping it out of an arbitrary first-three list.
3. Feed a short *current subgoal* (not the whole multi-step plan repeated in
   several fields), foreground app/phase and only the few necessary status facts.
   Put readable label/role/value in the Laya option criteria; for Needle use
   semantic tool names such as `tap_search`/`choose_exact_suggestion` rather
   than `action_a`. Keep node IDs/coordinates in CyanBridge's binding table,
   outside the model description. Never repeat the entire accessibility dump
   in state and options. Track context/token count, truncation, and omitted
   options. Re-observe and verify the binding before executing a tap.
4. For Needle, one current-step query, up to a few directly rendered tools;
   zero/multiple/unknown calls or ungrounded arguments go to the detailed
   planner. Model confidence is **not** approval. For Laya, include an explicit
   planner/defer option, fit probabilities on this exact option format and
   measure order sensitivity. Neither model writes email prose or establishes
   playback/delivery; evidence checks remain in CyanBridge.
5. Email drafting stays with the grounded prose planner. Explicit user approval
   authorizes only that checked draft, and a fresh Tasker observation must
   verify the Gmail composer before tapping Send. The present external Gmail
   composer is **not observable through this Tasker/AutoInput UI Query** in HIL;
   both models must abstain at that boundary. A successful `SENDTO` handoff is
   not a sent or self-delivered message.

Example of the *one-step* model-facing view at a search suggestion:

```text
current_step: choose the exact search suggestion Linus Tech Tips
app: YouTube; phase: suggestion
options: choose_exact_suggestion = visible “linus tech tips”
         edit_search_suggestion = visible “Edit suggestion linus tech tips”
fallback: detailed planner
```

The two options are separately bound to the Tasker-observed nodes. Their
coordinates do not appear in the model request. The planner must handle any
unsupported page or ambiguous visible state.

## What has actually been measured

The opt-in `JevShadow` trace contains six consecutive, real Tasker-observed
YouTube decisions. `tools/benchmarks/compare_laya_needle_live_shadow.py`
replayed them on workstation CUDA (multilingual Laya) and Linux CPU (Needle 3),
without letting either model act:

| Input | Laya / Needle outcome | Warm host median |
|---|---|---:|
| Previous repeated-goal, first-three/action-letter presentation | Laya selected Navigate up at the exact suggestion and a notification at the player; Needle returned multiple calls at 2/6 steps | ~41 ms / ~1.24 s |
| Experimental phase/current-step/semantic-tool presentation | Both returned the intended candidate on five steps; the already-playing screen deterministically deferred without inference | ~40 ms / ~0.21 s over the five inferred steps (one host run; varies with load) |

**Crucial caveat:** four of those five compact steps exposed just **one**
actionable tool; code had already done most of the selection. Reversing the two
suggestion tools kept both answers on the exact suggestion in a *single*
exploratory case. This is evidence that the input format/length matters, **not**
measured general accuracy, safe auto-action precision, or model-driven task
completion. The compact renderer is only a read-only, YouTube-specific host
experiment in `tools/benchmarks/phone_accessibility_frame.py`; it is not
installed as the production Gemma gate. Needle's published Android engine is
ARM64/ARMv7 (per its current device guide), not this licensed x86_64 Pixel AVD.
On the five compact calls,
`needle_init` reported 46–95 static-prefix tokens; the previous replay had
longer, goal-repeating prompts, but its token count was not measured.

Reports and traces are outside Git under `/tmp/opencode/`: baseline
`jev-laya-needle-live-shadow.json`, compact
`jev-laya-needle-compact-frame.json`, and the original observed states
`jev-laya-needle-youtube-shadow-trace.log`. Checkpoint/model weights likewise
stay outside Git/default CI.

## Next validation gate

Collect **independent** Tasker snapshots across apps and phases, including
ambiguous/missing controls, after-action DONE and mid-task negatives, unrelated
content, multilingual labels, and inaccessible screens. Label the exact current
node and stage without using a model's preferred answer. Split by app/page and
task episode, not translated near-duplicates. Compare full/deduplicated frames,
option order swaps, true one-vs-multiple candidate stages, prompt token budgets,
raw top-1, exactly-one-call rate, argument grounding, calibrated precision and
coverage on held-out episodes. Keep the existing Pro/planner fallback and
approval policy until a model-specific threshold passes this gate **and** an
end-to-end Tasker HIL on a compatible Android target.
