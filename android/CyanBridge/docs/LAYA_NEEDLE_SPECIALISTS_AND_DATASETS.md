# Laya and Needle specialists: datasets and product contracts

Reviewed **2026-10-03**. Focus: train **Laya and Cactus Needle**, with separate
specialists for voice deliberation, configuration selection and phone UI
automation. This supplements the [voice/Tasker findings](JEV_VOICE_TASKER_TRAINING_FINDINGS.md)
and [phone training recipe](LAYA_NEEDLE_PHONE_TRAINING.md).
The user-requested [remote training/ablation pilot](../training/laya_needle_specialists_v1/README.md)
implements paired public operation-only voice subsets and generated native
configuration examples. Its development results are separate from the proposed
real-voice/Tasker training programme below.
The versioned [source catalog](../training/laya_needle_dataset_sources_v1/catalog.json)
records access locations, reviewed revisions, licensing and measured/source-reported
statistics. It is a discovery inventory, not an assembled training split.

## Recommendation: three contracts, separately evaluated

The user's subsequent narrowing requirement is now implemented in the offline
[`bounded-specialist-v3` contract](BOUNDED_SPECIALIST_DECISIONS.md): status +
currently eligible action families → selected settings category → selected
setting → eligible bound value/download offer. Both models choose from the
same turn-specific options. Evaluate self-routed paths as well as oracle turns;
the earlier flat public-operation pilot is diagnostic evidence only.

| Variant | Input | Bounded output | First useful training sources |
|---|---|---|---|
| **Voice router** (`voice-router-v1`) | Final ASR utterance; current available action families; essential session facts | One offered action family or explicit clarification/no-change/fallback choice | MASSIVE/SLURP text, CLINC OOS, compatible Mobile Actions/DroidCall subsets, then CyanBridge-specific commands |
| **Configuration selector** (`config-selector-v1`) | Utterance + selected scope; currently supported settings/plugins; current values and capability/schema versions | One offered category → setting → eligible operation/value choice, or clarify/no-change/fallback; host binds the effect | CyanBridge's own typed actions/preferences/plugin catalog; public data supplies auxiliary phrasing, not our field labels |
| **Phone UI selector** (`phone-ui-v1`) | Current subgoal; fresh Tasker observation; last checked result; eligible bound candidates | One operation/compatible observed target/literal binding, or defer/wait/DONE proposal | AndroidControl projected text/action pairs; separate real Tasker episodes; Artemis remains an auxiliary source |

Train Laya and Needle baselines on the **same canonical records** for each
contract. A specialist is a versioned checkpoint/adapter and schema, not a
promise that both models must be loaded for every action. Choose per variant
from independent development results and real-device memory/latency.

For the voice router, Laya chooses an offered action family; Needle uses one
non-executing, zero-argument bound-choice tool. Clarification, no-change and
fallback are explicit tools in this contract, so `[]` is invalid rather than
an ambiguous combination of conversation/refusal. Configuration follows
selected scope → category → setting → eligible operation/value. Keep the
original request through every turn; prior selections constrain scope rather
than replacing the user's intent.

For setting values, Laya can choose from a bounded enum or from independently
parsed utterance values/spans; it does not need to generate arbitrary strings.
For “every 20 minutes”, parse `20` and its unit from the request, offer the
compatible interval operation, and validate its range in code. The preferred
contract lets Needle select that same bound candidate without generating its
argument. The earlier direct typed-argument pilot is diagnostic; any further
argument-generation experiment requires the same evidence/type/range checks.
Resolve plugin aliases only against the currently installed catalogue.
Unknown names, missing values and ambiguous scope require explicit labels.

This changes the earlier ordering: **voice and native configuration training
can progress before a broad Tasker UI corpus is ready**. Native commands have
typed app state and independently readable preferences. The UI variant retains
its real-observation data gate. All proposed model execution stays shadow-only
until its own acceptance and runtime checks pass; current Gemma margin ≥0.10,
planner fallback and explicit email approval/delivery boundaries remain.

## Dataset shortlist

