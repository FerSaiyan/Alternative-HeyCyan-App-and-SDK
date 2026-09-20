# Local-agent bounded-action calibration corpus

`local_agent_mobile_actions_v1.jsonl` is an audited, generated benchmark for
the fast decision layer between Tasker/AutoInput and CyanBridge. It is not a
general Android automation benchmark and it is not training data.

## Contract represented by the corpus

- Tasker/AutoInput owns accessibility observation and execution. CyanBridge
  does not request an accessibility service for itself.
- Every `CLICK_NODE` candidate comes from a Tasker-observed visible node and
  carries its exact `nodeIndex`. A model selects that candidate; it must never
  invent text labels, coordinates, or node identities.
- CyanBridge owns candidate construction, blocked-package policy, approval
  policy, retries, completion, and escalation.
- The fast backend receives only a bounded set of legal actions. It can select
  `DELEGATE_PLANNER` but cannot execute arbitrary tools.
- Open-ended text, such as composing a polite email, is represented by
  `DELEGATE_LOCAL_LLM`. The selected CyanBridge local LLM generates the prose.
  `TYPE_LITERAL` is legal only when text can be copied exactly from the user.
- Sending an email is an external side effect and therefore selects an
  approval request rather than tapping Send directly.
- Blocked packages have no candidates and are rejected before model inference.

## Contents

- 169 cases: 125 calibration and 44 held-out test cases.
- 13 locales: English, Brazilian Portuguese, Spanish, French, German,
  Italian, Dutch, Russian, Japanese, Korean, Simplified Chinese, Hindi, and
  Arabic.
- Launcher routing, YouTube search, exact query entry, search-result choice,
  Gmail compose/recipient/body/send stages, vague requests, missing controls,
  and a blocked-package policy boundary.

The held-out split is deterministic by `(locale, scenario)`. It tests threshold
selection without tuning on the reported test rows, but translated cases share
scenario structure and therefore are not an independent real-world test set.
Translations should be reviewed by native speakers before this corpus is used
as a release gate.

## Regeneration and validation

Run from the repository root:

```bash
python3 tools/benchmarks/generate_local_agent_calibration.py
```

The generator validates unique IDs, expected-candidate membership, bounded
candidate counts, and Tasker ownership of click nodes. Keep the generator and
JSONL in the same commit so changes remain reviewable.

## Acceptance policy

Calibration fits score/confidence thresholds only on the `calibration` split,
with a default target of 98% precision for automatic non-fallback actions. The
chosen thresholds are then frozen and reported on `test`. If no threshold
reaches that precision with nonzero coverage, the backend is unsuitable as an
independent automatic action selector; reporting zero coverage is the correct
result.

The benchmark evaluates one bounded next step. End-to-end reliability still
requires Tasker HIL tests, exact-node execution checks, approval tests, and
completion detection on real applications.