Sizes below are not additive independent evidence. MASSIVE localizes SLURP,
and related home-assistant corpora can share underlying utterances. Deduplicate
and group across sources before defining our own holdouts.

| Dataset / access | Verified contents | Fit for Laya/Needle | License and limitations |
|---|---|---|---|
| **[Google Mobile Actions](https://huggingface.co/datasets/google/mobile-actions)** | Inspected pinned JSONL: **9,654 rows**, `metadata=train` **8,693**, `eval` **961**. User/developer messages, tool schemas, assistant calls. Seven gold-call names: calendar/contact creation, Wi-Fi settings, flashlight on/off, maps, email. | Best small public phone-command starting point. Laya operation/capability choices; Needle `query/tools/answers` conversion. Settings coverage is mainly opening Wi-Fi settings, not changing arbitrary settings. | **CC BY 4.0**. All 8,693 train rows have calls: 5,794 single, 2,899 multi, **zero no-call rows**. HF exposes all rows as one `train` split: filter internal metadata. Some outputs normalize dates/compose text rather than copy spans. |
| **[DroidCall](https://huggingface.co/datasets/mllmTeam/DroidCall)**; [generator](https://github.com/UbiquitousLearning/DroidCall) | Inspected `DroidCall_train.jsonl`: **10,071 rows**, 7,435 single-call / 2,636 multi-call / **zero no-call**. `query`, `tools`, `answers`; Android Intent wrappers for settings, camera, timer/alarm, files, search and contacts. | Broader capability vocabulary. Useful seed for action/setting-family selection and grounded argument extraction after review. Use structured source, not the default code-string chat export. | **Apache 2.0** per publisher card. Tool schema uses `arguments`, Python-style types/defaults, not ready-to-use JSON Schema. Inspection found answer-name anomalies and a training example inventing an email address/body not present in its query. Quarantine unsupported/ungrounded/dependent calls. |
| **[MASSIVE](https://github.com/alexa/massive)** | 19,521 parallel utterance IDs/locale; 60 intents / 55 slot types. **1.0: 51 locales; 1.1: 52**, adding Catalan. `utt`, `annot_utt`, `intent`, `scenario`, `partition`, source ID. | Best multilingual utterance/slot seed. Audio volume, alarms, device/assistant phrasing; scope-relative unsupported cases. Use a coarse routing menu plus specialist choices rather than one 60-option head. | **CC BY 4.0 data**; repository code has separate licensing. Primarily text, not new recordings. Keep parallel IDs together and official train/dev/test partitions. Laya already benchmarks MASSIVE: public results are not a new deployment holdout. |
| **[SLURP](https://github.com/pswietojanski/slurp)** | Roughly 16.5k unique text commands and ~72k recordings; scenario/action/intent, token-indexed entities, recording links and speaker metadata. Explicit `train`, `devel`, `test`, `train_synthetic` files. | Grounded intent/slot examples. Text first; recorded audio can support an ASR-text error study under its separate terms. Useful for clean-vs-ASR disagreement. | **Text CC BY 4.0; audio CC BY-NC 4.0**. Do not call reference transcripts actual ASR hypotheses; obtain/recompute them with pinned ASR and keep recording/source/speaker groups. MASSIVE's English source overlaps. |
| **[CLINC OOS / CLINC150](https://github.com/clinc/oos-eval)**; [HF](https://huggingface.co/datasets/clinc/clinc_oos) | 150 supported intent classes + explicit out-of-scope. HF `plus`: **15,250 train / 3,100 validation / 5,500 test**. Includes volume/language/settings, conversation and unsupported requests. | Particularly useful for voice no-action/OOS training. Label against our actual capability menu; a source OOS label is not automatically OOS for CyanBridge. | **CC BY 3.0**. Single-intent text; no setting schema, exact slot supervision or UI state. HF configurations share data: pick one, do not pool them as new samples. Generic yes/no is not context-bound approval. |
| **[NLU Evaluation / HWU home-domain family](https://github.com/xliuhw/NLU-Evaluation-Data)** | Publisher reports **25,716 annotated rows** with scenario/intent, raw/normalized answers, entities and annotator status. Semicolon-separated CSV; historical cross-validation exports. | Lower-priority auxiliary home/device phrasing and slot labels. Original annotated source is inspectable; prefer it to an unverified HWU64 mirror. | **CC BY 4.0**. Exclude `IRR_` statuses; normalized dates/numbers differ from literal source. Related home-assistant sources require overlap audit. Historical shuffled folds are not our family/speaker split. |
| **[AndroidControl](https://github.com/google-research/google-research/tree/master/android_control)** | Publisher: **15,283 demonstrations / 833 apps**, high-level goals and step instructions, screenshots, accessibility trees and JSON actions, official split file in GCS. TFRecord/GZIP with serialized AndroidEnv tree protobufs. | **Best public UI auxiliary source for these text models**. Project a11y into bounded text candidates and map unambiguous gold actions to targets. Learn current-step action choice without requiring a vision model. | **Apache 2.0** stated in official README. Not Tasker observations; richer metadata and older app layouts. Tree/action alignment needs auditing. No explicit DONE in the documented action space; a final screenshot alone cannot supply a verified phone DONE label. |
| **[GUIOdyssey, updated release](https://huggingface.co/datasets/hflqf88888/GUIOdyssey)** | Updated card: **8,334 episodes / 212 apps / six devices**; screenshot paths, actions, low-level instructions, descriptions/intentions/context, SAM2 boxes and app/task/device splits. Older repo text also mentions 8,834: pin release, do not combine counts. | Secondary source of multi-step task language and action vocabulary. Potential reviewed text auxiliary rows only where target evidence is independently available. | **CC BY 4.0**. Updated card does not expose a Tasker-like a11y tree. Descriptions/intentions/boxes are annotation, not runtime Tasker evidence; cannot directly convert screenshot coordinates into eligible Tasker nodes. |
| **[AndroidWorld](https://github.com/google-research/android_world)** | **116 task definitions / 20 apps**, parameterized tasks, structured observations and independent reward/state checks. | Collection and evaluation environment, **not a ready-made training corpus**. Useful completion oracles and train/eval task-family separation. | **Apache 2.0 code**. Isolated fixture only; preserve authenticated Pixel. Generated episodes need their own observer provenance and task/seed holdouts; parameter variation is not new app diversity. |

Not recommended for our product training: **[Fluent Speech Commands](https://fluent.ai/fluent-speech-commands-a-dataset-for-spoken-language-understanding-research)**
has 30,043 recordings but only 248 phrases / 31 intents, and its publisher
expressly excludes commercial product training/testing/development. Google
Speech Commands is mainly single-word acoustic keyword spotting, not sentence
deliberation for these text models. Neither fills our custom plugin/mode gap.

## Conversion contract: public data → our two model formats

Keep canonical source ID/revision/partition/group, original utterance,
capability menu, target/operation/value labels, explicit no-action reason,
argument evidence spans or deterministic derivations, observer source and
postcondition provenance. Export two views:

- **Laya:** `state`, bounded `questions`, training `gold` distributions/classes.
  Stage-one coarse route; stage-two eligible capabilities or config fields;
  UI operation and compatible target. Shuffle options, retain a legitimate
  defer choice. Human hard labels may use one-hot training targets; these
  are not claims about calibrated deployment probabilities.
- **Needle:** `query`, current `tools`, exact `answers`, optional short derivation
  `reasoning`, system **facts**. Train one current operation or `[]` for the UI
  contract; preserve multi-request examples in a separate routing/decomposition
  track. Do not relabel a two-action request as one arbitrary successful call.

Source-specific conversion requirements:

1. Mobile Actions: unwrap each `function`; normalize schema type names such as
   `OBJECT`/`STRING` to JSON Schema types without changing value strings; accept
   dict or JSON-string arguments. Extract time facts from developer turns,
   rather than copying instructional boilerplate. Reject or explicitly mark
   derived dates/body generation for the exact-span selector. Never silently
   populate absent query arguments from the answer. Keep only `metadata=train`
   for fitting; the HF outer split is insufficient.
2. DroidCall: convert declared `arguments` to `parameters/properties/required`
   with an explicit type map, **never eval Python/code strings**. Check every
   answer name against its menu, required values against evidence, and exclude
   result-reference placeholders and ungrounded optional defaults. Public
   email/call examples can teach request classification in a separate routing
   view; they do not enter our executable UI toolset or change approval policy.
3. MASSIVE/SLURP: retain intent/slot names and actual spans, parse annotations
   using source tokens/offsets, verify alignment with raw utterance. A request
   to control a light is not rewritten as a request to activate an image mode
   merely to manufacture a product label. Build new reviewed product phrases.
4. CLINC: recalculate scope under the offered menu; use actual `oos` and known
   unsupported classes as labelled negatives after review. Keep conversation,
   ambiguous request and unsupported capability separate in the host labels.
5. AndroidControl: pair each pre-tree with its intervening action and next
   observation. Map click point to one unambiguous eligible tree target;
   overlapping/unnamed/unrepresented targets are exclusions or defer cases,
   not nearest-node guesses. Distill only Tasker-observable fields; tag
   `ANDROID_CONTROL_A11Y_PROJECTED`, never `TASKER_AUTOINPUT`. Preserve omitted
   gold-target statistics. Input text must be grounded in the request/current
   planner step, not copied from the gold answer to inflate extraction accuracy.
   Report runs using human low-level step instructions as **oracle-subgoal**
   evaluations, separately from high-level goals with a live planner's subgoals.

Raw reference corpora, screenshots and model weights stay outside Git/default
CI. Any acquisition records exact bytes/checksums, revision, license and split
selection before conversion. Catalog revision pins for GCS-backed data pin
documentation, not the bytes of future TFRecord downloads.

## CyanBridge-specific training is essential for modes and plugins

Public datasets do not label our **AI image route, Gemini Live, installed plugin
names or plugin settings**. Build a product capability manifest from these
existing code contracts, then collect speech/text against that manifest:

| Current source | Training distinction to preserve |
|---|---|
| `shared/.../glasses/GlassesDashboardPresentation.kt` | `SelectAssistantMode`: phone assistant vs custom AI provider. `SetAiWakeWordRoute`: voice question vs image question. Persistent route selection differs from invoking `TestImageQuestion` or capturing a photo. |
| `app/.../ai/live/GeminiLiveVisionPreferences.kt` | Refresh modes are **initial image only (0)** and **each significant speech turn (2 sentinel)**. They are not 2-second timer choices; legacy 5/10/15 values migrate to 2. Train semantic modes and let code map them. |
| `shared/.../plugins/NativePluginModels.kt`, `ui/CommunityPluginPrefs.kt` | Current plugin IDs/availability, enable/disable, glasses shortcut assignment and plugin start/stop need distinct intent labels. Actual UI effects vary: `CommunityPluginsActivity` starts Walking Aid when enabling it; `MainActivity.startNativePlugin` also enables several plugins. Manifest operations must reflect those effects. “Activate plugin” may need clarification between enable and run. |
| `plugins/visualdiary/VisualDiaryPreferences.kt` | Interval is **1–240 minutes**; changing it does not inherently start capture. Persisted plugin enable and plugin-local enable/service state require consistent dispatcher handling. |
| `plugins/handsfreetranslator/HandsFreeTranslatorPreferences.kt` | Source/target language, autodetect and speak-output are separate fields. “Speak Spanish” can mean response language, target language or a live session; use context or clarify. |
| `plugins/PluginVoiceSupport.kt` | Android recognizer emits partial/final text and allows one active voice plugin. Dispatch from finalized requests; record recognition failures/conflicts instead of creating an assumed successful activation. |

These classes are **not yet an implemented voice-action manifest**. A future
adapter must invoke the existing application dispatcher/policy and verify
actual resulting state. Model-generated preference keys, arbitrary plugin
names, service calls and inferred eligibility are outside the contract.

### Example task distinctions

| Utterance | Label under a declared product schema |
|---|---|
| “Use image questions when I say the AI wake word” | Set wake-word route to IMAGE_QUESTION; do not capture immediately |
| “What can you see?” | Image-question invocation if available; persistent route unchanged |
| “Switch to Gemini Live” | Choose declared Live setting/session operation, or clarify if both are offered |
| “Enable Visual Diary” vs “Start Visual Diary now” | Plugin enable vs lifecycle start; keep separate |
| “Change Visual Diary to every 20 minutes” | Select Visual Diary interval, set 20, verify persisted value |
| “Not Visual Diary; enable Live Caption Relay” | Correction selecting the second plugin, not blanket no-action |
| “Don't change the translator language” | No setting mutation |
| “Make the diary less frequent” | Clarify missing interval unless an explicit relative-change operation exists |
| “Set the diary to every 500 minutes” | Reject/clarify invalid value before the existing setter silently clamps |
| “Open Wi-Fi settings” vs “Turn Wi-Fi off” | Navigation vs state change; never equate them |

Use settings readback as the oracle for native changes; use service state for
plugin start/stop; use fresh Tasker outcomes for external UI. No settings
readback establishes that an external app completed a UI task.

## Specialist training and evaluation sequence

1. **Build voice/config data first.** Freeze the actual capability/setting
   schema, aliases, current-state facts and class policy. Proposed initial
   budget: 1,000–2,000 independently phrased product requests across supported
   families plus real finalized ASR recordings/transcripts; include roughly
   25–35% clarification/no-action/invalid/unavailable cases. These are collection
   targets, not a precision guarantee. Keep original sessions, speakers,
   paraphrase families and schema versions grouped before augmentation.
2. **Add reviewed public training subsets.** MASSIVE/SLURP for language/slots,
   CLINC for OOS, Mobile Actions/DroidCall for compatible capability contracts.
   Deduplicate relatives/translations and keep source tags. Compare product-only
   vs product+public ablations; do not let generic domains dominate the loss.
   Reserve development and calibration separately; keep a new product final
   set sealed. Public benchmark scores are regression/auxiliary evidence.
3. **Train Laya and Needle per contract.** For Laya compare frozen-head and
   encoder+head adaptation with the upstream voice recipe. For Needle compare
   base/tuned **20L**, then 8L/4L at matched depth using exact tool schemas and
   explicit `[]`. Local LoRA confidence is unset; fit an application gate on
   independent calibration data. Neither a high raw score nor forced calls
   supplies execution permission. Skip larger generative-model training.
4. **Bootstrap UI auxiliary data separately.** Parse/audit a bounded
   AndroidControl train subset, measure target recall under the reduced view,
   then collect new Tasker families/negative/DONE episodes. Compare Tasker-only
   vs projected-public augmentation and separate source metrics. Existing
   Artemis/Tasker diagnostics and inspected tests remain development evidence.
5. **Measure complete cascades.** Voice route precision/coverage, settings
   scope/key/value accuracy and unintended mutation, unseen installed-plugin
   selection, no-call false actions, UI target recall and verified completion.
   Test that two individually strong stages do not compound errors: report
   end-to-end request success and latency, including stage rejection/fallback.
   Use a trusted reviewer label, not the earlier variant's prediction, to
   train the next stage.
6. **Deploy only the justified specialist.** Needle's serialized process-global
   native engine needs one owner and explicit reset/reload on variant/schema
   changes; measure switching costs and avoid cross-task context leakage.
   Separate Laya checkpoints duplicate substantial encoder memory; evaluate
   on-demand residency or a shared-encoder/multiple-head experiment rather
   than assuming three full models fit at once. Needle is genuinely tiny;
   Laya is a zero-generation typed scorer, but its phone artifact remains
   substantially larger. Android ARM Needle and Laya LiteRT parity/latency
   gates from the roadmap still apply.

Immediate implementation priority: product manifest + voice/config collector
and split audit, followed by source converters and model-specific training
exports. The remote pilot now supplies initial converters, generated product
examples and model-specific training exports. Actual recorded product speech,
an executable native voice dispatcher and a new real Tasker UI training split
remain subsequent work.
