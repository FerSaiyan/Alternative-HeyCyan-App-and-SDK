# Worklog — CyanBridge local agent

## 2026-10-26 — Protocol-fixes branch retired; spoken questions merged into cyan-refactor

- `heycyan-eyevue-tunebuds-protocol-fixes` is retired; `cyan-refactor` is the only working branch.
  Its last commit (`c8fc4efb`: SMART spoken-question routing, keyless web answers, LiteRT follow-up
  sessions, spoken-question memory, Pro relay audio, voice profile, vision assistance onboarding)
  is merged as `4a687fe0`.
- MainActivity's frozen AI/voice, TTS and intent regions took the commit's version unchanged, so the
  line budget went from 4,507 to 5,069. Everything else was moved to where the code lives now: relay
  audio fields in the shared `RelayClient`, new settings in the typed preferences
  (`AssistantPrefs.QuestionRouting`, `AppPrefs.Accessibility`, `ProPrefs.Ai.audioCapableModels*`),
  voice enrollment through `AudioSessionCoordinator`.
- Hardware checks: `docs/refactor/HARDWARE_TEST_CHECKLIST.md`, "Spoken questions, web answers, voice
  profile and vision assistance". GlassesQuestionFlowTest compiles; it was not run (no emulator).

## 2026-10-26 — "Without Meta AI" pairing option investigated, then dropped

Idea: a third Meta pairing choice next to Developer Mode and the email invite, modeled on
[Visor](https://github.com/macedonga/visor) (AGPL-3.0, Android only, read at `68e45e7`). Visor replaces
the Meta AI app by making itself the glasses' owner. **Decision: dropped for now.** No code was
written and nothing from Visor was copied. The hybrid Developer Mode / invite registration was built
and merged instead.

- **How ownership works:**
  1. The app logs in to Meta through the Meta AI app's login flow. Passwords and email codes
     work; passkeys don't.
  2. The app generates its own EC P-256 owner key.
  3. The glasses send their certificate, serial number and a one-time code over Bluetooth.
  4. The app posts these to `graph.facebook-hardware.com` (`pair_request`, then `pair`) and gets a
     Meta-signed ownership receipt naming its key.
  5. The glasses verify Meta's signature and switch owner; Meta AI loses ownership.
  6. After that, every connection is local. The app proves it holds the owner key (ECDSA trust
     handshake), then opens an encrypted, multiplexed link. Meta is contacted again only for
     language packs and frame style.
- **Blocker:** Meta's hardware server issues receipts only to requests that carry Meta's own
  first-party app token (`HW|…`) and a Meta AI-style login (`AR|…`, `FRL|…` tokens). Visor's README
  says to extract them from the Meta AI app. Shipping that means impersonating Meta's app with its
  credentials, which is against Meta's terms. It risks users' Meta accounts and app-store removal,
  and Meta can break it at any time. The implementing agent declined to build it for this reason.
- **What it would take:**
  - Meta login; owner-key storage (Keystore/Keychain); the receipt exchange; the trust handshake.
  - The link: framing, ciphers, channel mux and reconnect.
  - Per-feature services: status, capture list and file import (Wi-Fi Direct), live view (the
    glasses host a soft AP), settings, and an assistant-server stand-in so "Hey Meta" can reach
    CyanBridge's AI.
  - AGPL rules out copying, so it needs a clean-room rewrite, several weeks for a useful subset.
    The alternatives are a separate license from Visor's author, or putting CyanBridge under AGPL.
- **Side effects:** Meta AI and every DAT app stop working with the glasses until they are paired
  again in Meta AI, which moves ownership back. Glasses firmware updates normally come through
  Meta AI, and Visor has no firmware-update path.
- **Android:**
  - Technically proven: Visor runs on real glasses over LE L2CAP / RFCOMM and Wi-Fi Direct.
  - Distribution would be a sideloaded APK only.
- **iOS:**
  - **Control link:** LE L2CAP is available (CoreBluetooth), so it looks possible. Classic
    Bluetooth / iAP2 (`com.meta.ar.wearable`) is limited to Meta's own MFi-approved apps.
  - **Media:** iOS has no Wi-Fi Direct. Joining the glasses' soft AP may work for live view; for
    media import it's unknown.
  - **Distribution:** no realistic path. App Review would reject Meta tokens and a login that
    poses as Meta's app. Personal development builds at most. Nobody has done it on iOS.
- **If it comes back:**
  - Preferred: the sanctioned route, a Meta partner/accessibility integration (like Aira) for
    "no Developer Mode, no invite".
  - Otherwise: a separate Android-only experimental APK outside the Play Store, where users supply
    their own tokens. This needs the owner's explicit decision on the token issue.
- Sources: Visor `README.md`, `CLAUDE.md`, `core/identity/provision/Provision.kt`,
  `core/transport/*`, `core/media/LiveStream.kt`, `core/protocol/Constants.kt`. Earlier research is
  in `~/research/a11y-apps/reports/` (`meta-dat-dual-registration-design.md`,
  `meta-dat-apps-survey.md`).

## 2026-10-25 — Context budget, LiteRT sessions for follow-ups, memory for spoken questions

- Token budget (Gemma 4 E2B processor, app strings): spoken instruction 87, decision routes 531, clock 33, LIGHT
  memory at caps 470, audio 25/s (30 s = 752), glasses photo ~268, web note 118–728 (weather box ≈ 470 alone).
  Whole decision turn 795 (5 s) to 1,891 (30 s + memory); fits 4k, not the 1024 minimum.
- Memory regression found: spoken questions (audio) ran with RagProfile.NONE and no query text, so no memory at all;
  LIGHT for a user without memory is ~100 tokens of seeded templates plus a "normal chat" preamble.
  `localagent/context/SpokenMemoryContext.kt`: query-free core (USER_FACTS.md entries, today's confirmed facts,
  today's summary) without headings/placeholders, ≤1,000 chars (~250 tokens), empty when there is no memory; read
  through LocalAgentMemoryStore (Memory Vault — plain file writes are ignored). Added after routes, before the clock
  (also sent to the Pro relay with audio, like the old STT path's LIGHT memory).
- Engine sessions: `SessionTurn(key, continueExisting, note)` on GenerationConfig → LocalChatSessionManager →
  LocalModelsProvider.streamChat → runChosenProviderQuery. LiteRtLocalInferenceEngine keeps the conversation open
  under the key; a continuing turn sends only its own content (audio / photo / text; `note` goes before the
  attachments) and fails with SessionUnavailableException when there is no open session or Conversation.getTokenCount()
  + turn estimate + 512 reserve > context size. Any other request closes it. llama.cpp and remote servers have none.
- LiteRT-LM fails every later message of a cancelled conversation ("Task cancelled", retry too), so in a session the
  decision turn is not cancelled: the app acts on the tag at once (photo/search/agent/Live) while the reply finishes,
  and waits for it before the next turn. Replies are capped by LiteRT (sendMessageAsync maxOutputToken = maxTokens)
  instead of the manager's cancel guard. LocalChatSessionManager also dropped newline-only chunks (`isBlank`).
- Spoken flow (`triggerSpokenDecisionQuestion` / `runSpokenTurn`, `ai/router/SpokenConversation.kt`): local model →
  session; camera photo and web results are turns in it (no audio re-encode; bare-tag replies fall back to the
  self-contained call); Google answers read out / fixed sentences become the next turn's note; 5 s follow-up window
  after answer/camera/search; a hotword question within 3 min continues; ignore resets.
- AVD (CPU) follow-up chain capital → "And how many people live there?" → "What's a famous building there?" →
  "Tell me more": all continued; population via search ("Canberra … 489,668"), tokens 856 → 949 → 1,359 (photo) →
  1,435; decisions 18.3 s (cold) then 3.0–5.2 s. 20-turn chain: all continued, 3,450 tokens at turn 20 (≈21 turns fit
  4k with this mix; ~75 tokens per short voice turn, ~400 per photo turn); follow-up decision time flat at 3–4 s.
  Seen: "building there" routed to [camera]; in-session photo answer came as "[camera] That is a pair of … socks"
  (accepted after clean); context bleed ("next public holiday" searched for Australia after the Canberra turns).
- Memory accuracy. Host (questions_memory_followup_draft.jsonl, 8 clips en/pt-BR): no memory 0/8 ("I do not have
  access to your personal information"), memory 6/8 (7/8 counting "alergia a peanuts"). Routing on the 224 other rows:
  v5a 186 → memory at the end 177 → memory first 168 (ignore 13 → 9 → 7, live 19 → 16 → 17); memory stays at the end.
  AVD end-to-end (Memory Vault facts): 6/6 with facts (4 memory + 2 controls) vs 2/6 without.
- Unit tests 731/731 (+ SpokenMemoryContextTest). Fixtures: /mnt/seagate/ML/cyanbridge-glasses-memory-followup-20261025
  (12 Piper clips), added to androidTest assets. Evals: eval/host-e2b-qat-spoken-memory-questions-20261025.jsonl,
  -tags-v5a-memory-20261025.jsonl, -tags-v5a-memfirst-20261025.jsonl.

## 2026-10-24 — Keyless web answers for `[search]`: DuckDuckGo in an off-screen WebView

- Goal: answer spoken `[search]` questions without an API key (open-source app) and, when DuckDuckGo's own AI
  (Search Assist) already wrote the answer, read it out without a second model turn.
- Access: from the workstation IP `html.duckduckgo.com` returns the duck CAPTCHA (HTTP 202) and headless Chromium
  gets the 418 error page; duck.ai chat needs an obfuscated JS challenge (`x-vqd-hash-1`). A real browser engine works:
  Chrome and the app's own WebView on the AVD were never blocked across ~60 searches.
- `ai/search/DuckDuckGoSearchAssist.kt`: loads `duckduckgo.com/?q=…&ia=web&assist=true` in an off-screen WebView
  (phone-size layout, "; wv" removed from the UA) and polls the DOM every 250 ms:
  `[data-testid="duckassist-answer-content"]` paragraphs (AI answer, done when unchanged 750 ms),
  the first non-ad module above the organic results (`li[data-layout]`, e.g. `forecast`, `maps_places`; never
  `related_searches`) as an info box, and `[data-result="snippet"]` snippets. `a[data-assist-icon-trigger]` (Search
  Assist tab) means an AI answer may still come; without it and with snippets it stops after 1 s. Timeout 12 s.
- `[search]` route in `triggerSpokenDecisionQuestion`: says "Let me check." (9 languages), searches with the decision
  payload, then: AI answer + English app language → spoken as is; AI answer otherwise → short model turn to say it
  in the spoken language; info box → model answers from it; snippets → model; nothing → NO_WEB_ACCESS_INSTRUCTION.
- AVD WebView results (ms from load): AI answers for "how tall is the eiffel tower in feet" (2.9–4.7 s), F1 winner,
  "usd to brl exchange rate", "time in tokyo", "next public holiday brazil" (All Souls' Day), "latest news",
  "what is the strongest pokemon card in the TCG right now" (5–8 s). Bare keywords ("bitcoin price") often get no
  AI answer. Weather queries get the `forecast` info box located by IP (correct city, ~2 s); when Search Assist
  answers weather it guesses a random US city (Halawa HI, Fort Worth TX, Kansas City…), so the box is preferred.
  Answers can be US-centric ("next public holiday" → Veterans Day) or stale (Lakers "next game" in the past).
  `kl=br-pt` localises boxes (Brazilian news) but drops most AI answers, so no region is set.
- Spoken flow on the AVD (GlassesQuestionFlowTest#smartWebQuestionReadsTheDuckDuckGoAnswer, CPU, cold model):
  "What's the weather like today?" → `[search] weather today` → info box (4.3 s) → "It is cloudy with a high of 26°C
  and a low of 23°C for Ribeirão Preto, Brazil today." "When's the next public holiday?" → `[search] next public
  holiday` → AI answer (6.5 s) read directly. Both pass; the model's decision turn is ~17 s on the AVD CPU.
- Prompt v4 (search payload as a complete English question) hurt routing: 109/144 vs v3 118/144 (plain 15/27 —
  "[search] What is the capital of Australia?"). Rejected. v5a (v3 + "short web search query in English") 119/142
  vs v3 118/142 on the same rows (ignore 8/15 vs 10/15, others equal or +1); now the app prompt. v5b not finished
  (stopped). eval/host-e2b-qat-spoken-tags-v4-20261024.jsonl, -v5ab-20261024.jsonl (v5a rows complete).
- Bugs found by the web flow: LiteRtLocalInferenceEngine dropped newline-only stream chunks (`isBlank`), so
  "[search] weather today\nThe weather…" streamed as "weather todayThe weather…" and the decision never stopped;
  SpokenDecisionParser now also cuts a search/phone payload at a following tag ("… in Brazil [answer] The next…").
- ToS: DuckDuckGo's Acceptable Use Policy forbids reselling and "frame, inline link, or similarly display any portion of
  the services within another service"; reading Search Assist answers aloud in the app is arguably that. Ask
  DuckDuckGo before shipping, or keep it an opt-in, user-initiated feature.

- Google instead of DuckDuckGo (same off-screen WebView, `ai/search/WebSearchAssist.kt`, engine in
  QuestionRoutingPrefs.webSearchEngine, default DUCKDUCKGO): `google.com/search?q=…&hl=<app language>`. Read: lead
  paragraph of the AI Overview (`[data-subtree="aimc"] [data-container-id="main-col"]`, `data-complete="true"` when
  done; trailing source chips stripped), `#rso` blocks above the first organic result as the info box (weather card,
  currency converter, sports), `.VwiC3b` snippets; `/sorry` or "unusual traffic" = blocked. Never blocked on the AVD.
- Same 9 queries in the app WebView: Google 9/9 with an AI answer or box, 1.5–5.3 s (mostly ~1.6–2 s); DuckDuckGo 6/9,
  1.8–8 s. Portuguese questions get Portuguese AI Overviews ("O próximo feriado nacional será em 2 de novembro de 2026
  (segunda-feira), Dia de Finados."; dollar, rain box, TCG); an English query with hl=pt-BR is answered in Portuguese,
  so Google answers are read directly in any app language. DuckDuckGo had nothing for 3/4 Portuguese questions.
- Spoken flows with Google (AVD, CPU): en weather (box → "It is currently 25° and cloudy, with a chance of moderate to
  heavy rain starting at 00:45."; the first run said "26°F" until the answer note kept units as written), en holiday
  (AI Overview read directly: "…All Souls' Day (Finados) on Monday, November 2, 2026."), pt weather and pt holiday
  (app language pt-BR: "Vou pesquisar." → "O próximo feriado nacional é o Dia de Finados, em 2 de novembro de 2026…").
  Search step 2.6–4.1 s after the decision.
- ToS: Google's terms forbid "using automated means to access content from any of our services in violation of the
  machine-readable instructions on our web pages", and google.com/robots.txt has `Disallow: /search`; stricter than
  DuckDuckGo's policy. Shipping the Google engine is the owner's call.
- 2026-10-25: Google is now the default engine (QuestionRoutingPrefs.webSearchEngine). Fallbacks and errors:
  AI Overview lead paragraph (read directly) → info box (model answers from it) → top 3 organic results as
  "title: description" (ads are in #tads, outside #rso; model answers, told to use the current local time) → fixed
  localized sentences: found nothing ("I searched the web but couldn't find an answer to that."), offline ("I couldn't
  search the web: there's no internet connection."), other failures ("I couldn't search the web right now. Please try
  again later."); 9 languages each. The model is no longer asked to report failures: offline it said "I need your
  location to tell you the weather." A failed model turn (decision or answer) speaks a localized "I couldn't process
  that question." and never the raw "Local Models error…".
- WebSearchAssist.Failure: WEBVIEW_UNAVAILABLE (WebView constructor throws; 3 ms), NETWORK (main-frame
  onReceivedError/HTTP error; offline detected in 0.3–0.5 s instead of the 12 s timeout), BLOCKED (/sorry or "unusual
  traffic"), TIMEOUT. "Loaded" requires the results container (#rso / DuckDuckGo mainline): one Google page reported
  readyState complete with an empty body.
- Google "People also ask" answers use the same `[data-subtree="aimc"]` block ("AI Mode replied:…", under
  `[data-initq]`); "bitcoin price" read a 2030 price prediction from one until those are skipped. The real overview
  sits under `[data-aim="1"]`.
- AVD spoken flows (Google): offline en/pt and WebView disabled (`pm disable-user com.google.android.webview`, then
  re-enabled) speak the fixed sentences above; results-only (debug hook GlassesQuestionTestHooks.webResultsOnly) →
  "The next public holiday in Brazil is on October 28, which is Civil Servants' Day." (the AI Overview says Finados,
  2 Nov; a 2B model answering from list-like snippets is weaker). Not tested: BLOCKED (no way to force the challenge),
  TIMEOUT. Many runs hit the AVD audio-encoder bug (status 13) before the decision; retried.

## 2026-10-23 — Question-path benchmark: Fold6 GPU + AVD CPU, host routing evals

Code since the 10-22 plan (uncommitted): `LocalModelLoadAnnouncer` cold-load cue; `GlassesQuestionTestHooks`
+ `MainActivity.debugGlassesEvent`; `GlassesQuestionFlowTest`; `QuestionPathBenchmarkTest`; decide-once
(`DecideOnceRequest`/`DecideOncePrompt`, LiteRT two-turn conversation, SMART + LiteRT only, photo sink for
prefetch/on-demand capture); LiteRT first-audio-after-install retry; current date/time/zone/region line in the
question system prompt; host eval `tools/benchmarks/eval_glasses_question_routing.py`. Fixtures v1.0 (252) and
v1.1 (+web/time, 351) under /mnt/seagate/ML/cyanbridge-glasses-questions-2026102{2,3}.

Fold6 was benchmarked with the existing app install signed by the default debug key: copies of the test APKs were
re-signed with ~/.android/debug.keystore and installed with `install -r -d` (data kept). The one-time
`--rotate-from-debug-key` migration on the Fold6 is still the owner's call.

Fold6 GPU, stock gemma-4-E2B-it.litertlm, 108 dev+test questions × 9 languages
(eval/fold6-GPU-bench-v2.jsonl):
| Path | Availability | Decision | Median total | Routing visual / plain |
|---|---|---|---|---|
| Android on-device STT → router → text answer | en, pt-BR only (7/9 languages no pack) | STT 2.5–3.0 s + router 0.45 s | answer 0.58 s | 3/10, 9/10 (CLARIFY often) |
| Raw audio → answer | all | none | 1.03 s | none (visual answered blind) |
| Decide-once (audio) | all | 1.56 s | 6.4 s answer / 13.1 s camera | 35/45, 45/45 |
Decide-once per language 8–10/10. AVD CPU decide-once (same 108): visual 33/45, plain 45/45, decision 9.3 s.

Findings:
- Transcribe-first is not viable as the only path: on-device packs are missing for most app languages on the
  Fold6, and the single-token router misroutes visual questions (CLARIFY/answer).
- Decide-once routes well across languages (plain never goes to the camera), but visual recall is 75–78%.
- The decide-once answer turn costs ~4–5 s before its first token on GPU even though turn 1 already prefilled the
  audio (raw audio answers in 1.0 s total). The model also already writes the answer after ⊲A⊳ in turn 1, so the
  next step is to stream the answer from turn 1 and only open turn 2 for the camera; profile with getTokenCount.
- ResponseFormat.regex is not enforced on the stock 0.16 AAR (CPU AVD and Fold6 GPU); route is parsed leniently.
- One Fold6 decision produced a malformed label ("⊲��⊳"); now falls back to answering.
- Host zero-shot (Gemma 4 E2B QAT bf16): deciding on words works (transcript 92/108 visual, 107/108 plain),
  raw audio does not (13/108 visual); writing the question first then deciding is close to a transcript.
  A separate decision turn with label scores + threshold 0.98 would give 96/108 visual, 106/108 plain, but needs
  the JNI scoring patch (Conversation clone + candidate scoring).
- Web/time research (agent): add ⊲C⊳ "needs current info" to decide-once; app fetches (holiday table offline,
  weather via paid/relay source — Open-Meteo free tier is non-commercial; generic search via a new Pro relay
  /search, opt-in); always inject date/time (done). Default-off "Live info lookups" settings + spoken disclosure.

### 2026-10-23 — Why decide-once decisions are slower than the earlier LiteRT decision research
Sources: /mnt/seagate/ML/gemma4-sm8650-port/WORKLOG.md (2026-10-08 "Weight-Sharing Results on Fold6",
"CyanBridge Decision LoRA 2x Slower Investigation", 2026-10-22 speed fix), android/CyanBridge/docs/
LITERT_SHARED_DECISIONS.md, NPU_ON_DEVICE.md.
- Earlier fast path: patched LiteRT-LM `Engine.scoreDecision` (opt-in `-PliteRtDecisionPrototype`
  + local AAR), QAT decision-LoRA package on the Fold6 NPU (QAIRT 2.50, weight sharing): 118/120 bounded
  dev, p50 1.34 s / p95 1.50 s. Reads label logits — no decoding — but runs two ~520-token text prefills
  (normal + reversed menu) in a fresh session. Older INT4 PTQ LoRA exports: 109–110/120 at 15.2 s CPU /
  4.6 s GPU (2x slower than stock: generic dynamic_wi4_blockwise32 PTQ + fixed 4096-slot FP32 KV).
- Fold6 backends for E2B: NPU prefill 2,250–2,900 tok/s, decode 32–35 tok/s, TTFT 0.24–0.46 s at
  512–1024 tokens (srq-a16-4k: prefill ~2.4k, decode ~30, init 8.7–11.5 s, PSS 1.61 GB); stock GPU
  prefill 540–1,050 / decode 14–16; stock CPU prefill 110–205 / decode 11–19.
- Today's decide-once (default build): stock 0.16 AAR, stock GPU bundle, audio input, and the label is
  produced by *decoding* ~20 tokens ("Q: …" + label) → 1.56 s on Fold6 GPU, 9.3 s on AVD CPU. The extra
  ~5 s on the answer route is the separate answer turn, not the decision (streaming variant being tested).
- Implication: on the NPU stack decide-once's decode would be ~0.6 s; label-logit scoring on the shared
  conversation (JNI patch: Conversation clone + candidate scoring, which the NPU executor does not yet
  support for Clone) would make the decision almost free because the audio prefill is needed for the
  answer anyway. The decision LoRA cannot share the answer conversation (merged weights; LoRA alters
  k/v), so shared-conversation scoring needs zero-shot calibration (host: threshold 0.98 → 96/108
  visual, 106/108 plain) or a routing ability trained into the answer model.

### 2026-10-23 — Web/time research details (agent report, verified items marked in its report)
- Mechanism: ⊲C⊳ route in decide-once turn 1 (optionally "K: weather|holiday|rates|search" and
  "S: <query>"); app fetches; results go back as a *user* text turn in the same Conversation. Tool calling
  (`@Tool`/OpenApiTool) works for text results in 0.16/0.18 but decides from raw audio, gives no
  consent hook (automatic calling runs inside the native callback), and drops ResponseFormat.
- Budget: 4096 context; decide-once system prompt is tiny (RAG off). Keep blocks short (weather ~80
  tokens, holidays ~50, search ≤600–800) because GPU prefill is 540–1,050 tok/s and CPU 110–205.
- Sources: relay has no search endpoint today (Gemini Live setup has no tools; chat via
  lib/cloud-model-router.ts). Open-Meteo free tier is non-commercial (subscriptions count as
  commercial), 10k calls/day, CC-BY 4.0, keeps IPs/logs ≤90 days. Nager.Date keyless, 204 countries
  (BR next: 2026-11-02 Dia de Finados), regional `counties` → bundle a 2–3 year snapshot for offline
  holidays. Brave Search $5/1k with $5/month credit, card required, storage rights extra. DDG has no
  official API; SearXNG scrapes (terms gray).
- Location: none used today (permissions only for Wi-Fi P2P/BLE; play-services-location unused).
  Proposed "home city" setting (rounded ~0.1°) + locale region for holidays (es is ambiguous ES/MX/AR).
- Disclosure: "Live info lookups" default OFF, per-category switches (weather, holidays, web search
  Pro-only), one-time spoken disclosure, recent-lookups list, cite the source, send only minimal data,
  never audio. Offline/timeout → model says it can't check that, in the user's language.
- Gaps fixed/remaining: decide-once had no date → now `ImageQuestionDefaults.currentContextInstruction`
  (weekday, date, time, zone, region) is in every question system prompt. `AssistantDecisionOptions`
  still lists "what is the weather today" as a plain answer example — change when the web class lands.
- Fixtures v1.1 (/mnt/seagate/ML/cyanbridge-glasses-questions-20261023, 351 rows, 429 clips, 35 MB):
  +81 web (weather today/tomorrow, next holiday in each language's own word, news, local teams, rates,
  pharmacy hours, traffic, online price) and +36 time (day of week, days until Christmas, date today);
  `weather_tomorrow` moved plain→web and `time_zone` plain→time (agent's call; revert if unwanted).
  Parakeet WER on new clips 0.052 (zh-CN/ko unchecked). Ambiguous deictic questions ("tell me more about
  this") are now provisionally camera-worthy without a photo — owner to confirm.

### 2026-10-23 — Owner decisions
- Deprecate the transcript router ("automatic table/decision") for SMART: SMART now runs only the
  decision model (decide-once, local LiteRT). Any other SMART setup (Pro, Tasker, phone assistant,
  llama.cpp) behaves as Classic; the router (STT → regex tables → single-token → JSON classifier) stays
  for Classic only. Removed the SMART-only router wiring (thresholded logit engine in the router
  provider) and the "no transcript → image flow" hand-off. If the serving model cannot decide, the
  recording is answered directly with the prefetched photo if any (no re-ask).
- Direction: replace slow on-device STT with raw audio to the model.
- Deictic ambiguous questions ("tell me more about this"): decision deferred until the decision model
  works well (needs per-language mapping).
- Streaming decide-once v1 (prompt "write ⊲A⊳ and then answer") biased toward answering (AVD visual→camera
  2/15); v2 keeps the decision prompt and streams whatever the model writes after ⊲A⊳ (turn 2 only if
  nothing follows). Measuring on AVD and Fold6.

### 2026-10-23 — Fold6 CPU benchmark (same 108 questions; eval/fold6-CPU-bench-v2.jsonl)
| Path (CPU) | Decision | Median total | Routing visual / plain |
|---|---|---|---|
| STT (en/pt-BR only) → router → text answer | STT 2.5–3.0 s + router 2.5 s | answer 2.7 s | 5/10, 10/10 |
| Raw audio → answer | none | 3.0 s (TTFT 2.1 s) | none |
| Decide-once (two-turn) | 4.7 s | 11.3 s answer / 19.3 s camera | 36/45, 45/45 |
Per language 8–10/10 (en, ko, zh-CN 10/10). CPU is 2–3× GPU; routing is backend-independent.
- AVD CPU, streaming decide-once v2 (eval/avd-decide-once-stream-v2.jsonl): routing identical to two-turn
  (visual 33/45, plain 45/45); decision 7.1 s (was 9.3), answer route 12.8 s (was 17.8), camera 28.0 s (was 30.9).

### 2026-10-23 — Fold6 GPU: two-turn vs streaming decide-once (eval/fold6-GPU-decide-once-stream-v1.jsonl)
- Same 108 questions, same routing (visual 35/45, plain 45/45). Streaming: decision 1.6 s (two-turn 2.3 s),
  answer route 6.6 s (7.8 s), camera route 15.2 s (15.3 s). Streaming helps a little; the large costs are
  elsewhere (next section).

### 2026-10-23 — Where decide-once time actually goes (Fold6 GPU probes, QuestionLatencyQuickTest)
New androidTest `hil/QuestionLatencyQuickTest`: `#secondTurnProbe` drives the LiteRT Engine directly (A/B
of vision backend, MTP, ConversationConfig, prompt wording); `#answerLatencyAfterQuestionWindow` times the
app path (LocalModelsProvider) from the moment the question window closes. Each run takes 1–7 min.
Results in /mnt/seagate/ML/cyanbridge-glasses-questions-20261022/eval/fold6-GPU-*-20261023.jsonl.
- **`ConversationConfig(enableResponseFormat = true)` costs 2.3–2.8 s per createConversation** (163 ms
  without it on the first conversation, then 3–20 ms). Decide-once sets it for the regex that the stock
  0.16 AAR does not enforce anyway; passing `ResponseFormat.regex` on the raw engine crashes natively
  (SIGTRAP in liblitertlm_jni). This is most of the unexplained ~4.5 s on the answer route.
- **Vision encoder on CPU costs 5.4–8.7 s per photo; on GPU 2.2–2.4 s.** The app's GPU config uses
  `visionBackend = CPU` (LiteRtLocalInferenceEngine.initializeGpuWithFallback). Camera-route turn 2: 5.4–6.2 s
  (vision CPU) vs 2.3–2.4 s (vision GPU). Audio after an image still worked with vision GPU on 0.16 in the probe.
- MTP on: with vision CPU, decode is ~2x faster (decision label 0.7–1.2 s vs 1.9–2.1 s without MTP).
- **The engine itself is fast:** streaming decide-once turn 1 (exact app prompt) reaches the label at 0.7–1.4 s
  and finishes the streamed answer at 1.3–1.9 s; a separate answer turn has first token at 0.16–0.4 s.
- **The app's audio prompt is broken.** "Answer the question spoken in the attached audio. Do not merely
  transcribe it." makes Gemma 4 E2B say no audio was attached: 67/108 of the earlier Fold6 GPU "audio"
  rows (54/108 CPU), so the earlier "raw audio 1.0 s" figure was mostly fast refusals. The app's default system
  prompt makes it worse. Wordings over 16 clips (en, pt-BR, de, zh-CN; app system prompt): text-after-audio
  (current) 8/16 refusals; text-before-audio / audio-only / "asked you out loud" no refusals, but language
  drift and mishearing (pt-BR capital → "Sydney", zh joke → flowers). **"First write the user's spoken question on one
  line starting with "Q: ", then answer it on the next line."** heard 14/16 correctly (misheard: de "read
  this sign", zh "these socks" → glasses), answered in the user's language except zh capital, +0.3–0.6 s. Raw audio needs the Q: line; it is also the transcript for history/logs/web queries.
- Label-first logits without the Q: line are not good enough (host, E2B QAT, label scored right after the
  audio): AUC 0.90 without photo / 0.93 with photo; after the Q: line AUC 0.986; transcript text 0.996.

### 2026-10-23 — STT→answer vs audio→answer, model cold/warm, voice and photo windows (Fold6 GPU)
Times from window close (lead 0.5 s + speech + 1.8 s endpoint silence = 4.5–5.0 s). Cold = model unloaded,
the app's input-phase preload starts at the wake and overlaps the window (GPU load 5.6–7.8 s). Photo = 3 s
simulated transfer in parallel with the window; answer includes the photo (vision CPU, as in the app).
Medians of 4 questions (en, pt-BR × visual, plain); audio uses the Q: wording.

| Flow | Model | STT → answer: first token / done | Audio → answer: first token / done |
|---|---|---|---|
| voice | warm | 5.9 / 6.0 s | **0.8 / 1.2 s** |
| voice | cold (preload at wake) | 6.6 / 6.8 s | 4.0 / 4.6 s |
| photo | warm | 11.2 / 11.5 s | 6.2 / 7.0 s |
| photo | cold | 12.6 / 12.7 s | 7.9 / 8.6 s |

- STT costs 5.4 s after the window: OfflineQuestionTranscriber replays the whole recording in real time,
  including the lead and the 1.8 s trailing silence. STT also mistranslated (pt-BR answer in Spanish; "Sydney").
- Cold: ~2.4 s of load remains after a 4.5–5 s window, plus ~1.5 s first-request warm-up.
- Photo: the CPU vision encoder (~5.4 s) dominates; it starts only after the window, although the photo
  arrives (~3 s) before the window closes.

### 2026-10-23 — Proposed next changes (not implemented; owner to confirm)
1. Drop `enableResponseFormat` from decide-once (−2.5 s per question; the regex was never enforced).
2. Vision encoder on GPU for the GPU backend (−3 to −6 s per photo), after re-running the audio-after-image flow tests.
3. Audio questions use the Q:-line wording; one turn: "Q: …" then ⊲A⊳ + streamed answer, or ⊲B⊳ (camera).
   Expected Fold6 GPU, warm: label ~1 s, answer done ~1.5–2 s after the window closes.
4. Image-mapped wake: no decision needed; always send photo + audio in one turn (the probe answered plain
   questions correctly with an unrelated photo attached).
5. Photo flow: prefill the photo as soon as it arrives, during the rest of the window, then add the audio.
6. Endpoint: 1.8 s of silence is the largest fixed cost on every path. Start the turn speculatively after
   ~0.6–0.8 s of silence and cancel if speech resumes, or shorten the silence.
7. Cold start: start the preload earlier (glasses connected / screen on) or keep the model loaded with an idle timeout.
8. Label logits (patched AAR): no clone needed. Decode "Q: …\n⊲", read log p(A), log p(B) at the label
   position (single-pass candidate scoring already in the 0.16 patch), apply a calibrated threshold, commit
   the chosen label (one-position rollback if needed), continue. Saves only ~0.1–0.3 s (the label is ~4 tokens);
   the gain is calibration: host after-Q: threshold 102/108 visual, 103/108 plain vs greedy 35/45 visual on Fold6.

### 2026-10-23 — Load times, CPU cold/warm, step-4/5 estimates (eval/fold6-CPU-latency-audio-20261023.jsonl)
- Engine load on the Fold6 (stock E2B .litertlm, file in page cache): **CPU 0.6–1.1 s**, but weights page
  in on first use (first answer +1.3 s); **GPU 4.6–9.8 s** (typ. 6–7 s; 11–12 s on the first load after an
  install, when GPU kernels are compiled and cached); **NPU ~9 s** (8.7–11.5 s, gemma4-sm8650-port notes;
  not re-measured today).
- CPU, audio path with Q: wording, from window close: voice cold 3.7 / 4.4 s, warm 2.4 / 3.1 s; photo
  ~10.5 / 11.2 s. Cold CPU and cold GPU first tokens are similar in the voice flow (3.7 vs 4.0 s); warm GPU is 3x faster.
- Backend defaults already match the owner's policy: `LocalGenerationSettings.defaultsFor` → GPU, NPU for
  `npuOnly` packages, per-model user choice persisted. The vision encoder on CPU inside the GPU backend
  dates from ec63380 (2026-08-25) with no recorded reason.
- Step 4 (image-mapped wake: always photo + audio in one turn, no decision): saves ~0.5–1.0 s with GPU vision
  (decide 1.3–1.9 s + photo turn 2.4–2.7 s vs one turn ~2.9 s + 0.3–0.6 s Q: line), ~0.3–1.1 s with CPU vision;
  the bigger gain is that 10/45 visual questions and half the ambiguous ones no longer ignore the photo.
- Step 5 is possible on the stock 0.16 AAR: `Engine.createSession` → `Session.runPrefill(List<InputData>)` /
  `generateContentStream(...)` with `InputData.Image(bytes)` / `Audio(bytes)` / `Text`. The Session API needs the
  Gemma 4 chat template rendered by us (`Conversation.renderMessageIntoString` shows it). It hides up to the whole
  photo encode (2.3 s GPU vision; part of 5.4 s CPU vision) when the photo arrives before the window closes;
  no gain on a cold GPU load (the load outlasts the window).
- Owner decisions (2026-10-23): respect the per-model backend; GPU default; NPU when an NPU model is loaded.
  Audio is the default question input; on-device STT only for models without audio (e.g. Qwen text models).
  No earlier preload (low-RAM devices); preload stays at the wake, with the "Initializing local model" cue.

### 2026-10-23 — Label before or after the transcript; Gemma 4 audio research; processing while the user speaks
- Gemma 4 audio facts (Google audio guide, deprecated page → gemma.ai): 25 audio tokens per second, **max
  30 s per clip** (the app's recorder allows up to 120 s), mono 16 kHz. Documented tasks are ASR ("Transcribe
  the following speech segment in {LANG} into {LANG} text") and AST ("Transcribe …, then translate it into …;
  first output the transcription, then one newline, then …"). There is no documented spoken-question-answering
  prompt. Our Q:-line wording mirrors the AST template (transcribe, newline, then the task), which is why it works.
- "Please provide the audio" refusals are what Gemma 4 produces when it effectively has no audio (ESCUCHA
  benchmark: text-only Gemma 4 refuses 32–53% of audio items that way). Public reports of Gemma 4 ignoring
  audio are runtime bugs (processor drops audio, missing feature extractor, chat-level audio flag). Our LiteRT
  runtime delivers the audio (the Q: line transcribes it), so ours is a prompt problem: an instruction about
  "the attached audio", placed after the audio, without a transcription step.
- Audio encoder (gemma4_audio config): conformer, chunked attention chunk 12, left context 13, **right context
  0** → causal, streamable by design. LiteRT-LM exposes whole clips only; the stock `Session.runPrefill` lets
  us prefill audio in pieces (cut at pauses) and the photo whenever it arrives, before the window closes.
- The decision needs 4 routes, not 2: A answer, B camera, C live info/web, D phone action (the old router's
  EXECUTE_UI_TASK → local agent). Only A can be answered in one go; B, C and D must stop after the label
  (and the transcript for C/D, which need text: the search query, the agent goal).
- Label-first is what lets the app act early (photo, fetch, agent) but is less accurate zero-shot on raw audio
  (host, 2 routes: AUC 0.90–0.93 vs 0.986 after the Q: line). Proposed: label first → transcript → answer, act
  immediately only on cheap, reversible routes (B: start the photo; discard it if the post-transcript check says
  A), and confirm C/D after the transcript. Long questions: the Q: line costs ~25–30 tok/s on Fold6 GPU+MTP
  (a 20 s question ≈ 2.5 s); for A it can be skipped once a direct-answer wording is reliable.
- The dataset has no phone-action class yet (351 rows: visual 108, plain 90, web 81, time 36, ambiguous 36).

### 2026-10-23 — Prompt layouts without rewriting the question, GPU vs NPU (QuestionPromptProbeTest)
New androidTest `hil/QuestionPromptProbeTest` (Engine direct, fresh conversation per question, greedy, 64 tokens)
and grader `tools/benchmarks/grade_question_prompts.py`. Build: LiteRT-LM 0.18 + NPU AAR (one build for both
backends). GPU = stock gemma-4-E2B-it.litertlm (vision CPU + MTP, as the app); NPU = our multimodal SM8650 package
(work_e2b_general_mm, vision GPU, audio CPU), **first on-phone run of its audio/image path**. Results:
eval/fold6-qp-*-20261023.jsonl. New fixtures: 55 phone-action/not-addressed rows × stock voices, en/pt-BR/es/de/zh-CN
(training/glasses_questions_v1/questions_action_ignore_draft.jsonl; audio /mnt/seagate/ML/cyanbridge-glasses-actions-ignore-20261023).
The decision LoRA's data (Laya/Needle: phone_ui_synthetic_v1, artemis_real_v1, tasker_real_v1) was phone automation
as written menus (next UI/control step among 10–12 options), no audio and no "question vs phone task" routing.

Variants: base (app today), qline (Q: rewrite), g (system prompt describes the glasses: the user speaks, a photo may
come along, reply in their language, never repeat their words; user turn = audio only), gtag ("Voice message:" + audio),
route (g + ⊲A–E⊳ labels first), route2 (g + [answer]/[camera]/[search]/[phone]/[ignore] + one example each).
16 clips (4 langs × visual/plain/action/ignore), photo runs on visual/plain; route2 on 44 clips incl. web/time.

| | GPU refusals | GPU reply language | GPU done | NPU refusals | NPU reply language | NPU done |
|---|---|---|---|---|---|---|
| base | 3/16 | 2/8 | 1.0 s | 11/16 | 2/8 | 1.3 s |
| qline | 0 (always rewrites) | 5/8 | 1.36 s | 0 (rewrites) | 5/8 | 1.5 s |
| **g** | **0** | **6/8** | **0.79 s** | **0** | **7/8** | 1.25 s |
| photo + audio, g | facts 3/4 | | 9.4 s (CPU vision) | facts 1/4 | | **3.2 s** (GPU vision) |

- The glasses system prompt + bare audio removes both the rewrite and the "no audio" refusals, and saves
  ~0.5 s vs the Q: line. zh replies tend to English; the Q: line still hears zh best.
- Without a decision, g is unsafe: voice-only visual questions get an invented colour ("blue and white
  striped"), phone actions are faked ("Okay, I'm opening Spotify…"), and speech to other people is answered.
- Zero-shot label-first routing: ⊲A–E⊳ letters are mangled (GPU 3/16, NPU 1/16; NPU stops after "⊲C").
  Bracket words are better: GPU 29/44 (web 8/8, action 7/8, plain 8/8, time 3/4, visual→camera 3/8,
  ignore 0/8), NPU 20/44. The model often appends a second tag after its answer ("[answer] … [camera]").
- The NPU package hears and knows less than Google's GPU bundle (pt-BR song → "Aquarela", "capital is the
  Australian Capital Territory", zh hallucinations). Needs a quality pass before it serves audio questions.
- Speed: NPU load 7.5–8.3 s; GPU load 12–16 s on 0.18 right after install. Photo questions: NPU 2.9 s first
  token (vision on GPU) vs GPU backend 9.2 s (vision on CPU; 5.4 s on 0.16).

### 2026-10-23 — Implemented: spoken questions as bare audio, audio-first input, GPU photos on GPU (uncommitted)
Owner request: "glasses system prompt with bare audio (or photo plus audio) for every audio question" and "GPU
runtime fully on GPU, no CPU handling photos when GPU is selected"; earlier decision: audio default, STT only
for models that cannot hear.
- `ImageQuestionDefaults.spokenQuestionInstruction(lang, allowQuestionEcho)`: glasses voice-assistant system text
  (recording of their voice, maybe a photo; reply in the language they spoke, else the app language; never repeat
  their words, except in the decide-once routing turn; use the photo only when they refer to what they see).
- `MainActivity.runChosenProviderQuery`: with audio, that instruction replaces "Answer only in <app language>";
  every audio call site (classic voice, photo + audio, decide-once, its fallback) now sends userPrompt "".
- `LocalModelsProvider`: keeps an empty user message for media requests, so the attachments are the whole user
  turn; `LiteRtLocalInferenceEngine` adds no text part for an empty prompt and joins the decide-once routing text
  without a leading blank prompt.
- Audio first: `processRecordedQuestion(transcribe = null)` uses on-device STT only when
  `spokenQuestionAudioGoesToModel()` is false (assistant route not LOCAL, remote OpenAI server active, or no installed
  model that hears audio: `LocalModelsProvider.hasLocalAudioModel` = LiteRT package with an audio encoder (0.16
  cannot report modalities, so LiteRT packages count) or a Gemma 3n/4 GGUF with a projector).
- GPU backend: `initializeGpuWithFallback` now uses `visionBackend = Backend.GPU()` (audio stays on CPU threads).
  If GPU media init fails, the existing fallback is GPU text-only (no CPU photo path).
- Tests: new `SpokenQuestionInstructionTest`, `LocalModelsProviderTest.spokenQuestionUserTurnIsTheAttachmentAlone`;
  QuestionPathBenchmarkTest/QuestionLatencyQuickTest audio paths now send the app's layout. 718/718 unit tests.
- Build gotcha: after switching `-PliteRtLmVersion`/NPU builds in the fast build root, KSP regenerated Room's
  `AppDatabase_Impl.kt` but incremental Kotlin compile left `AppDatabase_Impl.class` out (2 Robolectric failures;
  the NPU test APK installed on the Fold6 lacked it). Fix: `:app:compileDebugKotlin --rerun`; check the APK with
  `unzip -p app-debug.apk 'classes*.dex' | grep -c -a AppDatabase_Impl` (> 0).
- Known gap (2026-10-24): audio-first skips STT, so Classic phone tasks ("open Spotify and play …") no longer
  reach the agent (only the transcript router sends EXECUTE_UI_TASK) and the model fakes the action. A
  keep-STT-while-automation-is-on patch was reverted at the owner's request: the decision model must call the
  local agent (and Live) from the audio instead; STT stays a fallback only.
- Why LiteRT-LM 0.18 exists in the build (opt-in): the NPU (`Backend.NPU`, QAIRT 2.50 via LiteRT 2.3.0 dispatch;
  0.18 pins LiteRT 26895c9f = QAIRT 2.50), plus `EmbeddingEngine`/`ModelInfo` (modality probe). The default stays
  0.16 because the NPU AAR is opt-in (Qualcomm binaries not committed) and 0.18 needs the audio-before-image priming
  and Google's chat template.
- Audio encoder on GPU: Google's E2B bundle tags `tf_lite_audio_encoder_hw` CPU-only (port worklog: audioBackend
  CPU "required by the section tag"). Probe switch added: QuestionPromptProbeTest `-e qp_audio GPU`.
- NPU quality: the multimodal package (78e120e4) wraps the pre-SRQ base; the NPU session is repacking it on the
  srq-a16-4k base (2f2e0e64) → ~/nvme_tmp/gemma4-sm8650-work/work_srq_a16_4k_mm/, and checking media-embedding
  calibration and GPU audio.
- Pro relay: `/audio-query` already sends real audio (OpenRouter `input_audio`, capability "audio" candidates) and
  `CliRelayClient.audioQuery` exists, but the glasses flows never use it for Pro (STT → text); the app parses only
  `supports_vision` from /models; `/image-query` takes no audio, so photo + audio would need a server change.

### 2026-10-24 — Pro relay hears spoken questions; website main synced (website repo)
- Website (`~/Documents/CyanBridgeLabs/Cyanbridge_website`): production had been CLI-deployed (2026-10-04) from an
  uncommitted working tree; origin/main lagged 150 files. Verified prod's file list (545/547 identical to the working
  tree, 2 newer docs), committed it as d2addd1 "Sync main with the production deployment", then 1a1c3d6 "Accept
  glasses spoken questions as bare audio in the relay", pushed to origin/main, deployed with `vercel deploy --prod`
  (cyanbridge-pkamfbq7r, aliases cyanbridge.vercel.app / cyanbridgelabs.com). No CI run or git-triggered deploy fired.
  - `/audio-query`, `/image-query`: `spoken_question` (media is the whole user turn), `system_context` (appended to the
    system prompt); `/image-query` takes optional `audioBase64` (audio-capable candidates). `/models`: `supports_audio`,
    `cheap_router_supports_audio`. lib/spoken-question.ts + 4 tests; cloud-model tests 20/20.
- App: `ProSubscriptionRelayClient` parses `supports_audio` and caches audio-capable ids
  (`ProSubscriptionAiPrefs.questionsModelHearsAudio`, refreshed daily at question start). With the PRO route and an
  audio-capable (non-Live) questions model, `spokenQuestionAudioGoesToModel()` skips STT and the recording goes to
  `/audio-query` (or photo + audio to `/image-query`) with the spoken-question system context. Unknown catalog → STT.
  720/720 unit tests.
- NPU session (2026-10-24): rebuilt E2B image+audio on srq-a16-4k: work_srq_a16_4k_mm (sha256 dfe5d286…) and
  work_srq_a16_4k_mmfix (a9cb6605…, main embeddings input range ±8.59 → ±20; text-only calibration clipped ~40% of
  audio soft tokens, but host answers were unchanged). The SRQ base is the expected fix. Audio on GPU: LiteRT-LM
  has a GPU audio path, but Google tags the audio encoder/adapter sections "cpu" ("Audio backend constraint
  mismatch" with audioBackend=GPU, also on the stock bundle); a retagged "cpu,gpu" test bundle was requested.
  Phone plan: NPU session runs the NPU A/B + audio-GPU probe when the Fold6 reconnects, then reinstalls the 0.16 build.
- Audio-on-GPU test bundle: /mnt/seagate/ML/cyanbridge-litert-npu-20261008/audiogpu-20261024/gemma-4-E2B-it_audiogpu.litertlm
  (sha256 18fdcf20…, stock sections byte-identical, audio encoder/adapter retagged "cpu,gpu";
  recipe/retag_backend_constraint.py). The NPU session's Fold6 queue (NPU A/B on both SRQ packages, route2 on each,
  GPU audio CPU vs GPU) starts when the phone reconnects; results → eval/fold6-qp-<tag>-20261024.jsonl. Afterwards this
  session reinstalls the regular 0.16 build (current arm64 app-debug.apk, signed with the debug keystore).

### 2026-10-24 — Implemented: spoken decision model (tags) as the default for voice questions (uncommitted)
Owner approved the design: the decision model (not STT + transcript router) routes voice questions,
including to the local agent and Live; STT is fallback only. Implementation:
- `ai/router/SpokenDecision.kt`: `SpokenRoute` (ANSWER, CAMERA, SEARCH, PHONE, LIVE, IGNORE) and
  `SpokenDecisionParser` (leading `[tag]`, one-line payload for phone/search, stray-tag cleaning,
  `isComplete` for early stop). Tests: SpokenDecisionParserTest.
- `ImageQuestionDefaults.SPOKEN_DECISION_ROUTES` (= host v2 prompt) and `NO_WEB_ACCESS_INSTRUCTION`.
- `MainActivity.triggerSpokenDecisionQuestion()`: records once, `runChosenProviderQuery(decisionRoutes =
  true)` on the local model or Pro relay, cancels generation once a non-answer route is written, then:
  answer → speak; camera → photo + the same recording answered again with it; phone →
  `startLocalAgentFromVoice(task)` (shared with the Classic EXECUTE_UI_TASK branch); live →
  `launchGeminiLiveQuestion`; search → answered with the no-web note; ignore → silence. Test hook event
  `spoken_decision`.
- `handleAiWakeWordActivation`: voice route + mode ≠ CLASSIC + `spokenQuestionAudioGoesToModel()` → the
  spoken decision; the photo-mapped wake keeps the photo flow. `QuestionRoutingMode.fromRaw` defaults to
  SMART; the old `triggerDecideOnceQuestion` / `selectedLocalModelSupportsDecideOnce` were removed.
  Settings labels updated in 9 languages.
- Fixtures: `questions_live_draft.jsonl` (25 "start Live" rows, 5 langs; audio
  /mnt/seagate/ML/cyanbridge-glasses-live-20261024). androidTest assets + manifest gained
  en_play_song / en_talk_to_other (piper). GlassesQuestionFlowTest SMART cases: camera, answer, phone
  (automation forced off → "Enable Local Agent…"), ignore (strict only).
- Host evaluator `tools/benchmarks/eval_spoken_route_tags.py` (greedy tag + tag probabilities).
- Tests: app 726/726, shared 88/88.
- Host tag eval (E2B QAT bf16, transformers, audio only, 3 rows per lang×class; eval/host-e2b-qat-spoken-tags-v1v2-20261024.jsonl):

  | prompt | visual→camera | plain | time | web→search | action→phone | live | ignore | total |
  |---|---|---|---|---|---|---|---|---|
  | v1 (route2 + live) | 12/27 | 25/27 | 11/18 | 26/27 | 10/15 | 5/15 | 0/15 | 89/144 |
  | v2 (= app SPOKEN_DECISION_ROUTES) | 25/27 | 24/27 | 14/18 | 24/27 | 11/15 | 6/15 | 5/15 | 109/144 |

  v2 misses: "Open Gemini Live" → [phone] in all 5 langs; phone-call talk / "never mind" answered; zh 9/20.
  Greedy and tag-probability argmax agree within 2 rows, so a threshold would not change much.
- Emulator (CyanBridge_LiteRT_CI, CPU), GlassesQuestionFlowTest SMART cases: camera (photo, "yellow"), answer
  ("Canberra"), phone (reached the agent gate), ignore (kokoro clip) pass. The piper en_talk_to_other clip
  intermittently fails the AVD audio encoder (status 13, audio_litert_compiled_model_executor.cc:817, retry fails
  too) — recheck on the Fold6. Decision turns that fail now throw instead of being spoken.
- v3 prompt (explicit "Gemini Live is [live], never [phone]", ignore = talk to another person or on a call, examples
  phrased unlike the fixtures): host **118/144** (visual 24/27, plain 24/27, time 14/18, web 24/27, phone 12/15, live
  10/15, ignore 10/15; zh-CN 8/20, other langs 9/11–19/20). Now the app's SPOKEN_DECISION_ROUTES. Parser also maps
  `[phone] … Gemini Live / live mode` → LIVE and accepts a payload-less tag after a stray prefix ("] [camera]",
  quoted line + "[ignore]"). eval/host-e2b-qat-spoken-tags-v3-20261024.jsonl. Unit tests 728/728.
- Emulator (CyanBridge_LiteRT_CI, 16 KB pages, CPU), GlassesQuestionFlowTest with the v3 prompt, one test per process:
  all four SMART routes pass (camera: photo + "yellow"; answer: "Canberra"; phone: agent gate; ignore: silent),
  and the classic tests pass when the process's audio encoder works.
- AVD audio-encoder failure root cause: in about half of the processes LiteRT rebuilds the main XNNPack weight cache
  (the cache key flips between …_1785036911_… and …_1785036912_…); that build logs `mmap_handle.cc:173 remap
  failed: Bad address` and the audio encoder then fails every request in the process (status 13,
  audio_litert_compiled_model_executor.cc:817), even in a reloaded engine. Reloading without the weight cache works
  but lmkd killed the app at 4.9 GB RSS, so the app keeps one delayed retry and then fails cleanly ("I couldn't
  process that question"; decision turns never speak model errors). Not seen on the Fold6 (4 KB pages); recheck there.
- Signed arm64 APKs for the Fold6 (debug keystore, AppDatabase_Impl present):
  scratchpad/fold6/app-debug-v5-debugkey.apk + app-debug-androidTest-v5-debugkey.apk. App 728/728, shared 88/88.

## 2026-10-22 — Plan: decide-once question flow, "Initializing local model" cue, glasses-question data

Findings (read-only investigation, this session):
- Decision LoRA cannot share the answer model's cache: every decision-LoRA artifact is its own
  2.4–2.8 GB bundle (CPU/GPU merged; NPU LoRA compiled into the graph). LiteRT-LM rejects a main-LLM
  LoRA at runtime ("Lora is not supported"), and the LoRA adapts k/v projections, so a prefix
  prefilled without it is not what it was trained on (and vice versa). Every decision and answer
  today also starts a fresh session (no KV reuse; image follow-ups re-prefill the image).
  Native LiteRT-LM has SessionAdvanced::Clone, rewind and a media-aware CachedSession, not exposed in
  Kotlin/JNI; the patched Engine.scoreDecision always creates a fresh text-only session.
- Decision data has no ordinary glasses questions: bounded-v3 has 9 English image phrasings (all name
  image/photo/picture/camera) and 9 English science voice questions, wakeRoute is always "voice", no
  photo-already-taken state, no implicit visual ("these socks", "this sign", price, ripeness) or
  plain factual questions, no other languages. App 4-way menu != trained v3 menu. Router regex sends
  "what color…" to ANSWER_QUESTION.
- No end-to-end glasses question-flow test exists; AudioQuestionRecorder is created inline (no seam).
  No glasses-question audio fixtures exist. Real-model tests are manual (LocalMultimodalProviderTest).
- Local model cold load: LocalChatSessionManager.ensureModelLoaded (~:184); snapshot() blocks during
  load; media requests may pick a different model than the selected one; speak() uses QUEUE_FLUSH.

Target behaviour (owner, 2026-10-22):
- One flow for both wake mappings: record the question once (image-mapped: photo in parallel, as now)
  → decide → answer, never re-asking.
  - image-mapped + "what color are these socks?" → use the photo already taken + same question.
  - image-mapped + plain question → voice answer (skip image prefill).
  - voice-mapped + visual question → take the photo now, reuse the same audio.
  - voice-mapped + plain question → voice answer.
- Bench transcribe-first vs audio-straight before choosing (Fold6, available 2026-10-23).
- Speak "Initializing local model" only when a question/decision triggers a cold load; never when warm.

Decision options to bench (no LoRA, so the prefix can be shared):
1. Shared-prefix label scoring: prefill [system, image?, audio, question] once, clone the session,
   append a short menu, score labels, answer on the original session (needs JNI patch: session clone +
   scoring on an existing session with media).
2. Tool call: one conversation; the model answers or calls look_with_camera; photo returned as the tool
   result in the same conversation (verify LiteRT-LM tool support).
Later: decision-only (aLoRA-style) adapter, or decision training merged into the answer model.

Work order:
1. Test seam (injectable question audio + photo source) and fixtures: local Piper/Kokoro stock voices
   (never voice-clone outputs), 9 app languages, visual vs plain vs ambiguous questions; public images.
2. Emulator flow tests (wake → record → decide → photo → answer) with stock gemma-4-E2B-it.litertlm.
3. "Initializing local model" cold-load cue (localized, once, after recording, sequenced with TTS).
4. Extend decision data/eval: implicit visual vs plain vs ambiguous, wakeRoute + photoAvailable state,
   9 languages, audio versions.
5. Fold6 benchmark (latency to decision / first spoken word, accuracy, STT availability per language),
   then rebuild SMART on the winning path.

### 2026-10-22 (later) — LiteRT-LM findings that change the decision plan
- A LiteRT-LM `Conversation` keeps its KV across `sendMessage` turns and prefills only the new turn
  (conversation.cc renders history and prefills the diff; only the new message's media are processed).
  The app closes the Conversation after every call (LiteRtLocalInferenceEngine.kt:342-363), so it never
  benefits. `ConversationConfig(initialMessages=[user(audio+question)], prefillPrefaceOnInit=true)`
  prefills media without decoding.
- No-patch decide-once candidate: one Conversation, prefill [system, audio(+image if already taken),
  question]; the reply is constrained (`enableResponseFormat` + `ResponseFormat.regex`, llguidance in the
  stock .so) to start with ⊲A⊳ (answer, continue) or ⊲B⊳ (needs camera, stop). On B, send
  user(image + short text) in the same Conversation — audio is not re-prefilled and nobody is re-asked.
  With the app's greedy sampling (temperature 0, topK 1) the token is the argmax choice (no calibrated
  probability). Untested on device.
- Tool calling works in both AARs (Gemma 4 template has native tool tokens), but a tool response is
  JSON/text only — an image in a tool message does not render with the stock template.
- Exact label scores on the shared prefix need a runtime + JNI patch (~250–350 lines): Conversation-level
  clone + candidate scoring. Clone is not implemented in the NPU executor (0.16 source).
- Local 0.16 LiteRT-LM tree has a stale partial patch applied; reset before `build_aar.sh`.

### 2026-10-22 (later) — Emulator flow harness
- New debug-only seam `GlassesQuestionTestHooks` (fixture question WAV in place of the mic, fixture JPEG in
  place of the glasses photo, flow events) + `MainActivity.debugGlassesEvent("wake"|"photo_button")`.
- New `GlassesQuestionFlowTest` (hil): photo button, classic wake on image/voice routes, cold-load cue,
  and opt-in decide-once target tests (`-e gq_target true`).
- "Initializing local model" cue: `LocalModelLoadAnnouncer` (cold loads only, once per question, queued).
- Pixel 9a AVD, Gemma 4 E2B Q4_0 GGUF + mmproj (llama.cpp; the AVD lacks space for the 2.6 GB .litertlm):
  photo button → image + transcript reached the model (95 s on CPU); cold load spoke the cue once, warm
  questions stayed silent; the router's single-token decision alone took ~23 s on the AVD CPU.
  On-device STT worked on this AVD for the English fixture.
- Found: `binding.root` is never attached (MainActivity uses Compose setContent), so the existing
  `EXTRA_START_META_IMAGE_QUESTION` handler's `binding.root.post` likely never runs (not fixed yet).

### Fold6 runbook (2026-10-23) — question-path benchmark
Build arm64 (default ABI) and install keeping data:
```bash
JAVA_HOME=/opt/android-studio/jbr bash tools/hil/build_debug_fast.sh :app:assembleDebug :app:assembleDebugAndroidTest
B=~/.cache/cyanbridge/build-*/CyanBridgeManagerApp/app/outputs/apk
cd android/CyanBridge && scripts/install_debug_keep_data.sh --serial RQCX700KSDF --apk $B/debug/app-debug.apk
scripts/install_debug_keep_data.sh --serial RQCX700KSDF --apk $B/androidTest/debug/app-debug-androidTest.apk
```
Fixtures (synthetic stock-voice questions only) + stock model:
```bash
S="adb -s RQCX700KSDF"; $S shell mkdir -p /data/local/tmp/gq /data/local/tmp/mm
D=/mnt/seagate/ML/cyanbridge-glasses-questions-20261022
$S push $D/audio $D/images $D/manifest.json android/CyanBridge/training/glasses_questions_v1/questions.jsonl /data/local/tmp/gq/
$S push /mnt/seagate/ML/gemma4-sm8650-port/downloads/gemma4/gemma-4-E2B-it.litertlm /data/local/tmp/mm/
```
Benchmark (repeat with -e qb_backend GPU; NPU needs the opt-in NPU build + catalog id):
```bash
$S shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.QuestionPathBenchmarkTest \
  -e qb_model /data/local/tmp/mm/gemma-4-E2B-it.litertlm -e qb_fixtures /data/local/tmp/gq \
  -e qb_questions /data/local/tmp/gq/questions.jsonl -e qb_splits dev,test -e qb_backend CPU \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
$S pull /sdcard/Android/data/com.fersaiyan.cyanbridge/files/question-bench.jsonl
```
End-to-end flow on the phone (fixture hooks, no glasses needed):
```bash
$S shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.GlassesQuestionFlowTest \
  -e gq_model /data/local/tmp/mm/gemma-4-E2B-it.litertlm -e gq_strict true -e gq_target true \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
```
Both tests snapshot/restore the model registry and settings prefs; the flow test also restores routing,
wake-word, provider and onboarding prefs. They do not delete model files.

## 2026-10-22 — Decision-model question routing flag + voice-profile foundations (uncommitted)

- Merged the local `jev-like-local-agent` (`989b69d`: NPU runtime, speaker models, shared-Gemma
  decisions) into this branch as `448df1e`. The merge was clean and compiles. Local `jev-like-local-agent` is 66 commits ahead of
  `origin/jev-like-local-agent` (not pushed).
- Question routing flag (`QuestionRoutingPrefs`, Settings → Question routing): CLASSIC (default,
  unchanged), SHADOW (debug, logs `QUESTION_DECISION_SHADOW`), SMART (beta: hear the question first,
  router decides voice/camera; LOCAL engine = Fallback(Thresholded(loaded-model logits), SingleToken)).
  Tasker, phone-assistant and Gemini Live routes stay classic. No transcript on the image wake route
  → photo + recorded audio handed to the image flow.
- Voice profile: shared `VoiceEnrollmentScreen` (commonMain, stable Compose only), onboarding step 4/5
  and Settings → Voice profile. Three localized phrases (9 languages), consent, quality + leave-one-out
  checks, pending/commit storage in `noBackupFilesDir/voiceprint`, ECAPA default (WeSpeaker int8
  cataloged), model installed by staging + SHA-256. Non-CLASSIC modes log `SPEAKER_OBSERVE` scores
  only — no gating. Clear local data deletes the profile.
- Design/next steps: `android/CyanBridge/docs/QUESTION_ROUTING_AND_VOICE_PROFILE.md`.
- Verification: 700/700 app unit tests (14 new), 84/84 shared portability tests, x86_64 debug APK.
  Pixel 9a AVD: onboarding shows 5 steps; Settings sections render; enrollment screen, listening
  state and no-speech retry verified (AVD mic is silent). Not yet run: a real three-phrase enrollment,
  template build with the ECAPA model on a phone, SMART routing on glasses.

## 2026-10-10 — Merge with protocol fixes; NPU downloads, multimodal NPU packages, image/audio on the emulator

- **Commits and merge:**
  - Committed the main checkout's 2–4 Oct work on `heycyan-eyevue-tunebuds-protocol-fixes` (`01035cb`) and the NPU/local-agent work on `jev-like-local-agent` (`e74cc98`).
  - Merged them in `c05001e`, so `main` can fast-forward.
  - `llama-runtime` was added on both branches: the protocol-fixes `cyan_llama` (llama.cpp + libmtmd) is kept, and the shared-Gemma bridge becomes an opt-in second library (`-PsharedGemmaPrototype=true`).
  - Unit tests 938/938 once the emulator holding port 8554 was closed.
- **NPU model downloadable and selectable** (catalog `gemma4-e2b-it-npu`):
  - New public repo [`Fertroll10/gemma-4-E2B-it-litert-lm-npu`](https://huggingface.co/Fertroll10/gemma-4-E2B-it-litert-lm-npu) @ `947dafae`: the validated 4k SM8650 text package (sha256 `70c0223b…`) with a model card.
  - Google's SM8750 build (`litert-community` @ `b3ca0d2f`) is also listed.
  - Variants are now a list of sources with per-SoC sha256/size and a pinned commit.
  - NPU-only entries default to LiteRT on the NPU, and SoC-compiled packages never fall back to GPU.
  - The runtime AAR adds Hexagon V79 (`…-v75-v79.aar`, `524078fb…`).
  - `NpuCatalogSettingsTest` passes on the emulator (entry listed, disabled, reason shown). Downloading on a phone is pending.
- **The NPU packages were text-only; fixed** (subagent, recipe `add_multimodal_sections.py`):
  - E2B and E4B 4k packages now carry Google's vision/audio encoders and adapters next to the unchanged NPU main: `work_e2b_general_mm` (`78e120e4…`) and `work_e4b_general_mm` (`0fd6913e…`).
  - Contract checks and a host CPU check pass: bear / stop sign / living room described; "Hello, I like red flowers."
  - Not on a phone yet. The HF file will be replaced after the Fold6 check.
- **Image + audio on the Pixel 9a AVD** (`LocalMultimodalProviderTest`, through `LocalModelsProvider.streamChat`):
  - llama.cpp Gemma 4 E2B Q4_0 + mmproj: "…an electric bus." / The speaker says, "I like red flowers."
  - LiteRT Gemma 4 E2B CPU: "…a public transit bus." / same audio answer.
- **Bugs fixed on the way:**
  1. Imported Gemma 4 GGUFs were prompted in ChatML. Added the `gemma4_it` template and file-name inference.
  2. LiteRT media requests put the system prompt after the attachment, so the model said "I need the recording". It now goes to `systemInstruction`.
  3. LiteRT-LM 0.18 audio fails after an image in the same engine (XNNPack prepare). The audio encoder is now run once on silence before the first image (7.6 s once on the emulator).
- **Notes:**
  - Upstream `llama-mtmd-cli` at the same pin transcribes the clip, which confirms the bridge.
  - E2B with the terse app system prompt refuses a literal "transcribe exactly".
  - The 2.8 GB `e2b-base-wi4b32.litertlm` emulator copy was removed for space (identical sha to the Seagate archive).
- **Next (Fold6):** `NpuCatalogSettingsTest -e npu_download true`, then `LocalMultimodalProviderTest` with `mm_backend NPU` on the text and `_mm` packages, and stock E2B GPU image→audio. APKs: `/mnt/seagate/ML/cyanbridge-litert-npu-20261008/apks-arm64-mm-20261010/`. Details: `android/CyanBridge/docs/NPU_ON_DEVICE.md`.

## 2026-10-08 (afternoon) — Gemma 4 on the Fold6 NPU, context length study, per-SoC downloads

- **Gemma 4 LLMs on the NPU** (packages from the recipe session: QAT, QAIRT 2.50, weight sharing; LiteRT-LM 0.18 `Backend.NPU`; stock E2B for CPU/GPU on the same phone):
  - **E2B:** prefill 2,250–2,900 tok/s, decode 32–35 tok/s, init 6–8 s, PSS 1.31 GB. GPU: 540–1,050 / 14–16 (init 13–16 s). CPU: 110–205 / 11–19.
  - **E4B:** 585–622 / 11.6 tok/s, init 25–31 s, PSS 2.43 GB.
  - **Decision LoRA** (retrained on the QAT base) with annotated prompts: **118/120**, 0 control false actions, **1.34 s p50**. Our earlier INT4 LoRA exports scored 109 (15.2 s, CPU) and 110 (4.6 s, GPU).
- **Three blockers found on the phone, all fixed by the recipe session:**
  - **Chat template:** LiteRT-LM 0.18 rejects HF's newer Gemma template ("map has no method named get"); packages now carry Google's template.
  - **One NPU process domain per app:** E4B's 3.77 GiB estimate failed with `Failed to find available PD`, and extra PDs are `Permission denied` for apps. Weight sharing brought E4B to 2.26 GB.
  - **INT2 per-layer embedding tables:** the NPU's hardware lookup only accepts INT4/INT8 (`INVALID_ARGUMENT` in prefill); widened losslessly to INT4.
- **Context length** (the user asked whether 4k is a Qualcomm limit): it isn't. Research agent: Qualcomm's 2026 FAQ supports 8192, and LiteRT-LM added NPU multi-cache buckets in Aug–Sep 2026. E2B 8k/16k/32k all compile and load, but speed falls with the compiled cache:

  | Cache | Prefill tok/s | Decode tok/s | Init |
  |---|---:|---:|---:|
  | 8k | 1,110–1,250 | 15–16 | 12–18 s |
  | 16k | 560–660 | 11.5–12 | 21–27 s |
  | 32k | 113–116 | 4.7–4.8 | 42–70 s |

  - 32k is slower than CPU; 16k is the practical limit.
  - The 4k+16k **multi-bucket** package keeps short chats at 30 tok/s decode and switches buckets past 4,096 tokens (`CopyKVCache` 87–91 ms); init is 27–36 s.
- **Limitation:** the long-context feasibility builds use 4k calibration and **degenerate past ~4k** in the new `LiteRtLongContextProbeTest` (multi-turn, greedy). Long-calibrated builds (`_lc`, 8k ready) and a held-out needle set (3.5k–30k) are waiting for the next phone session (`#needleEval`).
- **Host follow-up (recipe session):** the cause is calibration, not the HTP. The FP32 16k export matches transformers' QAT text at 3.5k/7.5k/15k tokens; quantized A16 16k with 4k calibration degenerates on the host CPU exactly like the device; 16k long calibration is coherent but misses a needle FP32 finds. E2B misses 3/5 needles even in FP32, so device needle runs are scored by agreement with `work/needle_eval/host/hf_ref.log`. Next phone package: `work_e2b_mb4k16k_lc` (sha256 98ca4d916aa27354…, both buckets on 16k long calibration). The 32k long build is cancelled.
- **Other device results:**
  - EmbeddingGemma 2 740M on NPU: 8 ms per embedding, 125 ms per decision (CPU 60 / 1,144 ms), 47/120 on both.
  - YOLO11n 90 s soak: NPU steady at 11–14.5 ms with a flat skin temperature; CPU slows from 41 to 58 ms as it heats.
- **Per-SoC downloads (user plan: compile for all recent Snapdragons and host on a public Hugging Face repo):**
  - Shared `NpuVariantSet` / `NpuVariantResolver` resolve `<repo>/resolve/main/<prefix>_Qualcomm_<SoC>.<format>` (unit-tested).
  - The app's `NpuCatalogVariants` gates downloads on the NPU build and the phone's SoC, with a reason shown otherwise.
  - The NPU "coming soon" entry stays disabled until the repo exists.
- **Tests:** `LiteRtBackendBenchmarkTest` (new NPU option), `LiteRtBoundedDecisionEvalTest` (NPU), `LiteRtEmbeddingRuntimeTest` (740M), `LiteRtLongContextProbeTest` (new), and `WalkingAidNpuDetectorTest` soak mode (new). Shared `NpuVariantResolverTest` 3/3. The app compiles with the decision AAR plus the NPU AAR.
- **Coordination:** the Gemma recipe belongs to another session (`/mnt/seagate/ML/gemma4-sm8650-port`); its packages were used read-only. Long host jobs ran under `nice` at the request of a third session sharing the CPU.
- **Phone state:** the CyanBridge debug build with the decision and NPU runtime AARs is installed (`install -r`).
  - Test packages are in the app's external `files/litert-eval/` (multi-GB): E2B 4k/8k/16k/mb4k16k, LoRA, E4B ple4, EmbeddingGemma 2 270M/740M.
  - Walking-aid and speaker fixtures are in `/data/local/tmp/npu`.

## 2026-10-08 — First NPU runs on the Fold6: speaker, Walking Aid YOLO, EmbeddingGemma 2

- **Opt-in NPU build on the Galaxy Z Fold6 (SM8650, Android 15, `install -r`, data kept).** Results:

  | Model | NPU | CPU | Speedup | Accuracy |
  |---|---:|---:|---:|---|
  | WeSpeaker | 12.3 ms | 226 ms | 18× | parity cos ≥ 0.99993 |
  | YOLO11n | 10.4–11.4 ms | 47–61 ms | ≈5× | COCO-200 mAP 0.297 vs CPU 0.290 |
  | YOLO-World | 74 ms | 1,650 ms | 22× | mAP 0.508 vs 0.507 |
  | EmbeddingGemma 2 text | 8 ms/embedding, 123 ms/decision | 1,320 ms/decision | ≈11× | dev 47/120 both, same choice 107/120 |

  Google's EmbeddingGemma 2 bundle is a **QAIRT 2.49 context running on our 2.50 runtime**, so one runtime covers everything.
- **Walking Aid on the NPU:**
  - Both detectors compile fully for SM8650 (YOLO11n 369/369 ops, YOLO-World 487/487, one partition each).
  - `LiteRtVisionBackend` now runs `<detector>_Qualcomm_<SoC>.tflite` via `LiteRtNpuModelFactory` (CompiledModel) when the NPU backend is selected, keeping the Interpreter as metadata and fallback.
  - `WalkingAidNpuBackendTest` passes end to end on the Fold6 (NPU = host emulation 1.0/1.0; 11 ms vs 46 ms per frame).
- **Two existing Walking Aid bugs fixed (all backends):**
  - YOLO-World's catalog export outputs undecoded anchor distances, so its boxes collapsed to the top-left corner. Now decoded; CPU mAP 0.51.
  - YOLO11n's unquantized uint8 `class_idx` was multiplied by scale 0, so every label was "person".
- **Testing policy (user):** NPU tests run on the Fold6 when attached. The device dumps raw outputs (`-e yolo_dump_dir`) and `yolo_htp_parity.py --npu-raws` scores them. 200 frames take 8 s / 65 s on the phone vs about 12 min / 6–7 h in the x86 emulator. The phone matched the emulator exactly (YOLO11n 16-image detections and 200-image COCO metrics), so the emulator is the no-phone fallback.
- **Tests run:**
  - Fold6: `LiteRtNpuSpeakerTest` 2/2, `WalkingAidNpuDetectorTest` 2/2 plus two 200-image dump runs, `WalkingAidNpuBackendTest` 1/1, `LiteRtEmbeddingRuntimeTest` NPU and CPU with the dev set.
  - AVD: CPU parts, `WalkingAidVisionStackIntegrationTest`, `AudioSpeakerLiteRtProbeTest`.
  - Unit: 548/548 on 0.16 and 0.18+NPU.
- **New tools:** `tools/litert-npu/{yolo_htp_parity.py, make_yolo_fixture.py}`. Artifacts: `/mnt/seagate/ML/cyanbridge-walking-aid-npu-20261008/` and final APKs in `cyanbridge-litert-npu-20261008/apks-arm64/` (SHA256SUMS verified).
- **Not done yet:** `dumpsys meminfo`/thermals per backend, Gemma 4 E2B NPU package on the phone, EmbeddingGemma 2 740M audio NPU, Depth Anything on the NPU, distribution and licensing of per-SoC models.

## 2026-10-08 — One NPU runtime (QAIRT 2.50) in the app; WeSpeaker NPU graph fixed via HTP emulation

- **Single QNN runtime:** QAIRT **2.50.0.260828**.
  - LiteRT-LM 0.18 pins LiteRT `26895c9f`, which is QAIRT 2.50. LiteRT v2.3.0 is 16 commits later with no Qualcomm or dispatch changes.
  - The 2.3.0 dispatch library (`qnn_manager.cc`) rejects QNN libraries older than its headers, so the 2.47 runtime cannot work with LiteRT-LM 0.18.
  - The recipe's 2.47 was a minimum, not a pin.
- **App (opt-in, `-PlocalLiteRtNpuAarPath`, needs `-PliteRtLmVersion=0.18.0`):** `tools/litert-npu/package_npu_aar.py` builds one AAR with:
  - LiteRT 2.3.0 `CompiledModel` classes and natives (the Interpreter classes are left out, so `litert:1.4.2`'s NNAPI/GPU delegates for walking-aid stay);
  - Google Maven `litert-npu-runtime-qualcomm:2.3.0` (`libLiteRtDispatch_Qualcomm.so`, so no Bazel build);
  - QAIRT 2.50 `libQnnHtp`/`libQnnSystem`/V75 stub and skel;
  - a `uses-native-library libcdsprpc.so` declaration, which neither the app nor LiteRT-LM had.

  Gradle switches to extracted native libraries and keeps the Hexagon skel unstripped. `LiteRtNpuSupport` checks the runtime, `Build.SOC_MODEL` and the libraries before `Backend.NPU`, and rejects a `_Qualcomm_<SoC>` model meant for another SoC. Cost: 11.0 MB compressed / 27.8 MB extracted (V75 only).
- **Verified:**
  - Pixel 9a AVD (16 KB pages): `LiteRtNpuSpeakerTest` CPU `CompiledModel` parity 0.99999999 at 227 ms per 2 s; the NPU test skips cleanly ("SoC ranchu"). `AudioSpeakerLiteRtProbeTest` (Interpreter 1.4.2) passes in the same APK.
  - All 546 unit tests pass on 0.16 and 0.18+NPU.
  - arm64 APK has every library 16 KB-aligned.
- **The earlier "fp16-safe whole model on NPU" was wrong.** The QAIRT x86 HTP emulator (`htp_emulator_parity.py`) gives cosine ≈ 0.06 vs CPU on 2.47 and 2.50. The emulator itself is sound: it runs the split head at 0.999997. Stage bisection: HTP mis-computes the 400-tap stride-160 DFT conv1d.
  - `KaldiFbankNpu` (160-sample blocks, stride-1 kernel-3 conv; identical features) compiles 94/94 ops in one partition.
  - Emulated-HTP EER 0.25% = CPU over 1,200 crops; context SoC 57 / V75 / v2.50.0.260828221209.
  - Export `wespeaker_resnet34_lm_2s_npu.tflite` + `fixture-npu`, in `wespeaker-litert-20261007/npu-qairt250/`.
- **Recipe on 2.50 (`~/nvme_tmp/npu-litert230/env`, QAIRT zip downloaded by hand):** Gemma 4 E2B main graph recompiled read-only from the other agent's `main_quant_preaot.tflite`.
  - 897/897 and 1,694/1,694 ops, as with 2.47; 220 s, 9.2 GB peak RSS (2.47: 12.5 GB).
  - Context SoC 57 / V75, same two graphs, loads in the emulator.
  - In emulation the 2.50 runtime runs our 2.47 context with identical outputs.
  - Google's 2.49 EmbeddingGemma 2 contexts can't be created by the x86 emulator with 2.49 *or* 2.50 (shared weights across four graphs), so 2.49-on-2.50 compatibility is a phone check.
- **Pending (needs the Fold6):** NPU latency/parity of WeSpeaker, EmbeddingGemma 2 via `Backend.NPU` on the 2.50 runtime, the 2.50 E2B package. Artifacts: `/mnt/seagate/ML/cyanbridge-litert-npu-20261008/` (AAR, Maven inputs, arm64 APKs, SHA256SUMS). Doc: `android/CyanBridge/docs/NPU_ON_DEVICE.md`.

## 2026-10-08 — Whole-model NPU compile, app NPU requirements, Luciano cleaned

- **fp16-safe WeSpeaker front end** (`KaldiFbankFp16Safe`: DFT gain 0.5, fp16-normal mel floor, saturation, post-log offset). The single graph AOT-compiles fully for SM8650 (91/91, 1 partition); fp16 simulation EER 0.25% = fp32, 0 non-finite (the original graph: 50%, all non-finite). Embeddings differ from the CPU model (mean cos 0.97), so use one variant for enrol and verify. Artifacts in `wespeaker-litert-20261007/npu-aot-litert220/`.
- New `android/CyanBridge/docs/NPU_ON_DEVICE.md`: two runtimes (LiteRT-LM NPU vs LiteRT 2.x `CompiledModel`); missing dispatch/QNN libraries (the app's NPU option always falls back today); QAIRT version matching (ours 2.47, EmbeddingGemma 2 bundles 2.49/2.50); extracted native packaging and `ADSP_LIBRARY_PATH`; per-SoC artifacts and fallbacks; fp16 numerics rules; validation pipeline; memory; first-device-run checklist.
- **Luciano:** deleted the four backed-up project dirs (121 GB; root disk 91% → 37% used, partly from others' cleanup). The verified backup is in `/mnt/seagate/ML/luciano-cyanbridge-backup-20261007/`.

## 2026-10-08 — Speaker models on the SM8650 NPU (host-side), Luciano backup verified

- AOT-compiled for SM8650 with the NVMe `recipe_env` (LiteRT 2.2.0, QAIRT 2.47). WeSpeaker and ECAPA fp32 single graphs offload fully (88/88, 467/467), but HTP computes in fp16 and the in-graph front end overflows: GPU fp16 simulation gives 100% non-finite WeSpeaker embeddings (EER 50%). Weight-only int8 fails to compile (per-channel DEQUANTIZE); fully static W8A16 offloads 87/92 ops in 6 partitions.
- **Working layout:** fp32 front end on CPU plus the AOT ResNet head on the NPU (71/71 ops, 1 partition, 13.5 MB). fp16 simulation keeps EER at 0.25% (= fp32); the composed host graphs match at cosine 0.9999998. Artifacts are in `wespeaker-litert-20261007/npu-aot-litert220/` (SHA256SUMS). Device run pending: it needs LiteRT 2.2.0 `CompiledModel`, `libLiteRtDispatch_Qualcomm.so` (not in the pip SDK) and the QAIRT 2.47 Android libraries. EmbeddingGemma 2's SM8650 bundles use QAIRT 2.49/2.50.
- **Luciano backup:** the first pass missed files in nested folders (`--files-from` does not recurse with `-a`); re-run with `-r`. Now `VERIFY_OK` for all 885 main files and `AUDIO_VERIFY_OK` for all 3,252 audio files (23 GB) in `/mnt/seagate/ML/luciano-cyanbridge-backup-20261007/`. The Luciano directories listed for cleanup are safe to delete.

## 2026-10-07 (evening) — Fold6 CPU/GPU bench, WeSpeaker on LiteRT, compute policy

- **Fold6 matched decision latency** (same bundle, 10 turns): stock E2B CPU 8.2 s vs GPU 1.58 s (5.2×). Fine-tuned export: 15.6 s vs 3.9 s. Our INT4 export is ~2× slower than Google's stock bundle.
- **WeSpeaker ResNet34-LM converted locally with LiteRT Torch.** Kaldi fbank in-graph; host parity: fbank 6e-4, ONNX 0.999994, LiteRT 0.99999999999. Dynamic int8 is broken (0.015); ResNet-only weight-only int8 works (0.994, 11.6 MB).
- **Pixel 9a AVD, `AudioSpeakerLiteRtProbeTest`:** WeSpeaker fp32 241 ms, int8 227 ms, ECAPA 238 ms per 2 s window; device/host cosine ≈1. Recommendation updated: WeSpeaker int8 as default (7× smaller than ECAPA, more accurate), ECAPA as fallback.
- **Policy:** no more runtime patch builds or Gemma fine-tunes/exports on Luciano (shared NVMe). Local defaults: `JOBS=20`, Bazel RAM cap, `BAZEL_OUTPUT_ROOT` on NVMe; documented in `AGENTS.md`, `LITERT_SHARED_DECISIONS.md`, `BOUNDED_SPECIALIST_DECISIONS.md` and the campaign README.
- **Luciano backup:** non-regenerable work (14 GB: Laya/Needle/EG checkpoints, reports, datasets, logs, scripts, the baseline LoRA bundle, the latest 0.16 AARs) is being copied to `/mnt/seagate/ML/luciano-cyanbridge-backup-20261007/` with SHA-256 verification before any cleanup.
- **Disk findings:** the Seagate scheduler is `mq-deadline`, so `ionice` has no effect (BFQ needed). Gradle on the HDD is I/O-bound: a 92-min x86_64 build at about 50% system I/O pressure. The NVMe build-dir init script helps only the outputs.

## 2026-10-07 — Single-pass scorer, fine-tuned E2B on device, LiteRT 0.18 + EmbeddingGemma 2

- **Single pass:** `Tasks::Score` optionally returns a final-step full-vocabulary log-softmax for candidate ids (`RunCandidateScoring`); the JNI scores `⊲`+first label once, self-checks it against teacher forcing and falls back to per-label clones on Unimplemented. A first build read the step predicting the delimiter's last byte (mass ≈1e-8, caught on the AVD). AVD full run: 120/120 identical choices (max Δp 6e-8), p50 18.7 → 11.4 s.
- **Control false actions:** failures are "set X to its current value" with only the other value offered. Prompt rules/examples barely help (train 190 → ≤196 with host-annotated current values). LoRA on train (same budget as Laya/EG2): host dev 114/120 (current prompt), 118/120 (annotated options), 0% control false actions. Merged and exported to `.litertlm` (blockwise INT4 JSON recipe, 2.84 GB) after fixing config/tensor skew and a quantizer recipes-dir bug. On device the annotated LoRA scores 109/120 (AVD CPU and Fold6 CPU) and 110/120 (Fold6 GPU), all 0% control false actions, versus stock E2B 80/78.
- **App fixes:** text-only bundles no longer get audio/vision backends (failed every conversation before). LiteRT-LM version is a Gradle property (`-PliteRtLmVersion`), with per-version source sets; 0.16 stays default.
- **LiteRT-LM 0.18 patch** builds (dual ABI, OpenCL accelerator bundled). Fold6 GPU E2B scoring works (0.93 s vs 3.7 s smoke). `LiteRtEmbeddingEngine` runs EmbeddingGemma 2 text on CPU at 96 ms/embedding; GPU SIGSEGVs in the OpenCL delegate (factory keeps CPU); NPU bundle needs QNN libs. Fine-tuned EG2 has no LiteRT export path yet.
- **Memory (Fold6, measured properly with `dumpsys meminfo`):** CPU 2.15 GB steady PSS (1.42 GB reclaimable model mmap), GPU 3.02 GB (2.21 GB non-reclaimable GPU buffers). Earlier single `Debug.getPss()` snapshots were misleading.
- The Samsung's CyanBridge 2.3.0 debug build was replaced by this branch's debug build for testing (`install -r`, data kept). Details: `android/CyanBridge/docs/LITERT_SHARED_DECISIONS.md`.

## 2026-10-07 — Audio speaker filter (user-voice enrollment), parallel agent

- Benchmarked EmbeddingGemma 2 audio, the Gemma 4 E2B audio encoder, SpeechBrain ECAPA-TDNN and WeSpeaker ResNet34-LM on matched LibriSpeech test-clean trials (40 speakers, 3/5 enrollment phrases, 1/2/3.5 s; MUSAN noise/music negatives; wearer mixed with noise/babble at 0/5/10 dB). Gemma models are unusable for speaker filtering: EmbeddingGemma 2 final 38.7% EER, Gemma 4 soft tokens 35.7% (2 s, 5 phrases). Their audio towers are byte-identical (751/752 tensors), and the best internal layer reaches 9.4%. ECAPA 0.4%, WeSpeaker 0.2%. ECAPA is exported to LiteRT (84 MB, raw 2 s PCM → 192-d, host parity 0.9999998); device probe written, not run. Speaker templates are biometric data and stay on device. Doc: `android/CyanBridge/docs/AUDIO_SPEAKER_FILTER.md`.
- Shared-encoder follow-up (frozen Gemma audio encoder, LibriSpeech train-clean-100/360, 1,172 speakers disjoint from test): a 0.92M-param head (learned weights over conv + layers 0–3, attentive statistics pooling, 0.95 MB int8) reaches 0.8% EER on the matched trials (ECAPA 0.4%, WeSpeaker 0.2%; previous best mean-pooled 9.4%) and 1.3% on out-of-domain VCTK (ECAPA 0.5%), but accepts only 47% of the wearer in 0 dB noise (ECAPA 95%). Later layers, the encoder output (4.6–5.8%) and a LoRA branch (1.2%) do worse; a log-mel-only head reaches 1.5%. The E2B bundle's audio encoder section (94 MB INT2/INT4) runs standalone in a LiteRT Interpreter but only exposes final states; the useful layers 0–3 would need a ≈31 MB truncated copy, no RAM saving over int8 ECAPA. Decision unchanged: dedicated speaker model (ECAPA int8; WeSpeaker upgrade). No public Gemma/USM speaker head exists as of 2026-10-07. Artifacts: `/mnt/seagate/ML/cyanbridge-audio-speaker-20261007/head/` (SHA256SUMS verified).

## 2026-10-07 — LiteRT E2B classification working on the Pixel 9a AVD

- Moved the patched LiteRT-LM build to Luciano (48 cores; ~15× faster than local 4-job Bazel, which was stopped with its cache kept). NDK r28b downloaded there and SHA-1 checked against Google's repository manifest; Bazel binary SHA-256 matched; minimal SDK subset copied. `build_aar.sh` now takes `ABIS`/`JOBS`, builds `:litertlm-android_kt` (the old `:litertlm-android` target only yields an empty wrapper jar) and uses NDK `llvm-nm`/`llvm-readelf`; `package_aar.py` packages several ABIs with per-ABI ELF checks.
- First compile of the patched JNI failed: `Engine::GetTokenizer()` is const but `TextToTokenIds` is not. Applied the same `const_cast` upstream uses in `c/engine.cc`. Link then failed on Git LFS pointer files; copied real `prebuilt/android_{x86_64,arm64}` binaries after matching each LFS SHA-256. The open-source build links `libGemmaModelConstraintProvider.so` dynamically, so the AAR now bundles that verified upstream prebuilt (platform deps only, 16-KB aligned).
- AVD smoke (`LiteRtSharedDecisionRuntimeTest`, E2B CPU) passed but showed label mass ≈1e-5. Host diagnosis with stock `litert-lm` 0.16.0 Python and an HF `transformers` reference proved native `RunTextScoring` scores the first target token with the stale pre-last-prefill distribution; later tokens are correct (greedy decode picked the right answer). Patch now prefills the context before `⊲`, scores `⊲`+label and keeps the label token. Label mass is now 0.97–0.997. Rebuilt both ABIs (AAR SHA-256 `099c3dd1…`); the superseded AARs are kept separately.
- Added `LiteRtBoundedDecisionEvalTest` (device never sees expected labels), `renderedDecisionPrompt()` diagnostics and `score_litert_bounded_eval.py`. Full bounded-flat-v3 development run, stock E2B zero-shot on the AVD CPU: **80/120**, substantive 0.786, control false actions 34.4%; margin ≥0.9 accepts 47 at 46/47 correct. Decision p50 18.7 s (emulator). Best untuned option measured; tuned EG2 (110) and Laya (91) remain ahead on controls.
- 41 JVM decision/engine tests pass in default and prototype builds; 31 Python contract/new tests pass. Details: `android/CyanBridge/docs/LITERT_SHARED_DECISIONS.md`.

## 2026-10-06 — EmbeddingGemma 2 versus tuned Laya

- EmbeddingGemma 2 released today (`google/embeddinggemma-2` `914f7f89`, Apache 2.0; 270M text-only of 740M). Added an `embedding` backend to `eval_bounded_specialist.py` and a matched-budget `train_embedding_bounded.py` (180 updates, batch 16, best-of-60/120/180 development snapshot, in-menu softmax over cosine). Isolated `eg2-venv` on Luciano; EmbeddingGemma 1 fetched there with the token piped over stdin, never stored.
- `bounded-flat-v3` development, 120 turns: tuned EG2 (status+request, 768 or 256-d) **110/120, 0% control false actions**, versus tuned Laya 91/120 (rerun on the same A4000, identical to the published result) and tuned EmbeddingGemma 1 103/120. Zero-shot is unusable: 20/120 with status JSON, 50/120 request-only with 77% control false actions. EG2 query encode p50 35 ms A4000 / 223 ms CPU-4T. Development-set snapshot selection for both models; calibration/sealed untouched.
- Reports, tuned checkpoint (SHA-256 verified) and log on Seagate under `cyanbridge-specialists-20261003-artifacts/`. Details: `android/CyanBridge/docs/BOUNDED_SPECIALIST_DECISIONS.md`.

## 2026-10-04 — Same-engine LiteRT-LM decision scoring fork

- Found existing native `RunTextScoring` in pinned LiteRT-LM v0.16.0 (`924e79c91542761242244e4f1651851f822e4cbb`). It computes full-vocabulary **log probabilities**, despite the interface comment describing negative log probabilities. Added experimental Kotlin `Engine.scoreDecision` and JNI wrapping the existing scoring implementation: full-prefix single-token checks, configured-context overflow rejection, prefill once, isolated cloned context per candidate, finite score/mass validation. All contexts use the existing engine; no model loader is invoked.
- Added opt-in `-PliteRtDecisionPrototype=true` shadow calls and `-PlocalLiteRtDecisionAarPath=...` dependency override. The app renders the loaded model's template with thinking disabled, feeds the delimited assistant prefix and serializes LiteRT chat/scoring/load/unload. Existing normal/reversed-menu alignment and incumbent Tasker output are retained. Stock 0.16 remains the default; flag-only stock builds report that the patched API is missing if scoring is attempted.
- Preserved the pinned source patch and matching Kotlin/JNI ARM64 AAR build/packaging helpers under `android/CyanBridge/tools/litert-decisions/`. Source and native build/download cache are under `/mnt/seagate/ML/cyanbridge-litert-decision-20261004/`. No libraries were installed in base conda.
- **9 selected JVM tests passed** (5 fork-binding + 4 shared-menu tests); opt-in debug app and instrumentation APK built against stock 0.16.0. The default build also passes **13 selected JVM tests**, adding the four existing LiteRT streaming checks. This verifies app integration, not actual fork-native inference. Added provisioned `LiteRtSharedDecisionRuntimeTest` for scoring→chat→scoring reset, invalid-label/overflow/unloaded rejection and smoke timing/PSS. ADB reports no attached device; E2B/E4B/GPU inference and phone memory remain pending. Native fork build status is recorded in `docs/LITERT_SHARED_DECISIONS.md`.
- First scoring implementation requires one prefill and N clone/decode operations per menu, repeated for reversed presentation. It adds temporary context memory and synchronous scoring can delay cancellation. A single native logit pass for all labels is an optimization target after correctness/backend parity. Evidence and reproduction: `android/CyanBridge/docs/LITERT_SHARED_DECISIONS.md`.
- Recovered after server restart: retained completed app/test results and the pinned source patch, resumed the interrupted NDK download, verified its size and upstream SHA-1, and extracted NDK r28b on Seagate. Resumed matching Kotlin/JNI ARM64 build using the saved Bazel cache; log `build-arm64-resumed.log`. Corrected the packager's hashing helper to work with the restarted server's Python 3.10 and added ARM64, JNI-symbol, native-dependency and 16-KB-load-segment checks.
- The resumed source build first failed during toolchain discovery because Python ZIP extraction had materialized NDK symlinks as text. Restored all 35 symlinks from the verified archive, confirmed Clang 19 launches and resumed the cached build (`build-arm64-ndk-links.log`). Added compiler-launch preflight to the build helper. This failure occurred before native source compilation.

## 2026-10-04 — Shared chat-model System-One prototype (Jev branch)

- Verified the supplied external discussion against current upstream sources: dedicated `/v1/systemone` PR #29818 merged October 2; ordinary-chat-model PR #29832 remains an open draft with E4B/Qwen3.5 examples and no E2B/phone-action accuracy evidence. PocketPal depends on llama.rn; its backend documents Adreno OpenCL and experimental Hexagon, not universal phone acceleration. Gallery's referenced bundles recommend 8 GB for E2B / 12 GB for E4B; MobileActions has a 289-MB artifact and 6-GB recommendation. These are bundle configurations, not measured CyanBridge RAM requirements.
- User selected **current Jev branch** as the implementation target. Added an opt-in `llama-runtime` text/CPU prototype, pinned to the same verified upstream revision as the Eyevue adapter (`0c1e57098bba43ac29e6e3b677cdceebdd22334f`). `-PsharedGemmaPrototype=true` selects one native GGUF/context for both ordinary chat and real next-token-logit decisions. Native scoring uses the model Jinja template with thinking disabled, validates one-token labels and full-prefix delimiter boundaries, rejects overflow and reports full-vocabulary label mass. No server/localhost API is included.
- `SharedGemmaDecisionEngine` compares normal/reversed menus with position-label reassignment and aligns results to original bound candidate indices. Decisions always abstain; Tasker records `SHARED_GEMMA_SHADOW` while returning the incumbent cascade's choice. The session serializes inference/reload/unload, and scoring borrows an already-loaded engine without loading weights. LiteRT/legacy-wrapper sessions do not produce raw-logit shadow results; the existing short-decoded-choice path still works for LiteRT. No GPU, multimodal, calibration or deployment result is claimed.
- Verification: **48 JVM decision/candidate/UTF-8 tests passed**; host checked-JNI smoke passed with the existing Qwen2.5 0.5B Q4_K_M file, including actual logits, chat/scoring state reset, concurrent calls, streaming/grammar and malformed-label/overflow/unloaded-handle rejection. ARM64 native AAR, debug app and Android-test APK built successfully. Opt-in ARM64 `.so`: 6,352,000 bytes; default runtime AAR verified to contain no native libraries. No ADB device was attached, so the provisioned `SharedGemmaRuntimeTest` was compiled but not run. E2B/E4B quality, memory and device latency remain to be measured.
- Source/usage/evidence: `android/CyanBridge/docs/SHARED_GEMMA_DECISIONS.md`; native/test logs under `/tmp/opencode/shared-gemma-*`. No calibration/sealed cohort or phone credentials were touched.

## 2026-10-04 — Resumed bounded ablations and verified campaign summaries

- Compared precisions on the best tuned v3 flat Laya checkpoint using the local RTX 4060 Ti and same padded inputs (1×512 tokens / 12 option slots): existing FP32-weight/BF16-autocast reference **93/120**; FP16-resident **91/120**, BF16-resident **94/120**. Resident 16-bit variants each use **625.5 MiB allocated / 644.6 MiB peak VRAM**, versus reference **1,247 / 1,514.5 MiB**. BF16 p50 forward 33.49 ms and FP16 34.47 ms; the single 120-example development-set difference is not validation evidence. Two reference choices differ between the prior A4000 and local GPU; the fixed-input local reference matches the local variable-length run exactly.
- Upstream-style Q8 per-tensor dynamic INT8 ONNX scored **79/120** (80.8% choice agreement to local reference), flips 23 choices (3 fixes, 17 regressions, 3 both-wrong changes), and causes **8/64 control false actions**. Its static FP32 ONNX source is 1,290,252,004 bytes; Q8 is 914,805,203 bytes (1.41× smaller than FP32 graph but 1.42× larger than the original 614-MiB FP16 checkpoint). ONNX Runtime CPU with four threads p50 **597 ms**; process RSS after run 1,968 MiB and high-water 6,649 MiB. This is not competitive for this selector.
- The campaign-pinned dynamic-axis ONNX export fails at variable sequence lengths because the traced attention reshape bakes in dummy length 16. To complete the Q8 comparison, exported a fixed 512-token / 12-option graph and fed identical padding/attention/marker masks to all modes; reference labels are unchanged from variable-length local inference. Full report: `/tmp/opencode/cyanbridge-specialists-20261003/reports/bounded-v3-laya-flat-precision-fixed512-comparison.json`; hashes/results summarized in `training/laya_needle_specialists_v1/laya-bounded-v3-flat-precision-summary.json`. No Android/NNAPI/LiteRT measurement, calibration, sealed inference or setting mutation occurred.
- Checked upstream Laya current main (`8a6e1328cce2460a0e5aa348ad465bb1b5821cd2`) against our older campaign source pin (`4066d5d5fbf08b66c6757ddeedbd797bd7655bc0`). Upstream now has ONNX dynamic weight-only INT8 Q8 export, CPU-only, default per-tensor; documented 1.4–2.8× size reduction is vs FP32 ONNX, with ~83% eager-output agreement for multilingual; per-channel is much worse. No Q4 or prequantized multilingual Hub artifact. `laya-java` ONNX/JVM port explicitly lists Android as not implemented; not a supported drop-in Android build. HF current multilingual weights are FP16. Documented links and constraints in `docs/BOUNDED_SPECIALIST_DECISIONS.md`.
- Measured Laya bounded-v3 hierarchy inference memory on Luciano's RTX A4000 using the isolated PyTorch environment: one checkpoint is 614.01 MiB/321.9M FP16 values; the inference module loads FP32 parameters and runs BF16 autocast. One 438-token, 12-option batch-1 forward used 1,515 MiB peak allocated / 1,538 MiB reserved VRAM; resident after transfer was 1,247/1,272 MiB. Whole Python RSS was 1.78 GiB after model transfer / 2.08 GiB after inference, with ~3.10 GiB transient load high-water. Linux Python/GPU only; Android export/phone PSS and runtime buffers remain unmeasured. Recorded in `android/CyanBridge/docs/BOUNDED_SPECIALIST_DECISIONS.md`.
- ICproj migration was interrupted by a termination signal during copying; its NVMe original remained intact. Confirmed the LTFS web service and tape mount were still running. Resumed only the previously audited partial destination `/mnt/seagate/ML/ICproj` at idle I/O priority, in a detached process that survives the initiating shell. Original deletion remains gated on full checksum/worktree verification. Migration completion is pending.
- Resumed remote work on idle Luciano GPU 1. Executed both corrected v3 Needle fixed-budget fits, 20-layer exports, base/tuned oracle evaluation and self-routed rollouts. Hierarchy: oracle **61/352 → 68/352**, episodes **14/120 → 9/120**; flat: **11/120 → 12/120**. Hierarchical root tuned **16/120**; tuned invalid choices **41/352 hierarchy / 2/120 flat**, oracle control false-action rates **35.9% / 79.7%**. Final training losses **0.202 / 0.267** do not establish runtime accuracy.
- Published complete eight-report `bounded-v2-summary.json` and `bounded-v3-summary.json` in the compact campaign training directory. Tightened publication checks for exact required variants, contracts/views, held-out scope flags, recomputed metrics/effects and referenced model SHA-256; summary also hashes each full report. V3 has all prediction/model hashes; the two earliest v2 Laya hierarchical reports lack embedded hashes, explicitly recorded rather than retroactively claimed verified.
- Retrieved bounded Needle v2/v3 adapters, archives, training records and full baseline reports directly to Seagate at `/mnt/seagate/ML/cyanbridge-specialists-20261003-artifacts/`; verified **28 files** against a remote SHA-256/size manifest. Large new model copies do not consume the local NVMe.
- Completed reversed-presentation evaluation preserving host IDs/effects and original request/status. Tuned Laya flat **91→92/120**, hierarchy **86→82/120**; tuned Needle flat **12→16/120**, hierarchy **9→20/120**. A staging omission of the flat builder's transitive generator import failed after hierarchical reports completed in both lanes; preserved failed logs, staged an isolated complete directory, and reran only missing flat reports. Published an independently verified eight-report order summary.
- Completed the first-12-development-turns/view base/tuned CUDA teacher-forcing diagnostic. Base predicts `<think>` first on all 24 rows despite `<tool_call>` targets; tuned flat also 12/12 `<think>`, hierarchy 6/12 `<tool_call>`. Teacher-forced token counts improve (hierarchy 124/158→142/158; flat 128/163→140/163) with no entirely correct 12-row target in any variant. Published a compact diagnostic with case IDs, report/adapter/base/data hashes and explicit non-native scope.
- Completed the matched Needle reasoning-target ablation in an isolated script directory. Only `train.needle.jsonl` gains short synthetic bound-label-derived reasoning; requests/status/menus/answers/groups and all other cohort files are preserved. Full token/binding/mask audits passed (training maxima 652 hierarchy / 753 flat, cap 768), followed by both 180-update/batch-8 fits and native oracle/rollout evaluations. Tuned hierarchy **76/352 oracle / 7/120 episodes**, flat **18/120 episodes**. Hierarchical invalid choices drop 41→3 while root accuracy falls 16→12/120 and control false actions rise 35.9%→51.6%; flat invalids rise 2→18 and control false actions fall 79.7%→60.9%, still worse than base. Longer-target losses are not directly comparable. This diagnostic does not identify a complete parity fix or justify promotion.
- Published `bounded-v3-reasoning-summary.json`, explicitly complete for its four Needle variants; regenerated the main v2/v3 and order summaries with current strict checks and per-ablation flags/budgets. Retrieved new artifacts/reports/logs directly to Seagate; all **50 model/adapter/training/report files** passed remote/local SHA-256 and byte-size verification.
- **26 contract/metric tests + 10 campaign publication tests pass**, including reasoning-input preservation, unchanged bindings under reversed order, strict eight-report main campaigns and deliberate four-report single-model ablations. Documentation links and Python/JSON parsing passed; `git diff --check` passed after the prior changes. No real ASR, calibration/sealed inference, phone action/readback, device model download or email send was performed. All models remain shadow-only.

## 2026-10-04 — Shared EyeVue/TuneBuds capture ownership and website rollout

- Deployed the current website working tree to Vercel production. Deployment
  `dpl_973D6gSkkNqKvBHuDPFcSLaWHqFB` is READY on `cyanbridgelabs.com` and
  `cyanbridge.vercel.app`. Both vision endpoints correctly rejected unauthenticated
  POSTs with 401; the production homepage returned 200. Deployment/inspection
  logs: `/tmp/opencode/unified-photo-vercel-{deploy.log,inspect.json}`.
- Added shared device-tagged `GlassesPhotoCapture` ownership across HeyCyan,
  EyeVue and TuneBuds; background permits now carry a device class. Raw vendor
  disconnects clear HeyCyan permits only. Native disconnect/reconnect cancels
  only the matching capture and keeps ownership until cleanup finishes.
- Added `NativeGlassesPhotoCapture` with one owned waiter, a deadline covering
  command acknowledgement plus image transfer, complete JPEG validation, bounded
  stream buffers and noncancellable late-packet draining. Native push paths do
  not automatically take another photo on failure. Removed broadcast-flow waits
  from both native managers and awaited EyeVue's actual command write.
- Native manual AI streams reserve receive-only ownership at stream start and
  pass an acquisition future to the device-filtered, identity-aware Live router.
  Live records attempts before reception and sees failure outcomes; stopping or
  superseding the handler cancels acquisition without another shutter. Shared
  transfer diagnostics include native failures; send counts/limits stay common.
- Added `GlassesAiPhotoCapture` as the selected-device adapter for Live, Walking
  Aid periodic photos and auto/standalone visual notes, including native connection
  checks. Dashboard camera commands and TuneBuds Detailed acquisition also hold
  shared ownership; Detailed releases it after Wi-Fi/hotspot cleanup.
- EyeVue ignores callbacks from replaced GATT objects and TuneBuds ignores data
  returned by replaced RFCOMM sockets. Assemblers reset and reject overflow/orphan
  tails; native stream attempt IDs remain unproven. TuneBuds Detailed's existing
  newest-filename storage selection is not claimed as proven fresh correlation.
- Verification: 208 focused tests passed (37 classes, zero failures/errors/skips),
  covering ownership, native timeout/cancellation/draining/recovery, manual stream
  diagnostics, adapter routing, Live send counters, device protocols and Walking
  Aid. Integrated ARM64 debug APK built. Both repositories passed `git diff --check`.
  Log: `/tmp/opencode/unified-native-photo-tests-build.log`.
- Documented contract and hardware acceptance in
  `android/CyanBridge/docs/UNIFIED_GLASSES_PHOTO_CAPTURE.md`. No ADB device was
  connected; physical repeated captures, native manual streams, disconnect recovery
  and TuneBuds Detailed regression remain pending. Android edits are local and
  uncommitted; no APK was distributed.

## 2026-10-04 — Implement HeyCyan Live photo ownership and transfer repair

- Added `HeyCyanPhotoCapture`/`HeyCyanCaptureOwner` using the Android coordinator
  for Live, Walking Aid and auto-loop. Programmatic captures register a fresh
  photo-ready waiter before issuing commands; missing notifications fail rather
  than reading stale thumbnails. MainActivity defers their owned notifications.
- Restored physical-photo routing through the Live router's dedicated vendor
  listener, including while MainActivity is stopped. MainActivity's own requested
  capture retains precedence through the shared permit. Handler replacement and
  unregister are identity-aware; UI/service work dispatches on main.
- Added `HeyCyanThumbnailReceiver` for every Android HeyCyan thumbnail consumer.
  It owns raw FD responses, validates total/index/order/size and full JPEG decode,
  makes one bounded retry, freezes writes/chaining, drains late replies with a
  bounded quiet window, and compare-removes only its owned map entry. Callers
  release permits in finally after cleanup. MainActivity/auto-loop timeout permit
  leaks are repaired. Vendor private packet-request helper is retained by ProGuard.
- Live sends now return enqueue success, serialize counters/limit checks, and
  update visual-context/sent status only after acceptance. Free/Economy respects
  the proxy's ten-image cap; Private retains its existing visual-input cap.
- Added nonbillable session diagnostics (attempts/completions/failures, photo-ready
  timeouts, transfer failures, queued images and rejected sends). Website changes
  in `/home/fertroll10/Documents/CyanBridgeLabs/Cyanbridge_website` add an authenticated
  idempotent revision endpoint, proxy session correlation and separate admin table
  diagnostics. Diagnostic events do not enter usage/quota/billing records.
- Verification: 38 focused Android tests passed, including actual-AAR raw handler
  ownership, truncated JPEG rejection, competing captures, missing ready event,
  failure recovery, cancellation, disconnect invalidation, late tails and concurrent
  send-limit enforcement.
  Integrated ARM64 debug APK built. Website focused tests passed 7/7; lint, build
  and Prisma validation passed. Both repositories passed `git diff --check`.
- Full website E2E was run: 118 passed, 47 skipped, 4 not run, 19 failed. Failures
  are in existing account-delete localized text, checkout/database setup and old
  homepage/viewport assertions; the new vision endpoint E2E passed. Full output:
  `/tmp/opencode/live-photo-website-e2e.log`. No unrelated checkout/homepage repair
  was bundled into this task. Final smoke/vision endpoint rerun passed 23/23.
- Documented implementation and hardware acceptance in
  `android/CyanBridge/docs/HEYCYAN_LIVE_PHOTO_REPAIR.md` and website
  `docs/GEMINI_LIVE_VISION_DIAGNOSTICS.md`. No ADB device was connected; W610
  validation and arbitrarily delayed same-metadata packet timing remain pending.
  Changes are local and uncommitted; no website/APK rollout performed.

## 2026-10-04 — Gemini Live photo counts and capture ownership follow-up

- Inspected `/home/fertroll10/Documents/CyanBridgeLabs/Cyanbridge_website` and
  its Free/Economy proxy, Private usage/end API, analytics store/dashboard,
  alongside Android Live preferences, capture, event routing and send counters.
- Read-only production KV query over 30 days: 203 session rows, 163 with zero
  images, 37 with one, 3 with more; excluding flagged test accounts, 189/190
  had zero/one. Last 7 days: 47 rows, 38 zero/9 one. Free/Economy proxy counts
  incoming video messages directly; the count is not artificially capped at
  one. There were no Private per-turn records in the sample. Saved only
  anonymized aggregates under `/tmp/opencode/issue23-live-production-summary.json`.
- Vercel production alias READY, deployed October 1 18:13 UTC. CLI inspection
  did not return source SHA, so local uncommitted website edits are not treated
  as proven deployed source. Checked-in and local counting paths agree on the
  relevant proxy image-counter behavior.
- Default Live refresh mode is intentionally only-first-image; zero for voice
  startup and one for image startup can be expected. Automatic every-turn
  capture requires opt-in and locally detected speech.
- Confirmed July 29 `62cbb17` removed the only call to
  `GeminiLiveImageButtonRouter.handleImageButton()` while Live handlers still
  register. Automatic HeyCyan Live captures use a separate shared coordinator
  and fixed four-second wait, allowing MainActivity to treat their `0x02` as a
  hardware request and start a competing read of the same singleton FD slot.
- Found send statuses can claim success despite `sendVideoFrame()` silently
  dropping a frame. The backend has no capture-attempt/transfer-failure counts
  and cannot distinguish these from intentionally disabled vision. Latest 200
  production debug-log submissions had no matching BLE failure lines.
- Added `docs/GEMINI_LIVE_PHOTO_COUNT_AUDIT.md` with evidence and prioritized
  capture ownership/router/receiver/send-result/telemetry repair requirements.
  Existing focused preference/policy/coordinator unit tests passed 19/19;
  those tests do not cover the cross-workflow defects. `git diff --check` passed.
  Investigation only; no website deployment or Android logic patch. No ADB
  device available for a physical repeat-capture run.

## 2026-10-04 — HeyCyan BLE photo issue #23 investigation

- Read issue #23 and its comments, compared remote main and v2.3.0 with the
  local thumbnail path, and traced the official app, bundled vendor AAR,
  firmware photo/cache evidence, and Git history. Findings are in
  `android/CyanBridge/docs/HEYCYAN_BLE_PHOTO_ISSUE_23_AUDIT.md`.
- Confirmed `559abe1` (July 17) removed the two-attempt thumbnail retry and
  introduced completion-owned background-permit release with no bounded
  timeout/cancellation recovery. The post-0x02 transport wait is absent, but
  was not a 250 ms delay removed by that commit. The official app also has
  no explicit post-notification delay; test 250 ms as a workaround, not a
  firmware-proven requirement.
- Inspected actual `glasses_sdk_20250723_v01.aar` with javap and executed a
  standalone synthetic-frame Java probe. All three checks passed: total=0
  suppresses the app callback; a final packet alone reports completion; the
  callback accepts another final packet after completion. Artifacts and
  results: `/tmp/opencode/issue23-sdk/`.
- A safe repair must stop/freeze/detach the owned thumbnail handler and account
  for late packets before releasing the permit or retrying. Separate files
  alone cannot isolate wire responses because the SDK routes by opcode -3.
- Found separate Android/shared session coordinators used by MainActivity
  versus Gemini Live still capture, relevant to the assistant-active retest.
  Existing September 29 AIMB-G3 logs show two successful BLE previews; the
  W610 initial failure remains unproven without a failed raw-packet trace.
- Documentation-only investigation; no production photo-transfer patch yet.
  No ADB device was connected. `git diff --check` passed.

## 2026-10-03 — Paused for local NVMe audit and progress review

- User requested an explanation of NVMe usage before continuing. Paused new training; both Luciano GPUs are idle. Needle version-2 bounded fits/evaluations completed; version-3 Needle has **not** started. Laya version-3 fits and rollout reports are complete.
- Earlier local disk check was 99% with 9.6 GiB available; current filesystem reports ~862 GiB used / 914 GiB capacity, 5.76 GiB available to ordinary processes, and ~46.5 GiB free-but-reserved/unavailable blocks. `df` rounds displayed usage to 100%; it does not mean literally zero free bytes.
- This session's local `/tmp/opencode/cyanbridge-specialists-20261003/` is ~4.2 GiB, including ~3.99 GiB models. Six full Laya safetensors copies at 614 MiB each account for ~3.60 GiB, plus Needle artifacts and small corpora/reports. The training environments/checkpoints were built on Luciano (campaign ~15 GiB there); local copies for verification consumed avoidable NVMe headroom. Seagate has ~157 GiB available and is the appropriate artifact-storage destination before more work.
- Measured existing large local occupants include Documents ~308 GiB (ICproj 100 GiB; CyanBridgeLabs 77 GiB including gemma4-sm8650-port 61 GiB), anaconda3 65 GiB, cache folders ~62 GiB, `/tmp/opencode` 55 GiB including this session, Android AVDs/backups ~47 GiB, and OpenCode data ~23 GiB. Per-path totals can overlap/share hardlinks; this is not a complete privileged filesystem accounting. The audit was read-only apart from its small log; no cleanup or artifact move was performed.
- Completed Needle version-2 bounded comparison: hierarchical oracle turns 62/352 base → 87/352 tuned, but self-routed episodes remain 12/120; flat 10/120 → 11/120. Those inputs lacked the explicit v3 running/readiness facts and are diagnostic. No checkpoint is promoted; actual ASR/native readback/Tasker collection and Android runtime verification remain pending.

## 2026-10-03 — Remote training and state-bounded specialist contracts

- Used Luciano's two idle RTX A4000 16 GB GPUs; PhotoBioPC's GPU was occupied. Created isolated Laya/Needle environments and transferred/hash-checked existing pinned models. No local GPU training or base-conda package installation.
- Executed six Laya head/encoder fits and four Needle LoRA fits on paired public-operation/generated-config pilot views. Preserved source-family groups and official Mobile Actions exclusions, independent calibration/sealed cohorts, exact typed calls, native suppressed-call/error evidence and model hashes. Laya voice encoder+head reached 469/480 open development calls, config 191/210; config hand-written challenges only 11/24. Needle mixed fits did not improve runtime refusal (voice 216/480 versus 218 base; config 66/210 versus 67 base). Full ablation record: `training/laya_needle_specialists_v1/README.md`.
- Incorporated the user's correction: status-defined, turn-eligible action families → settings categories → settings → valid installed model/value/download offer. Implemented a versioned offline host menu/transition contract, paired Laya choices / Needle zero-argument bound-choice tools, freshness/membership/reachable-scope checks and explicit clarify/no-change/defer choices. Root options and every offered leaf/control now have positive supervision; missing/unusable/incompatible models are not offered for selection.
- Added state-matched flat-versus-hierarchical dataset views (300 train / 120 development episodes in both; 880 vs 300 training turns), grouped before state variants. Added per-turn and self-routed rollout evaluation so wrong ancestors cannot be replaced by gold scope, plus audits for bound-label coverage, eligibility, hashes and unclipped inputs/target masks. Initial incomplete root-label coverage runs were archived before final fitting. A large Needle batch exhausted VRAM; switched to a small fixed-update trainer reusing upstream quant-aware LoRA routines.
- The contract is reference training/evaluation tooling, not an Android dispatcher. It proposes download offers and setting effects without executing or claiming readback; actual plugin/state/Tasker evidence integration remains necessary. No phone action, model download to the phone or email was performed. Details: `docs/BOUNDED_SPECIALIST_DECISIONS.md`.
- Executed the initial version-2 state-matched Laya ablation from base: flat 31/120→90/120 proposed-effect episodes; hierarchical 37/120→85/120, despite oracle-turn choices improving 167/352→301/352. Mean hierarchy cost is 2.75 model decisions/episode versus one flat call. Narrow final installed/download choices each pass 6/6 gold-scope turns; root routing and ancestor errors dominate. These are open written development results, not verified setting outcomes.
- A further status audit found that omitting Live running/readiness facts forces inference from an absent Start choice. Version 3 exposes Live running/availability, Tasker readiness and download-in-flight explicitly; version-2 inputs/results remain diagnostic artifacts. Version-3 fitting starts again from original base weights, preserves the same goal cohorts and uses identical per-turn/rollout status rendering. The paired tooling checks now include that state-visibility regression.
- Completed the corrected version-3 Laya fits: flat 39/120→91/120, hierarchical 36/120→86/120 self-routed episodes; hierarchical oracle turns 176/352→305/352. Smaller final menus help local choices but do not yet beat the flat complete-path control. Audited all model inputs/loss masks without clipping (v3 maxima: Laya hierarchy/flat 393/442, Needle 632/727), all offered choices have positive training labels, and 24 contract/metric tests pass. No calibration or sealed inference was performed.

## 2026-10-03 — Laya/Needle specialist variants and public dataset inventory

- Adopted the user's focus on Laya/Needle with separate voice-router, native configuration/plugin selector and phone-UI contracts. Voice/config training can progress from typed app capabilities and readback without waiting for the broad Tasker UI corpus. No larger generative-model training is proposed.
- Reviewed primary MASSIVE, SLURP, CLINC OOS, home-domain NLU Evaluation, AndroidControl, updated GUIOdyssey and AndroidWorld sources; added pinned access/licensing/split metadata in `training/laya_needle_dataset_sources_v1/catalog.json`. MASSIVE 1.1 has 52 locales vs 1.0's 51; SLURP text/audio terms differ; Fluent Speech Commands' publisher expressly excludes product training/testing/development. AndroidControl is the preferred text/a11y UI auxiliary; screenshot descriptions/boxes from GUIOdyssey are not Tasker evidence.
- Parsed pinned Google Mobile Actions in memory: 9,654 rows, 8,693 metadata-train / 961 eval; train is 5,794 single-call / 2,899 multi-call / zero no-call. HF exposes the whole file as train, so internal metadata filtering is mandatory. Seven gold action names give limited settings scope. Evaluation rows were counted, not replayed or used for tuning.
- Parsed pinned DroidCall structured training: 10,071 rows, 7,435 single-call / 2,636 multi-call / zero no-call. Inspected schema conversion needs, answer-name anomalies and one ungrounded address/prose example. Plan reviewed supported/grounded subsets plus product-specific negatives; neither pool is Tasker supervision or already suitable for blind fine-tuning.
- Mapped proposed product labels to existing dashboard enums/actions, native plugin IDs and preferences. Preserved distinctions between AI image wake-word route and image invocation, plugin enable and start, settings navigation and mutation. Gemini Live refresh 2 is an every-speech-turn sentinel, not two seconds; Visual Diary intervals are 1–240 minutes. The proposed voice manifest/dispatcher is not yet implemented.
- Added `docs/LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md`, source inventory README/catalog, and cross-links; revised roadmap priority toward voice/config data and paired Laya/Needle specialists alongside UI collection. Kept each specialist's split/calibration/runtime gate, current approvals/fallback and provenance separation. No new model training or phone action was run.
- Verification: parsed the source catalog, checked nine revision pins and measured row-count sums, and resolved 26 local links across the five linked documents; `git diff --check` passed. Rechecked publisher licensing/action-space notes. These are inventory/documentation checks, not model-quality or device-runtime results.

## 2026-10-03 — Closer voice/device references and Tasker training findings

- Followed the user's Robotics & devices reference and reviewed its ten catalog entries. Prioritized Evoke's installed recipes/closed inputs, smart-home control-vs-conversation routing, Fusion 360's command detector and RoboJEV's intent/action separation with independent outcomes. Inspected Evoke `0c61ee5`, RoboJEV `2c920c2` and EmbodiedJev's integration review `f08de2e`; report-only demos and simulator evidence are identified explicitly.
- Found a closer Laya worked training example at source `fa9a2a7`: Spanish telephone voice-turn routing, rebuildable 4,740 examples, unseen menus, negation in both directions, corrections, ASR-like text and full encoder/head training. Its author-reported 0.396→0.912 over 217 written fixtures still includes 12 wrong actions and weak confidence gating; later variants used inspected errors. Its random question-level calibration and 1024/256 token configuration must be adapted to our grouped episodes/mobile export.
- Inspected Needle `9571a58` media/wearable/smart-home schemas and their acceptance harness. They are stub environments with no-call/invalid/negation cases, not Tasker training trajectories; harness masking, string case-folding and call sorting must not become our raw/exact/order metrics. Current local trainer supports W4 exact-call scoring and `--val-split 0`, but defaults to random examples. Official DroidCall/Mobile Actions tuning plots force calls and do not prove refusal; the MicroDuck demo did not supply a verified reproducible dataset in this review.
- Documented separate linked voice-route and UI-action datasets: actual ASR provenance/literals, command/chatter/clarification, supported capability menus, current subgoal, fresh Tasker evidence and verified outcome. Proposed contrast pairs distinguish negation from a positive request containing negative words, corrections from blanket refusal, and filled-field DONE from unfinished goals.
- Rechecked our wire/parser: first 160 nodes, bounded text/ID/summary, array-index pairing, unreported flags written as false, zero-area tap points and invalid coordinates defaulting to `(0,0)`. Proposed explicit unknown/provenance/clipping/coordinate-validity records and recall audits; these are design requirements, not implemented new metadata. Identified page-wide literal visibility as distinct from verified field value.
- Added `android/CyanBridge/docs/JEV_VOICE_TASKER_TRAINING_FINDINGS.md` with source pins, concrete model-format examples and collection/training changes; linked it from the roadmap, training recipe, Tasker context and embedding findings. Kept Laya/Needle shadow-only and the existing Gemma/planner/approval boundaries. This research update adds no model training or live device-action result.
- Verification: checked local Markdown link targets, illustrative JSON parsing/choice-tool label consistency, and `git diff --check`. These checks validate the research document, not model accuracy or runtime compatibility.

## 2026-10-03 — Evidence-based phone-agent plan

Current planning entry point: `android/CyanBridge/docs/JEV_PHONE_AGENT_ROADMAP.md`.
This supersedes older next-agent ordering and handoff recommendations below;
historical measurements retain their dates. Reviewed clean baseline `160299c`.

- Re-read Ship With Jev's engineering guide, Browser Use flight search, Hunch and task-done checker, plus the October 1 Jev Layer and Playwright integration entries. Adopt operational questions, explicit unclear/defer, fresh host-approved options, host-owned execution/approvals and attached outcomes. Catalog numbers are author-reported browser evidence, not Android acceptance targets.
- Consolidated results: Gemma margin ≥0.10 remains the conservative incumbent; its 48/48 calibration and 16/16 accepted test choices do not establish ≥98% real-phone precision. Laya/Needle synthetic gains did not transfer reliably; their uncalibrated probabilities/confidence do not authorize actions. Keep both shadow-only. Pro/Gemma YouTube playback previously passed twice, latest 22.9 s; Gmail send/delivery remains unverified because Tasker composer observation is blocked.
- Dataset readiness: Artemis has 29 real emulator episodes / 82 decisions but only three connected families and no missing-control supervision. The six YouTube Tasker frames and all earlier frozen/pilot tests have been inspected. One distinct Tasker Settings episode took 1,821 ms pre-observation-to-post-observation (1,734 ms action-to-verification). None supplies a new train/calibration/final-test split.
- Prioritized next batch: restore/pin Pixel and re-establish Pro baseline → version a complete Tasker episode/candidate collector and stage timers → collect roughly 100–150 fresh episodes / 500–1,000 decisions across at least six independent families, including negative/unfinished/DONE cases → audit recall and reserve grouped holdouts → compare deterministic/compact-Gemma/planner baselines before retraining. Counts are planning targets, not a statistical release guarantee.
- Runtime lane: verify pinned patched GGUF artifact acquisition/build; retain Gemma CPU as baseline, defer Qwen embedding optimization. Needle's small ARM Android runtime makes it the first new device feasibility probe once an ARM64 target exists; host Linux timing is not Android timing. Laya LiteRT comes after a clean development candidate, with tokenizer/head/output parity and actual-device timing. The selected-node mapping, bound-field focus and short production state builder already exist; do not reopen them as missing implementations.
- Promotion plan: separate accuracy/calibration from ABI/runtime parity, and separate inference latency from verified task time. Use a new locked final test, report uncertainty and episode-grouped metrics, require useful reversible-action coverage at the declared precision target and matched planner task-completion results. Keep Gmail as a read-only observation repair track until fresh composer verification works.
- Server-restart notices cancelled historical emulator processes. `adb devices -l` showed no attached target during this review. This planning work did not boot/wipe an AVD, retrain, run a new live workflow or send email. Revalidate local artifacts and device identity before the next HIL; serial reuse is not device provenance.
- Documentation checks: all local Markdown links in the planning/training/model docs resolve; saved Artemis source/pool SHA-256 values match the manifest. Read-only audits confirm 29 Artemis episodes / 82 decisions / three components (`readyForIndependentSplit=false`) and the saved Tasker diagnostic (`readyForTraining=false`).

## 2026-10-03 — Shared EyeVue/HeyCyan RTSP endpoint

- User confirmed commit `3d7cfe5` streams successfully on their Samsung phone
  and HeyCyan glasses. That resolves the pending October 2 playback retest.
- Reviewed Vortex's M02S report again: official `02 01 14 01` P2P start,
  `02 01 15 01` stop, `8554/ch0`, TCP RTP, in-band SPS/PPS with missing video
  fmtp, five-second BLE heartbeat, and untested `02 01 14 02` AP start.
- Promoted EyeVue's multi-viewer RTSP relay/wire parser to `media/rtsp/` and
  used it for HeyCyan as well. Both now listen on phone loopback port 8554 and
  publish `rtsp://127.0.0.1:8554/live/`. Port conflicts are explicit errors;
  no random-port fallback. One upstream RTSP session forwards encoded video
  and audio to all clients; independent viewer teardown stays local.
- Retained EyeVue's zero-based PLAY rewrite; HeyCyan uses normal live PLAY.
  Local SDP connection addresses are normalized to loopback. HeyCyan retains
  its working BLE IP, full-MAC peer matching, pending-connect protection, and
  normal-bind/verified-direct-route sequence. Added the vendor's live heartbeat
  as a cancellable child covering startup and playback. Relay cleanup precedes
  native player release and network teardown.
- The local decompiled official app is `1.0.86_20260115` (manifest), older than
  Vortex's 1.0.103. `wifi/ap/TempWifiHelper.java` confirms WPA2 Wi-Fi specifier
  association and network callback/binding cleanup; the newer realtime activity
  and its credential source are absent. Recorded AP investigation details;
  live transport remains the validated P2P mode.
- Added `docs/LIVE_STREAM_INTEGRATION.md` with VLC, USB/ADB TCP forwarding,
  FFmpeg recording, fixed-port lifetime, latency and OEM/AP notes. Replaced
  stale passive-probe instructions in `docs/LIVE_PREVIEW_TEST_MODE.md`.
- Concurrent Walking Aid changes temporarily lacked resources during the
  first build. Validated the stream patch against `3d7cfe5` in detached
  `/tmp/opencode/shared-rtsp-validation` with its MYVU submodule initialized.
  All 25 focused relay/P2P/control tests passed; ARM64 debug build passed.
- Built/installed the x86_64 APK and instrumentation APK on emulator-5580.
  `GlassesRtspRelayDecodeTest` passed in 11.827 s: actual fixture H.264 over
  RTSP, no video fmtp, two production LibVLC frame sinks, both upstream profiles
  at the fixed endpoint, one source connection/PLAY, surviving viewer after
  the other closes. Evidence: `/tmp/opencode/shared-rtsp-20261003/`, including
  `isolated-build.log`, `emulator-build.log`, `decode-test.txt` and the saved
  `cyanbridge-shared-rtsp-arm64-debug.apk`.
- Physical phone is not connected to ADB; sideload and new relay validation
  on physical glasses remain pending. After the concurrent Walking Aid resources
  landed, the integrated workspace ARM64 build passed in 1m15s with configuration
  cache reused (`integrated-build.log`). The combined APK is saved as
  `/tmp/opencode/shared-rtsp-20261003/cyanbridge-shared-rtsp-debug.apk`.

## 2026-10-02 — HeyCyan live preview hardware peer-matching failure

- Captured the original attempts and a fresh user-driven retest on Samsung
  SM-F956B (`RQCX700KSDF`, Android 15 / API 35). Evidence is under
  `/tmp/opencode/heycyan-live-20261002/`: `before-retest.log`, `retest.log`,
  `wifip2p-after-failure.txt`, and `connectivity-after-failure.txt`.
- The 16:04:18 retest sent `02 01 14 01` and discovered
  `AIMB-G3_C4E3BFC3A402` after 6.6 seconds. LivePreview rejected that peer:
  its BLE identity is `AIMB-G3_A402` / `C4:E3:BF:C3:A4:02`, while its distinct
  P2P address is `60:c2:2a:4c:5b:b5`. No connection request was sent, and
  BLE IP discovery timed out after 45 seconds. RTSP playback was never reached.
- The subsequent ordinary media sync in the same trace matched that peer,
  formed a P2P group, received glasses IP `192.168.49.40`, and downloaded two
  JPEGs successfully. This corroborates the live-specific matching bug.
- Updated LivePreview to reuse `HeyCyanP2pPolicy.matchesOfficialPeer`, the
  existing media-sync full-BLE-MAC matcher, preferring available matching peers.
  Added coverage for the observed AIMB identity, the report's M02S naming form,
  and rejection of an unrelated full MAC sharing the same short suffix.
- Reference: https://gist.github.com/vortex1024/2a9e0bada7692900a3bb4285362d8a2a
  confirms the branch's start/stop opcodes, `8554/ch0`, and LibVLC transport.
  End-to-end preview validation of this peer fix is pending the patched retest.

## 2026-10-02 — Live preview duplicate connect and local build tuning

- The peer-fix build passed all eight focused unit tests and was sideloaded to
  `RQCX700KSDF`. Its build took 27m38s. The project previously limited Gradle
  and Kotlin to 2 GB heaps, disabled parallel projects, and wrote generated
  outputs to the NTFS HDD while an unrelated tape-backup job read that disk.
- The patched 16:47:49 and 16:48:00 attempts successfully matched the full-MAC
  P2P peer. Samsung sent a disconnected broadcast immediately after accepting
  the first connect request, clearing the generic manager's connecting flag.
  A subsequent invited-peer broadcast triggered a duplicate connect, returning
  Android BUSY (`reason=2`); LivePreview treated it as terminal and tore down
  the negotiating group. Evidence: `patched-retest-focused.log` and `retest.log`
  under `/tmp/opencode/heycyan-live-20261002/`.
- Added preview-owned connection-request state to retain the pending request
  across startup disconnect / unformed-group broadcasts. Established-group
  disconnects remain terminal. Regression coverage replays this sequence.
- Enabled Gradle build/configuration caches and parallel projects with 24
  workers, 8 GB Gradle heap and 6 GB Kotlin heap. Added
  `tools/hil/build_debug_fast.sh` to place app/composite/native generated files
  and the project cache on the host SSD. `testAbi` now also filters native llama
  builds, avoiding an unused x86_64 build for ARM64 phone debugging.
- The fresh SSD/ARM64 build completed in 6m49s with configuration cache stored;
  all 46 selected media/P2P/OTA unit tests passed. Its APK contains only
  `arm64-v8a`, is 189.6 MiB, and was installed successfully on `RQCX700KSDF`.
  Build evidence: `fast-build.log`; profile:
  `~/.cache/cyanbridge/build-c42ce86fe7b6/CyanBridgeManagerApp/root/reports/profile/profile-2026-10-02-16-59-22.html`.
- Kept SSD native staging outside Gradle's build directory so `clean` preserves
  the expensive native compilation state; the staging-path update built in
  1m44s. The identical warm invocation then completed in 11s, reused the
  configuration cache, and reported 113 tasks up-to-date / three executed.
  Evidence: `fast-build-staging.log` and `fast-build-warm.log`.
- Documented that llama's local-source override is optional: normal CMake
  FetchContent downloads the pinned, SHA-256-verified upstream automatically.
  The no-override build passed in 2m46s (`no-llama-override-build.log`).
- The 17:27:00 and 17:27:33 hardware attempts confirmed both prior fixes:
  a single connect request formed the group in eight seconds, followed by BLE
  IP `192.168.49.40`. Android exposed `p2p-wlan0-0` with source `192.168.49.1`
  but rejected all process-bind attempts, so the app aborted before RTSP.
  Evidence: `second-patched-focused.log`.
- Added a direct-route probe for this platform behavior. Playback is allowed
  only after a TCP connection to the BLE-reported glasses endpoint uses a local
  address on the verified P2P Network. An ordinary Wi-Fi route or missing P2P
  interface cannot satisfy the gate. The no-override build passed in 7m7s with
  all 15 focused tests passing (`direct-route-build.log`); installed successfully
  on `RQCX700KSDF`, marked `HEYCYAN_DIRECT_P2P_ROUTE_FIX_INSTALLED` in logcat.
  End-to-end hardware playback validation is pending the next user retest.

## 2026-09-27 — Laya/Needle local training pilot

- Follow-up data audit after the synthetic transfer regressions: the available
  local logs contain only six structured `JEV_SHADOW_STATE` decision frames,
  all from one YouTube episode. Older Chrome HIL logs expose repeated
  candidate lists from failed runs but lack independently verified next-action
  and post-action labels. Do not retrain on those logs or on the now-inspected
  held-out cases; collect new episode-disjoint Tasker observations first.
- Created a deterministic **synthetic** English phone-UI decision dataset,
  `android/CyanBridge/training/phone_ui_synthetic_v1/` (576 train, 128
  validation, 128 test decisions per model). Inputs and answers are grouped by
  episode and split by disjoint invented names; explicit no-action/ambiguous
  cases are included. The 169-case generated calibration benchmark and six real
  Tasker states are *not* trained on. Tool IDs for duplicate operations are
  assigned after shuffle to avoid leaking the target's role in its ID.
- Trained a **frozen-encoder Laya choice head** (four epochs, CUDA) using
  `tools/benchmarks/train_laya_phone_ui.py`. Synthetic test top-1 56.3% base →
  86.7% trained. On the six *untouched* Tasker YouTube states, base Laya 5/6 →
  trained 4/6; both failed to defer at the playing-video state. The synthetic
  gain does not transfer to this real episode. Reversing option order dropped
  each model to 3/6 on the same six frames. The frozen 13-locale corpus
  remained unsuitable for auto action: raw held-out 42.5% base versus 45.0%
  trained, **zero** safe actions at its 98% precision gate. Artifacts under
  `/tmp/opencode/phone-ui-models/laya-head-v1/` and read-only reports under
  `/tmp/opencode/phone-ui-laya-*-real-shadow*.json`.
- Needle 3 base exact-call accuracy on the synthetic test: 59/128 (46.1%),
  0/32 for no-match or ambiguous inputs. On the real six-state trace under
  a separate first-four-visible-controls renderer, the 20-layer base chose
  only 1/6 reviewed next steps (host shadow, no execution). A local 20-layer
  LoRA in a dedicated non-base Python environment reached 80/128 validation
  and 83/128 synthetic test exact calls; missing-control no-call remained
  **0/16**. On the same real-state renderer it reached 2/6 and failed at the
  player DONE state. In a serial host-only rerun of 128 test rows, Needle
  p50 was 394 ms base versus 357 ms tuned, not an Android measurement.
  On the frozen 13-locale generated corpus, Needle's held-out raw top-1
  **regressed from 28/40 (70.0%) to 19/40 (47.5%)**; both had zero automatic
  actions at the 98% precision gate, and tuned confidence is unavailable.
  Weights at `/tmp/opencode/phone-ui-models/`; keep them
  shadow-only. See the dataset README for commands and provenance.

## 2026-09-27 — Laya/Needle training research and Jev browser comparison

- Read the official Laya browser fine-tuning write-up and published pipeline,
  Needle 3 fine-tuning/tool-design/runtime/device guides, the open-source
  `browser-use/jev-ultrafast` loop, and Ship With Jev's independent catalog and
  engineering guide. Wrote a concrete phone-specific data, training and
  evaluation recipe in `android/CyanBridge/docs/LAYA_NEEDLE_PHONE_TRAINING.md`,
  linked from the model docs. Key findings: train Laya operation/compatible
  target heads using real post-action DONE and mid-task negatives; current v17s
  adds synthetic long-form episodes and harness fixes, and its model card
  discloses that earlier DAgger samples leaked evaluation goals. Train Needle
  on one-step `query`/`tools`/`answers` plus no-call examples; local Needle LoRA
  does **not** preserve calibrated confidence. Measure candidate recall before
  model accuracy and verified episode outcomes before giving either model any
  action authority. The six-state replay is too small to fine-tune reliably.

## 2026-09-27 — Tasker accessibility framing for Laya / Needle

- Read Laya's official browser-agent training example and Needle's official
  tool design, Python and confidence guides. The prior host replay duplicated a
  long multi-step goal in state and prompt, ranked options by whole-goal terms,
  exposed arbitrary first-three controls, and named Needle tools `action_a`.
  Laya's browser example specifically found that moving element details from
  state to option criteria improved accuracy; its base model needed
  task-specific training. Needle expects short current requests, narrowly named
  tools and evidence-grounded arguments, and may validly emit multiple calls for
  a multi-step request. Details and source links are in
  `android/CyanBridge/docs/TASKER_SMALL_MODEL_CONTEXT.md`.
- Added a **host-only, read-only** current-step renderer for the six captured
  Tasker YouTube states (`tools/benchmarks/phone_accessibility_frame.py`,
  `compare_laya_needle_live_shadow.py --frame compact`). With 1–2 grounded
  controls per step, both checkpoints selected the intended candidate on five
  steps and the player step delegated without inference; two visible suggestion
  options still yielded the exact suggestion when their order was reversed.
  **This is mostly code prefiltering, not a demonstrated 5/5 model accuracy:**
  four of five inferred steps had only one action tool. Workstation warm medians
  were ~40 ms Laya CUDA and ~0.21 s Needle CPU in the latest replay (host load
  varies); no Android runtime/agent
  completion measurement. Report:
  `/tmp/opencode/jev-laya-needle-compact-frame.json`. Keep the production Gemma
  state/gate unchanged until calibration on independent episodes and HIL.

## 2026-09-27 — Laya/Needle live-state shadow trial and Gmail gate

- The user's suspicion about an extra CyanBridge approval gate was checked against
  the real email trace: the ambiguous voice reply remained pending, an explicit
  affirmative reply moved the high-risk `SendEmail` to executed, and Tasker's
  `ACTION_SENDTO` opened the Gmail external composer. **There was no second
  CyanBridge confirmation gate after that approval.** A separate read-only HIL
  proved AutoInput/Tasker never returned a composer observation, so CyanBridge
  correctly did not guess a Send tap; no delivery is established.
- Reimported a profile with bounded per-node text and summary. A fresh opt-in
  `TaskerGmailComposerObserveHilTest` still timed out after 32 seconds on a
  harmless `example.invalid` SENDTO draft, with **no send operation**. Temporarily
  restricting AutoInput UI Query to Gmail and temporarily including invisible
  nodes each also timed out; both settings were restored and the original
  profile reimported. Traces: `/tmp/opencode/jev-gmail-bounded-observe.log`,
  `/tmp/opencode/jev-gmail-filtered-observe.log`, and
  `/tmp/opencode/jev-gmail-all-visible-observe.log`. Do not run another real-email
  send test until a Tasker-observed composer succeeds and the independent
  self-delivery check can run.
- Added a **debug-only, opt-in** `JevShadow` trace of bounded candidates and
  Tasker-observed YouTube state. Replayed six real consecutive decisions through
  the previously provisioned multilingual Laya checkpoint on host CUDA and
  Needle 3 host CPU. This replay is read-only: no model output can reach Tasker
  or bypass CyanBridge policy. Three actionable options plus planner fallback:
  Laya warm p50 ~41 ms/decision (first ~554 ms); Needle p50 ~1.24 s/decision,
  with invalid multiple calls at two of six steps. Laya chose “Navigate up” at
  the suggestion step and “notifications are off” at a video-player step;
  Needle and Laya diverged repeatedly. Two-action and seven-action Laya
  exploratory variants changed the choices, but also gave wrong navigation
  suggestions. Reports: `/tmp/opencode/jev-laya-needle-live-shadow.json`,
  `/tmp/opencode/jev-laya-needle-live-shadow-2actions.json`, and
  `/tmp/opencode/jev-laya-all-needle-3-live-shadow.json`. These are **host
  inference timings and hypothetical choices, not Android inference or either
  model finishing an end-to-end workflow**. The available Pixel is x86_64;
  Needle's released Android runtime is ARM64-only. A real ARM64 target and
  trustworthy calibration are needed before granting either model action
  authority.
- The Pro/Gemma-controlled YouTube HIL passed again in 22.9 seconds. Its
  independent Tasker oracle now accepts positive *advancing time* on the same
  requested video when YouTube hides the Pause overlay between polls, never a
  static progress indicator. Trace:
  `/tmp/opencode/jev-laya-needle-youtube-shadow-hil3.log`. The first shadow
  capture run reached service-observed playback but its older test oracle
  timed out; the improved oracle passed the later run.
- Paused the host USB-tether watchdog and GitHub Actions runner only during
  Android HIL, then restored both. The authenticated `Pixel_9a` remains bootable
  with its private checksum-verified offline backup intact.

## 2026-09-27 — Pro HIL, model comparisons, preserved Pixel

- **Preserved licensed Pixel:** stopped `Pixel_9a` and copied its entire AVD plus
  `.ini` to private `/home/fertroll10/.android/avd-backups/Pixel_9a-pro-20260927/`
  (14 GB, directories mode 700 and files mode 600). The offline userdata image
  SHA-256 matches the original before it was rebooted:
  `e16a5b3de0c171320f889d6af9c4bf1efb09d61a6d565e2c2abcdbddb358cf2e`.
  `/mnt/seagate` ignored mode restrictions, so its interim backup copy was removed
  after the private copy's checksum matched. Original rebooted with the same userdata,
  CyanBridge and licensed Tasker installed; no wipe or paid-app sign-in. Continue
  replacement installs only. The HIL uses `emulator-5554` (Android x86_64).
- **Cloud YouTube HIL passed:** Pro-planner/EmbeddingGemma-gated run reached
  independently observed requested playback in 44 seconds; trace
  `/tmp/opencode/jev-pixel-pro-youtube2.log`. Do not interpret this as a Laya or
  Needle run. The embedding gate can abstain and delegate to Pro.
- **Cloud email HIL remains incomplete:** after grounding the Chrome fixture,
  the run reached spoken readback, clarification after ambiguous reply, explicit
  approval, and executed `SendEmail` handoff. The original Tasker `composeEmail()`
  opened Android's generic share resolver, then returned to Chrome. The Tasker
  profile now requests `ACTION_SENDTO` with a `mailto:` URI; a harmless probe
  opened Gmail's composer. An approved-email continuation prevents re-drafting,
  re-approval, or repeated Send taps, and checks visible recipient/subject before
  any UI Send action. Targeted unit tests, Android-test compilation, Tasker
  profile validation and Tasker observer/executor smoke passed. The subsequent
  Pro email HIL reached `approved_action_executed`, then timed out: AutoInput
  observation of Gmail's external composer produces **no Tasker callback** (even
  with a 65-second read-only diagnostic request). The same observer works on the
  launcher and Gmail welcome screen. The composer was independently visible via
  `uiautomator`; **no Send tap or self-delivery was observed**. Traces:
  `/tmp/opencode/jev-pixel-pro-email3.log`, `jev-pixel-pro-email4.log`,
  `jev-pixel-pro-email-sendto.log`, `jev-gmail-composer-long-observe.log`.
  Do not launch another real-send HIL until Gmail composer observation works;
  Tasker/AutoInput must be the action/observation path, and old approved records
  or drafts must not be auto-rejected/re-sent.
- **Laya and Cactus Needle rechecked on the frozen corpus (host, not phone):**
  multilingual Laya CUDA: 42.5% held-out raw top-1, 0 safe automatic actions,
  median 51.0 ms/decision (p95 64.6). Needle 3 Linux host CPU: 70.0% held-out
  raw top-1, 0 safe automatic actions, median 585 ms/decision including tool
  init (p95 1,151 ms). Both met the ≥98% calibrated auto-precision target only
  by abstaining entirely. Reports:
  `/tmp/opencode/laya-mobile-actions-recheck-20260927.json` and
  `/tmp/opencode/needle3/mobile-actions-recheck-20260927.json`. These are
  **not end-to-end run times**. Needle ships an ARM Android runtime but not an
  x86_64 one for this authenticated AVD; Laya's LiteRT Android host is external
  and task-specific safety calibration has failed. Neither is authorized to
  make live UI decisions on this Pixel. Do not claim either completed YouTube or
  email; an ARM test target and calibrated integration are still required.
- The USB-tether watchdog and user GitHub Actions runner were restored after
  HIL; watchdog PID 1817 is running and `github-actions-runner.service` is active.

## Current handoff: Jev-like local agent (2026-09-26)

This section supersedes the older `tasker-integration-polish` handoff below for
the current branch. The historical August instructions refer to a different
worktree/branch and should not be used as the current emulator or CI state.

- **Branch:** `jev-like-local-agent`; clean before this worklog update, HEAD
  `4551deb`, one commit ahead of `origin/jev-like-local-agent` and **not pushed**.
- **Architecture:** Tasker/AutoInput observes the phone and executes grounded
  actions. CyanBridge constructs bounded legal candidates, applies policy,
  plans, asks for approval, and verifies outcomes. Do not request CyanBridge's
  own accessibility service. Open-ended text goes to its local LLM; sending
  email requires explicit approval before Tasker executes Send.
- **Best current fast-path candidate:** EmbeddingGemma 300M Q8 with cosine
  top-two margin ≥0.10, cascading uncertain steps to the local-LLM planner.
  On the frozen 169-case/13-locale corpus it scored 61% raw top-1 on model
  calibration cases and 70% on held-out model cases; accepted actions were
  48/48 and 16/16 correct, respectively, at ~46% actionable coverage. This is
  small, structurally related benchmark evidence, **not** ≥98% real-world
  precision or end-to-end task success. Qwen embedding was slower and weaker;
  Needle and multilingual Laya had confident wrong choices and no safe
  standalone automatic-action threshold on that corpus. Needle may be tested
  as an optional Gemma agreement gate, not a sole decider.
- **Live mismatch:** actual YouTube/Tasker screen states gave Gemma margins
  ~0.001–0.013, so the production gate abstained rather than accelerating.
  A short production-description probe showed removing `(node N)` text did
  not fix this; long/noisy state is the stronger suspect. Shorten/ground state,
  collect production-like snapshots and legal candidate sets, then recalibrate
  on the exact live representation without weakening send-approval or
  independently observed completion checks. Review disputed corpus labels
  separately and score stage progress as well as safe auto-action precision.
- **End-to-end status:** Tasker observation/click/type HIL passed, but the
  embedding-backed YouTube playback run ended `max_steps_reached` after gate
  abstentions and an LLM Voice Search mistap. Email tests did not reach the
  approval/send success path due to slow planning, detours, or `Brain call
  timed out`. Neither workflow is validated end to end; rerun YouTube without
  concurrent emulator activity, then separately validate draft → spoken
  approval → Send → independently observed delivery. Do not automate paid-app
  authentication or wipe the provisioned Pixel_9a AVD.
- **Release path still needed:** choose/package the patched embedding runtime
  and matched model/tokenizer outside Git/default CI; provide user-facing local
  model acquisition and Tasker/AutoInput profile, permissions, and readiness
  setup. Embedding preferences are currently default-off and enabled only for
  tests. Keep model-free tests separate from opt-in Tasker HIL; external Artemis
  fixture smoke uses a different emulator and does not prove CyanBridge HIL.
- **Unmeasured:** ARM Needle latency on a physical ARM64 target; Laya Android
  accuracy/latency on a physical GPU device. Host GPU timings are not phone
  timings. Delay any claim that one backend can finish user tasks until both
  real workflows pass with grounded state checks.

**Next actions:** capture representative Tasker snapshots; fit the Gemma gate
on shortened live states; rerun undisturbed YouTube HIL; troubleshoot the email
planner and run approval/send HIL; document install/onboarding and provisioned
model delivery. Push `4551deb` only when the shared CI emulator is available.

### Update 2026-09-26 — Jev patterns applied, ADB root cause, HIL reruns (still no pass)

### Cloud-planner follow-up (2026-09-26)

- CPU inference on the Pixel is slow enough to hit both the decision and 60-second brain deadlines. The new classifier state is bounded to 600 chars of current controls, and explicit observed first-result/YouTube exact-suggestion taps skip an unnecessary model call. Chrome site-info popups are dismissed and re-observed rather than offering their warning text as clickable task options. Article email generation now receives a short evidence-only prompt (with recipient/subject code-checked) and 200 output tokens, still yielding a HIGH-risk SendEmail pending action; the service brain deadline is 90s. Unit regressions and HIL Kotlin compilation passed.
- `CYANBRIDGE_HIL_PRO_PLANNER=true` selects Pro for the existing YouTube/email HIL; a missing linked account or non-active server entitlement fails rather than silently falling back to CPU. Added `ProPlannerReadinessHilTest` (token presence and fresh server verification only, no quota use). Tests do not log tokens.
- Examined spare AVDs read-only. `Medium_Phone_API_36` has no Pro prefs or Tasker. `CyanBridge_Walking_Aid_CI` has the owner's verified email, Tasker/AutoInput, Gemma GGUF, and **passes the server Pro-readiness test**, but Tasker itself displays **Trial Over**. Its Tasker readiness probe failed despite branch profile import; do not automate paid-app activation. Both read-only alternate emulator instances were shut down. The licensed `Pixel_9a` had no Pro Subscription prefs before setup; link it through the existing `ProAccountSetupHilTest#requestVerification` / `#verifyAccount` with a real user-provided code, not by copying the alternate AVD's encrypted secrets.
- An interrupted Pixel email instrumentation remained active until its natural 10-minute timeout; it clicked Chrome's site-info warning instead of the first result and **did not request approval or send**. Avoid duplicate email HIL launches while any instrumentation remains active.

- **Ship-with-Jev patterns applied:** fresh bounded A–H actions rebuilt from each
  Tasker observation (never reuse saved coordinates); prose/summary left to the
  detailed local-LLM planner; stage-level `JEV_HIL_STAGE` logging added to both
  HIL tests so failures report `reached=[...]`; playback evidence tightened to
  word-boundary `pause`/`playing` (paused/progress alone no longer counts);
  `run_instrumentation.sh` now refuses auto-retry for local-AI/email classes so
  a lost ADB result cannot overlap two planners or double-send email.
- **ADB drops root-caused:** host `/usr/local/sbin/usb-tether-watchdog` treats
  `emulator-5554` as a tethered phone and repeatedly runs
  `svc usb setFunctions` / Wi-Fi resets on it. Each USB reset restarts emulator
  `adbd` (`adbd socket disconnected`, new `adbd` pid, ~1 s outage) and breaks
  `am instrument`. Host has no `usb0` and routes via Ethernet. Watchdog was
  `STOP`ped for HIL and `CONT`ed afterward; self-hosted runner was stopped and
  restarted. Do not run HIL while that watchdog targets the emulator.
- **YouTube (embedding-gated):** with CI paused, reached
  `tasker_observation_ready → youtube_foreground → requested_content_visible`
  (opened YouTube, tapped Search, typed “Linus Tech Tips”, tapped a suggestion).
  Then stalled on search-results navigation and ended `Brain call timed out`
  after a 60 s single-token generation timeout; last visible text was a
  YouTube Premium upsell. Typed-search candidates now exclude Voice Search.
- **Email (deterministic fixture):** three 10-min runs, ADB stable. Fix 1
  suppressed omnibox retyping on results pages (address-bar node excluded when
  results visible); run 12794 then reached the article and offered
  `Tap Cobalt Horizon first result` as A. Fix 2 suppresses all typing when the
  grounded-answer planner is ready, so the article now leads with
  `Use the detailed planner now…` as A (run 13968). That planner then exceeds
  the 60 s `BRAIN_TIMEOUT_MS` on the emulator CPU (Qwen2.5 0.5B, ~35 s per
  single-token step), so the run ends `Brain call timed out` before a draft or
  approval exists. No email was prepared, approved, or sent in any run.
- **Tests vs strictness:** keep send-approval, no-pre-send Gmail, and observed
  post-send completion strict. The useful relaxation is stage reporting plus
  valid-alternate-tap review, not weaker safety. Next levers are planner
  latency (prompt length, token budget, model speed, timeout policy) and
  result-first candidate ordering (the fallback LLM picks A almost every step),
  then a fresh undisturbed rerun of both workflows.

**Pointers:** `android/CyanBridge/docs/JEV_LIKE_ANDROID_EMBEDDINGS_NEXT_STEPS.md`,
`android/CyanBridge/calibration/README.md`,
`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/`,
`tools/benchmarks/benchmark_laya_mobile_actions.py`,
`tools/hil/build_embedding_runtime_aar.sh`,
`tools/hil/run_artemis_fixture.py`. The frozen corpus is
`android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl`.

---

## Historical handoff: Tasker-Integration-Polish AI/Gmail CI Tests

Updated: 2026-08-23 (session 2, context limit reached — CONTINUE FROM "Next Steps")

## Mission

Make the new CI tests on branch `tasker-integration-polish` actually green — not "green because skipped". Specifically:

- `LocalAiTaskerChromeHilTest` — CyanBridge local model plans, Tasker/AutoInput drives Chrome against a deterministic web fixture, answer must be grounded in observed page facts.
- `LocalAgentEmailApprovalHilTest` — full real-email flow: Chrome research → summary → SendEmail queued as HIGH-risk → voice prompt → "maybe" gets clarification → literal "yes" approves → Tasker executes Gmail compose+Send → unique `CB-HIL-<ts>` subject must appear in Gmail. Recipient is the user's own account: `fernandosaiyan10@gmail.com` (user explicitly authorized real sends).

Both tests are gated by repo variables that are currently UNSET, so the last branch CI run (32656567419, both workflows success) skipped them. Default gates are green; the two optional layers have never run.

## Current Status

- Branch/worktree: `/tmp/opencode/HeyCyan-tasker-polish`, branch `tasker-integration-polish`, tracking `origin/tasker-integration-polish`, HEAD `617f8e7` + local UNCOMMITTED fixes (see below).
- Core 5-class HIL suite passes on emulator (`OK` per isolated class): fixture smoke, LocalAgent, AutoDiary, AiTaskerProfile, VisualDiary.
- Gmail on emulator `emulator-5554`: SIGNED IN as fernandosaiyan10@gmail.com; first-run screens completed (welcome tour GOT IT → TAKE ME TO GMAIL → notifications Allow → Meet Got it).
- Local model: **currently NOT installed** (deleted to free storage during APK reinstall). Must re-download via catalog UI before running either AI test (exact steps below).
- Web fixture: running on host port **18765** (8765 is occupied on this host by an LTFS server, pid 3381957 — that is also a real CI hazard, fixed in workflow, see below). Process: `nohup python3 tools/hil/serve_web_fixture.py --port 18765` (log `/tmp/opencode/tasker-polish-web-fixture-18765.log`). `adb reverse tcp:18765 tcp:18765` was set; re-run after any emulator restart. Chrome was prelaunched at `http://127.0.0.1:18765/` and marker `CYANBRIDGE_HIL_WEB_SEARCH_72941` verified visible, then HOME pressed.
- Build: `:app:assembleDebug :app:assembleDebugAndroidTest` green (needs both submodules initialized, see below).

## Uncommitted Fixes In The Worktree (all verified locally, need commit+push)

1. `.github/workflows/android-tasker-hil.yml` — fixture port collision fix:
   - Old code hard-coded port 8765; on this self-hosted runner `127.0.0.1:8765` is already used by `ltfs_web.py`, so `serve_web_fixture.py` died with `Address already in use` and the health check would pass against the WRONG server (it only checked HTTP 200).
   - New behavior: allocate an ephemeral host port, `echo port=... >> $GITHUB_OUTPUT` (step id `web_fixture`), `export CYANBRIDGE_HIL_FIXTURE_PORT`, health check requires body marker `CYANBRIDGE_HIL_WEB_SEARCH_72941`, `adb reverse tcp:$port tcp:$port`, Chrome opened at `http://127.0.0.1:$port/`, "Reset Chrome fixture" and "Stop fixture" steps use `steps.web_fixture.outputs.port`.
2. `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localmodels/device/DeviceCapabilityService.kt` — unit fix: replaced `GIB = 1024^3` divisor with `GIGABYTE = 1_000_000_000.0` (catalog `minRamGb`/`minStorageGb`/`sizeBytes` are decimal GB). Before this, a nominal 4 GB AVD reported 3.82 "GB" and every 4 GB-tier model was rejected ("RAM unsuitable: device has 3.8 GB, model needs at least 4.0 GB").
3. `android/CyanBridge/shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/localmodels/catalog/LocalModelCatalog.kt` — qwen2.5-0.5b entry: `minStorageGb` 1.0 → 0.75 → **0.5** (with comment). Rationale: 0.5B model is ~0.47 GB; the 6 GB emulator data partition hovers around 0.5–0.8 GB free with Chrome/Gmail/Tasker resident, so a 1.0 GB post-install floor made the supported starter model permanently unloadable. Download headroom check (size+0.35 GB) is separate and still enforced.
4. `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localmodels/download/LocalModelDownloadManager.kt` — downloader hardening: OkHttp client now `.protocols(listOf(Protocol.HTTP_1_1))` (Hugging Face large-file redirects intermittently reset HTTP/2 streams on Android: `StreamResetException: stream was reset: CANCEL`), and the retry loop catches `java.io.IOException` (covers StreamReset/Socket/DNS) instead of only SocketException+UnknownHostException. Verified: catalog download then completed ("Download complete", Status: ready).

Static checks pass: `python3 tools/hil/validate_tasker_profiles.py`, `bash -n tools/hil/*.sh`, `git diff --check`.

## Submodule Note (fresh worktrees)

This branch adds a second submodule. Both must be initialized or the build fails:
- `third_party/moonshine` @ 79b0217 (missing → `cmake.path ... doesn't exist`)
- `android/CyanBridge/app/src/main/myvu-upstream` @ 66ec6f6 (missing → dozens of `Unresolved reference 'myvu'`)

```bash
git submodule update --init --recursive
git -C third_party/moonshine lfs pull --include="core/speaker-embedding-model-data.cpp,core/third-party/onnxruntime/lib/android/arm64/libonnxruntime.so"
```

CI is fine (workflow checks out submodules recursively + LFS pull); this is only for local worktrees.

## Reproduction Trail (what was observed, in order)

1. `LocalAgentEmailApprovalHilTest` with `CYANBRIDGE_HIL_EMAIL_SEND=true` → fails safely at prereq: "No CyanBridge local model is installed/selected for the email automation HIL" (no email sent).
2. Imported the 0.5B GGUF via app UI (Import model file → Downloads → pick file). Import path has NO capability gate, but load-time gate then crashed the test app: `RAM unsuitable ... 3.8 GB` → fixed by decimal-GB change (#2).
3. After #2, load gate: `Not enough free storage. Need about 1.00 GB` (custom imports fall back to hardcoded min 4.0 RAM/1.0 storage in `LocalChatSessionManager.ensureModelLoaded` capabilityEntry) → decided to use the CATALOG download instead (catalog floor now 0.5).
4. Catalog download failed twice with `stream was reset: CANCEL` (HTTP/2) → fixed by #4; then downloaded successfully via the app UI.
5. `LocalAiTaskerChromeHilTest` then reached model load and failed only on the storage floor (0.75) → lowered to 0.5 (#3). APK replacement then hit `INSTALL_FAILED_INSUFFICIENT_STORAGE`; recovered by deleting `files/local_models` via `run-as` + `cmd package trim-caches 2G` + reinstall (Success).
6. **Stopped here**: about to re-download the model via catalog UI and rerun the local-AI test.

## Next Steps (in order)

1. **Re-install + re-download model** (emulator `emulator-5554`):
   - APKs are already installed (patched build). If needed: `bash tools/hil/install.sh emulator-5554` from the polish worktree.
   - Free storage first if low: `adb shell cmd package trim-caches 2G`; check `adb shell df -k /data` (need ≥ ~1.2 GB free for download headroom 0.4+0.35 GB; after install ~0.5 GB is enough to LOAD).
   - UI recipe (coordinates are for this 1080x2424 AVD, verified working):
     1. `adb shell monkey -p com.fersaiyan.cyanbridge 1` (launch; dismiss any permission dialog with Allow at ~(540,1325))
     2. Tap Settings tab: (980,2190)
     3. Swipe up: `input swipe 540 1900 540 700 500`
     4. Tap "Configure local models" row parent (text bounds ~[147,1140..1282]; tap ~(300,1193) — re-dump `uiautomator` and compute from `text="Configure local models"` if unsure)
     5. In Local models screen: tap "Curated catalog" header to expand (~(500,1157) or wherever the header is; verify `Collapse Curated catalog` appears)
     6. Swipe down: `input swipe 540 2200 540 700 600` until `text="Qwen2.5 0.5B Instruct (Q4_K_M)"` visible with `Device suitable`
     7. Tap its **Download** button — it is the LEFT button of the pair, e.g. bounds [84,1335][529,1461] → tap (300,1387). (The right button is "Info".)
     8. Wait ~90 s; verify `Status: ready` and "Download complete". If "stream was reset" appears, the HTTP/1.1 fix isn't installed — rebuild/reinstall first.
   - Fallback (if catalog download keeps failing): `adb push /tmp/opencode/qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Download/` then app → Local models → "Import model file" → Downloads → tap the file card (left card, e.g. (300,1000)). Caveat: imported-custom models use hardcoded floors (4.0 RAM/1.0 storage) in `LocalChatSessionManager` — with the decimal-GB fix RAM passes (4.1 GB), but storage needs ≥1.0 GB free; trim caches or free space first. Catalog download is preferred.
2. **Run local-AI test** (fixture must be up):
   ```bash
   nohup python3 tools/hil/serve_web_fixture.py --port 18765 >/tmp/opencode/tasker-polish-web-fixture-18765.log 2>&1 &
   adb -s emulator-5554 reverse tcp:18765 tcp:18765
   adb -s emulator-5554 shell am force-stop com.android.chrome
   adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18765/' -p com.android.chrome
   sleep 3; adb -s emulator-5554 shell input keyevent KEYCODE_HOME
   CYANBRIDGE_HIL_LOCAL_AI=true bash tools/hil/run_instrumentation.sh emulator-5554 hardware com.fersaiyan.cyanbridge.hil.LocalAiTaskerChromeHilTest
   ```
   Test must answer with "37", "amber", "cyanbridge", "tasker" (Borealis fixture facts) and end observed on Chrome article marker `CYANBRIDGE_HIL_WEB_ARTICLE_72941`. Debug with `adb logcat -s TaskerLocalAgent LocalChatSession LocalModelDownload` and `run-as com.fersaiyan.cyanbridge cat shared_prefs/local_agent_prefs.xml` (status/last_error). Planner is Qwen 0.5B on CPU — steps are slow; BRAIN_TIMEOUT_MS=60 s per step, test budget 6 min.
3. **Run the real-email test** (user authorized; sends exactly one self-email per run):
   ```bash
   adb -s emulator-5554 shell am force-stop com.android.chrome
   adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18765/' -p com.android.chrome
   sleep 2; adb -s emulator-5554 shell input keyevent KEYCODE_HOME
   CYANBRIDGE_HIL_EMAIL_SEND=true bash tools/hil/run_instrumentation.sh emulator-5554 hardware com.fersaiyan.cyanbridge.hil.LocalAgentEmailApprovalHilTest
   ```
   Preconditions inside the test: NO pre-existing pending actions (assert fails otherwise — resolve via app Pending Actions UI if needed), provider becomes LOCAL_AGENT (or already PRO_SUBSCRIPTION), `RemoteOpenAiPrefs.isActive` false, model installed. Replies "maybe"/"yes" are injected textually via `LocalAgentController.replyToApproval` (no real mic needed), but the voice session still speaks via TTS and listens (silence tolerated; external reply wins).
4. **Fix whatever the tests expose** (product bugs, keep changes minimal), then:
   - `python3 tools/hil/validate_tasker_profiles.py && bash -n tools/hil/*.sh && git diff --check`
   - `./gradlew --no-daemon :app:testDebugUnitTest :shared:portabilityTest`
   - Core suite (5 classes) still green.
5. **Commit** the four files listed above (+ any new fixes) on `tasker-integration-polish` and push. Suggested message themes: fixture port collision + marker health check; decimal-GB capability units; 0.5B storage floor; HTTP/1.1 + IOException retry for model downloads.
6. **Enable CI layers** (requires repo admin; `gh variable list` currently shows NO CYANBRIDGE_* vars):
   ```bash
   gh variable set CYANBRIDGE_HIL_ENABLE_EMAIL_SEND --body true -R FerSaiyan/Alternative-HeyCyan-App-and-SDK
   gh variable set CYANBRIDGE_HIL_ENABLE_LOCAL_AI  --body true -R FerSaiyan/Alternative-HeyCyan-App-and-SDK   # optional but recommended
   ```
   If you cannot set vars, ask the user to add them in GitHub → Settings → Secrets and variables → Actions → Variables.
7. **Verify CI**: push → watch `Android Tasker HIL` run; the previously "skipped" steps (Prepare persistent emulator for AI automation HIL, Prepare deterministic Chrome fixture, Run CyanBridge local AI through Tasker and Chrome, Reset Chrome fixture, Run approved Gmail self-send HIL) must now RUN and PASS. Note the runner's persistent AVD is presumably this same machine's AVD (self-hosted homelab runner) — local emulator state (Gmail sign-in, installed model, Tasker entitlements) IS the CI state; keep it healthy, never wipe.
8. Update this worklog with final results.

## Operational Notes

- ADB on this API 37 emulator drops constantly (`error: device offline/not found`). Pattern that works: `adb kill-server; adb start-server; adb -s emulator-5554 wait-for-device` before each batch; retry failed taps once.
- `run_instrumentation.sh` already: dismisses the 16 KB compat dialog, splits comma-lists into one process per class, and recovers offline emulators via `tools/hil/start_emulator.sh` (cold boot, userdata preserved).
- Storage is the recurring constraint (6 GB /data, ~80–92% used). Recovery recipe that worked: `adb shell am force-stop com.fersaiyan.cyanbridge; adb exec-out run-as com.fersaiyan.cyanbridge sh -c 'rm -rf files/local_models'; adb shell cmd package trim-caches 2G; adb install -r -d <apk>`.
- Gmail/Tasker/AutoInput state, Google account, and accessibility grants must never be wiped (`pm clear`, `-wipe-data` are forbidden).
- The email test's Tasker side executes the approved SendEmail through the Local Agent Tasker project (`android/CyanBridge/tasker/CyanBridge_LocalAgent_Tasker.prj.xml`); if execution fails, check `TaskerExecutionBackend.execute` results in the pending-action record and the Tasker run log. The planner prompt requires a second observation proving compose is gone before finishing.
- Repo variables/secret context: `META_GITHUB_TOKEN` secret exists; `CYANBRIDGE_HIL_*` vars do not (as of this writing).

## Constraints (unchanged)

- No subagents. No credential exposure. No billing/entitlement bypass (Tasker/AutoInput entitlements were legitimately restored earlier via the user's own AutoApps purchases).
- Real email ONLY to `fernandosaiyan10@gmail.com`, one per test run, unique `CB-HIL-<ts>` subject, body labeled as deterministic fixture data.
- Do not `pm clear` CyanBridge/Gmail/Tasker; do not wipe the AVD; do not force-push without approval.

## Real UI collection (2026-09-27, Artemis isolated AVD)

- Created `CyanBridge_Artemis_Data` (Android 36, `emulator-5560`) separate from authenticated `Pixel_9a`. The collector verifies AVD name, boot, absence of Tasker/AutoInput and absence of Android accounts before actions. Artemis checkout revision `371aa6df56880643da57b30da936e9812fb0ec66` supplied `AndroidAdbDriver` + UIAutomator2; no Artemis agent, LLM credentials or accessibility helper were used.
- Collected 14 verified Settings navigation episodes, 5 Clock selected-tab episodes, and 10 YouTube **query-field-only** episodes. Every labeled action has pre/post screenshot and hierarchy hashes, a currently observed binding, and a separate checked postcondition. Unverified Settings transitions into other packages and YouTube's failed network search were excluded. Raw screenshots/XML remain in `/tmp/opencode/artemis-phone-captures/`, not Git.
- Assembled `android/CyanBridge/training/artemis_real_v1/pool.jsonl` and manifest: 29 episodes, 82 decisions (43 TAP, 10 TYPE_LITERAL, 29 DONE), 40 screen signatures, just **three connected screen families**. `readyForIndependentSplit=false` and `readyForTaskerTraining=false`. These rows were **Artemis-observed**, not Tasker-observed; no Laya/Needle retraining was performed on them.
- Python collector/audit suite: 9 tests passing. Android `:app:compileDebugAndroidTestKotlin` passed with `ANDROID_HOME` set. Added an opt-in Tasker Settings parity HIL test to measure whether a target seen by Artemis is also present and retained in the real Tasker candidate set; Tasker execution result must be measured separately.

## Tasker Settings source parity and verified episode (2026-09-28)

- On `Pixel_9a`, Tasker/AutoInput observed 20 Settings nodes and CyanBridge retained `Network & internet` among seven candidates. The opt-in read-only parity HIL passed.
- A first Settings-navigation trial had **already resumed the Network page before the tap**. Its post markers looked good, but the audited pre-action state exposed a false positive; the export was discarded. The final test explicitly starts Settings HOME and refuses an already-open destination, missing Tasker markers, unchanged hashes or stale timestamps.
- The HIL emulator-recovery helper reused `emulator-5554` for `CyanBridge_Walking_Aid_CI` hours later. An AVD-identity check prevented an invalid Pixel export. `CYANBRIDGE_HIL_ARTEMIS_PARITY=true` now pins `Pixel_9a` and fails closed on offline/wrong AVD before and after instrumentation. Pinned HIL passed on `emulator-5562` without clearing app data.
- The final allowlisted Tasker diagnostic is `android/CyanBridge/training/tasker_real_v1/settings_network.json` (episode `tasker_settings_network_1790601149125`). Tasker pre-state: Settings home. Tasker action: tap `Network & internet`. Tasker post-state: `Internet` and `SIMs`, changed hash; separate ADB UIAutomator dump confirmed page title and `Airplane mode`. Pre-observation to verified post-observation: **1,821 ms**; action start to verification: **1,734 ms**. This is one diagnostic episode, not a trainable split or a planner comparison. No email was sent.

## 2026-09-29/30 — AudioRecord replaces Google STT for glasses questions (in progress, hunks parked)

> Historical implementation note. The 2026-10-01 review below corrects the
> diagnosis, stash reference, existing-VAD inventory, and readiness assessment.

### Goal
Remove the system Google `SpeechRecognizer` from the glasses image-question and
voice-question flows. Field logs showed its server-side speech-onset deadline
killing the Ask window ~0.6-0.9 s after the cue (`NO_SPEECH_DETECTED` -> error 7),
while the app's own 3.3 s window never fired. Replacement: `AudioRecord`
capture with on-device energy endpointing, feeding audio + image (+ text
instruction) straight into the multimodal LiteRT turn. No transcription
fallback (rejected: latency, VRAM/RAM contention, offline requirement) and no
relay transcription (privacy: relay only routes to a Pro user's chosen cloud
model, never transcribes). 99% of users are offline local-model users.

### What failed (no code fault on our side)
- Mid-implementation, a concurrent agent working Eyevue/build issues on
  `cyanbridge-relay-readiness` stashed the shared tree (`git stash push -u`,
  "parked to unblock eyevue build"), sweeping up the in-progress files. Current
  branch `heycyan-eyevue-tunebuds-protocol-fixes` is back at HEAD for those
  paths. Nothing is lost: the work sits in `stash@{0}` (see `git stash show`
  output below), but it mixes both agents' files across two branches, so it
  was NOT popped. The other agent is now separating my MainActivity hunks from
  their WIP to preserve them.
- Stash@{0} contents relevant to this task: `MainActivity.kt` (all rewires),
  new `ai/image/AudioQuestionRecorder.kt`, new test
  `ai/image/AudioQuestionRecorderTest.kt`.

### What was planned and why (approved before implementation)
- New `AudioQuestionRecorder`: `VOICE_COMMUNICATION` AudioRecord @16 kHz mono
  (follows the glasses SCO mic like today), pure `VoiceEndpoint` state machine
  (onset timeout reuses the 3.3 s constant, ~1.8 s silence hangover so long
  questions are never cut, ~20 s cap, ~400 ms min-speech to reject cue echo),
  pure RMS frame classifier + WAV writer (both JVM-testable), WAV to
  `cacheDir/audio-questions/`. Rationale: endpointing fully on-device, window
  stays open while the user speaks, zero network dependency.
- Image flow: `CapturedImageQuestion` gains `audioPath`; threaded through the
  parallel/sequential capture, `offerFollowUp`, `triggerAssistantImageQuery`,
  `triggerChosenProviderImageQuery`. LOCAL route sends image + audio + short
  instruction text (not the default description) in one LiteRT turn
  (`audioPath` already supported end to end via `GenerationConfig` ->
  `Content.AudioFile`). PRO/TASKER/Live proceed with text only + log line
  (interim: PRO needs the planned combined image+audio relay endpoint; TASKER
  external-UI fill fundamentally needs text = open product decision).
- Voice flow: same recorder replaces its `SpeechRecognizer`; LOCAL answers
  direct from audio; PRO answers via existing `/audio-query` (no website change);
  TASKER voice gets a clear toast (needs text bridge). Text intent routing
  (`AssistantRequestRouter` ANALYZE_IMAGE/EXECUTE_UI_TASK/CLARIFY from the voice
  button) is dropped with the text it depended on — documented regression.
- Verified beforehand (read-only): LiteRT-LM 0.16.0 already builds
  `Content.AudioFile` (wav/mp3/flac); llamacpp-kotlin 0.4.0 binding is
  text-only (upstream libmtmd has audio, binding does not expose it); website
  has no standalone VAD (only Live-API `silenceDurationMs`); relay
  `/audio-query` takes audio+prompt, `/image-query` image+prompt, no combined
  route yet; chat-screen typed questions stay text-only via existing defaults.
- Website plan (no edits): extend `/image-query` with optional `audioBase64`
  appended as an `input_audio` part (mirrors `/audio-query`), backward
  compatible.

### Hunk recovery plan
1. Let the concurrent agent finish isolating my MainActivity hunks from their
   Eyevue WIP (in progress on their side).
2. Re-apply on `heycyan-eyevue-tunebuds-protocol-fixes`: my 3 paths only
   (`MainActivity.kt` hunks, `AudioQuestionRecorder.kt`,
   `AudioQuestionRecorderTest.kt`) — via their separated patch/branch, or
   `git checkout stash@{0} -- <paths>` + manual verification that no Eyevue
   hunks leak in, or rewrite from context if the stash becomes unusable.
3. Do NOT `git stash pop` (would reintroduce their files across branches).

### Next steps (after recovery)
1. Re-apply edits, then `git diff --stat` review of exactly the 3 paths.
2. Run `:app:testDebugUnitTest` (new endpointing/classifier/WAV tests + existing
   vision/transcription/router tests), then `:app:compileDebugKotlin`.
3. On-device test with logcat (`ImageQuestion`, `ImageQuestionAudio`,
   `AudioQuestionRecorder`): confirm onset/hangover behavior and that LOCAL
   answers audio+image directly.
4. Follow-ups (separate): combined image+audio relay endpoint (website),
   PRO image audio enablement (one-line once endpoint lands), TASKER spoken
   questions product decision, voice-button intent redirection (lost with text).

## 2026-10-01 — Audio/image question diagnosis and parked-patch review

- Full review: `android/CyanBridge/docs/AUDIO_IMAGE_QUESTION_REVIEW.md`.
- Corrected the earlier certainty: logs prove early recognizer closure and no
  usable question, not a proven Google server-side onset deadline. Google starts
  online recognition **and** offline SODA; SODA fails to obtain the `en` language
  pack although an `en-US` pack is listed. Bluetooth route-open/packet activity
  alone does not prove intelligible speech reached recognition. An earbuds route
  and an 11-second empty-result attempt are additional confounders.
- The app already has local energy-based speech detection: AmbientSpeechDetector
  (actively used), SpeechActivityDetector, SilenceCompactor, and
  GeminiLiveSpeechActivityDetector. They are not currently the single-shot
  question endpoint. Reuse suitable primitives rather than claiming none exist.
- LiteRT already combines image/audio/text and preserves configured/runtime
  system instructions. Found a separate real defect: failed media generation
  silently retries text-only, and absent files can be silently omitted.
- Original WIP is now `stash@{1}`, stable commit
  `75c25289d6f2ae7755a364f088798ed834888de9`. Exported only the original audio
  MainActivity delta plus recorder/test files and hash manifest into
  `/tmp/opencode/audio-image-review-20261001/` without applying them.
- The WIP is incomplete: orphaned recognizer code, undefined voice cue/timeout,
  missing imports. Its unconditional 20-second cap contradicts the long-question
  requirement. Cancellation ownership, file cleanup, ignored non-local image
  audio, and removed voice intent/Live routing also require revision.
- Concurrent EyeVue protocol, WAV-import, and RTSP changes are broadly sensible
  for transport/live preview. The auto-audio ACK-timeout permit-release change
  still needs vendor late-reply isolation verification; coordinator tests alone
  establish only permit bookkeeping.
- Current selected unit tests passed: **33 tests across five classes**; log at
  `/tmp/opencode/audio-image-review-tests-20261001.log`. This does not validate
  the stashed recorder. No ADB device attached. Located test WAV fixtures, but
  no raw microphone recording associated with the failing question attempts in
  searched paths.
- Next: rebuild the LOCAL capture-to-multimodal handoff from the reviewed hunks,
  preserve system/extra text, stop silent media loss or implicit cloud fallback,
  retain route behavior deliberately, and verify offline on physical hardware.

## 2026-10-01 — Offline-first question flow + upstream llama.cpp (uncommitted)

### Goal (user-approved)
- Keep Google recognition strictly on-device where usable; fix the `en` vs
  installed-pack failure mode instead of relying on network STT.
- If offline transcription fails, send recorded audio + image + required text
  prompts to LiteRT.
- Bring llama.cpp up to an upstream revision with audio-capable libmtmd, since
  `llamacpp-kotlin:0.4.0` exposes no audio path.

### Implemented (working tree, not committed)
- Branch: `heycyan-eyevue-tunebuds-protocol-fixes` @ `05463fb`; changes uncommitted.
- Capture: new `ai/image/AudioQuestionRecorder.kt` (16 kHz mono WAV, shared RMS
  primitive from `SpeechActivityDetector`) + new `ai/image/QuestionEndpoint.kt`
  (`NO_SPEECH` on onset timeout, `COMPLETE` on sustained silence, 2-min resource
  limit reported as `LIMIT_REACHED`, not silent truncation).
- STT: new `ai/transcription/OfflineQuestionTranscriber.kt` (API 33+ only,
  `createOnDeviceSpeechRecognizer`, `checkRecognitionSupport` installed-locale
  selection, recorded WAV injected via `EXTRA_AUDIO_SOURCE` + segmented session;
  any failure returns null so the original audio is retained for the model).
- Wiring: `MainActivity.kt` image/voice capture now records once, tries on-device
  transcript, keeps existing text routing on success, otherwise forwards the WAV
  to local multimodal inference. Added `audioPath` plumbing and WAV cleanup.
- LiteRT: removed silent text-only retry/fallback in
  `LiteRtLocalInferenceEngine.kt`; missing/empty attachments now fail loudly.
- llama.cpp: new `:llama-runtime` module pinned to upstream
  `0c1e57098bba43ac29e6e3b677cdceebdd22334f`
  (`SHA256=6a85ec94...0407f`) with `libmtmd` image+audio bridge (`bridge.cpp`,
  `UpstreamLlamaBridge.kt`); replaced `LlamaCppLocalInferenceEngine` and
   embedding engine with the upstream JNI path; `EngineLoadConfig.projectorPath`
  added; new `LocalModelProjectorStore.kt` for per-model mmproj GGUF imports.
- Routing: `MediaInferenceRoutingPolicy` no longer falls back LOCAL media to
  Pro/Tasker relay; `LocalModelsProvider` can pick an installed LiteRT or
  projector-backed GGUF media model for the request. Tests: new
  `QuestionEndpointTest`, `OfflineQuestionTranscriberTest`, `Utf8TokenDecoderTest`;
  updated `MediaInferenceRoutingPolicyTest`.

### Verification
- `:app:compileDebugKotlin`: PASS (`/tmp/opencode/offline-question-compile.log`).
- `:llama-runtime:assembleDebug`: PASS
  (`/tmp/opencode/llama-upstream-android-build.log`).
- `:app:testDebugUnitTest`: 576 tests, 1 failure —
  `EyevueReleaseSafetyTest.eyevueMediaSyncUsesVerifiedWifiActivationCommand`
  (`/tmp/opencode/offline-question-tests.log`). Unrelated EyeVue Wi-Fi
  expectation; needs triage before commit.
- Host JNI smoke (`test_upstream_llama_jni.sh`): blocked at CMake
  `Could NOT find JNI` under both JBR and Java 11
  (`/tmp/opencode/upstream-llama-host-smoke.log`); Android AAR build itself
  succeeded, host smoke still open.
- No device attached; no on-phone offline STT or LiteRT audio+image run yet.

### Next
1. Triage the EyeVue safety-test failure; rerun full unit tests green.
2. Fix host JNI smoke environment or document it as device-only.
3. On-device test with network disabled: on-device transcript path, audio+image
  LiteRT path, long-question endpointing, and projector-backed GGUF media caps.
4. Commit only after 1–3; review `android/CyanBridge/tools/` untracked dir before
   committing.

## 2026-10-01 — Meta DAT log review, guided setup, and actual SDK mock CI

- Reviewed report `log_mukhte4r_6fb6f47a` (Samsung SM-F976U, Android SDK 37,
  app 2.3.0). Its September 28 00:00–00:12 UTC events predate `a13fffc`
  (14:39 UTC), whose initial-STOPPED stream/display fix is already in this branch.
  Registration succeeded; discovery raced camera permission checks, and recovered
  `Granted` results left the old error latched. Review:
  `android/CyanBridge/docs/META_DAT_LOG_REVIEW_2026-10-01.md`.
- DAT readiness now follows the selected device's `LinkState.CONNECTED`, with
  link/auth diagnostics, bounded retries for typed connection errors, and recovery
  of old camera failures. Setup checks are deduplicated per ready-device state and
  resume. Permission-request errors preserve their SDK description. The manager
  also observes an already initialized process-wide SDK (`ALREADY_INITIALIZED`).
- Added next-step popups for Android permissions, app settings after permanent
  denial, Bluetooth, registration, reconnecting, and separate Meta camera consent.
  Capture failures open Meta setup. Shared `MetaDatPermissions` covers Location on
  Android 10–11 and Nearby Devices on Android 12+. Connection errors route to Meta
  AI rather than misleading permission/re-registration instructions.
- Debug mock is guarded to debug builds, handles camera checks consistently,
  preserves a running preview during one-shot capture, and releases its lease on
  disable. Removed unreliable reflective activation of a second mock backend.
- Added `MetaDatSdkMockTest` to self-hosted CI: actual Meta `MockDeviceKit` through
  the production manager, authorization denial/grant, stream, decodable photo,
  cleanup, and power-off readiness. A 3.6 KB synthetic camera video is included.
- Actual SDK mock exposed a native packaging crash: LibVLC's old bundled STL won
  `pickFirst`, leaving `libfbjni.so` unable to load a required stringstream symbol.
  Added a cached AAR transform removing only LibVLC's STL so the modern shared
  runtime is packaged. The SDK test loads LibVLC and DAT in the same process.
- Verification: all **21 Meta instrumentation tests pass** on a fresh API 36
  `CyanBridge_Meta_DAT_CI` emulator (4 lifecycle, 1 actual SDK mock, 7 UI mock,
  9 setup popup tests), via the same isolated-class HIL runner used by CI.
  Targeted unit tests: 26/26; shared portability tests: 80/80.
- Full unit run initially found 583 tests / one pre-existing EyeVue source-check
  failure. Removed its obsolete `stopLiveBlocking()` assertion: committed sync
  cleanup now uses `finishMediaSync(completed, total)`. The Wi-Fi activation checks
  remain. Final verification is green: **583/583 app unit tests**, **80/80 shared
  portability tests**, debug and instrumentation APK builds, plus the 21/21 Meta
  emulator tests. The normal debug APK's ARM64 and x86_64 shared runtimes both
  contain the required fbjni symbol and match the modern fbjni dependency.
  Build/unit logs and SDK logcat: `/tmp/opencode/meta-dat-review/`; isolated
  instrumentation results: `build/hil/results/instrumentation-emulator-*-Meta*.txt`.
- No physical Meta glasses attached. Hardware validation on the reported Samsung
  / Android 17 build remains the next device check; emulator capture verifies the
  app/SDK sequence and does not establish that hardware result. Changes uncommitted.

## 2026-10-01 — Walking Aid continuous EyeVue and Meta video

- Added a persisted Walking Aid camera source with periodic photos as the default,
  and device-gated EyeVue/Meta continuous-video controls in settings. The photo
  interval and photo benchmarks apply only to the photo source. Source changes
  are disabled while active. Permission popups guide Bluetooth, EyeVue Wi-Fi,
  permanent-denial settings, and Meta registration/camera authorization.
- Live sources feed the existing local YOLO/tracking/warning worker through the
  shared latest-frame queue. Queue overflow and shutdown recycle unused bitmaps;
  local depth owns an independent copy and runs as a cancellable child so it does
  not block the next YOLO frame. Video history saves only analyzed frames and
  bounds retained JPEGs. Stream stalls/errors stop the plugin with a visible error.
- EyeVue preview's BLE/Wi-Fi/HTTP/RTSP connection was extracted into
  `EyevueLiveConnection`, shared by the visible preview and headless LibVLC output.
  An ImageReader surface receives RV32/RGBX and applies the same -90° rotation as
  preview. The real video test caught/fixed an initial RGBA/RGBX format mismatch.
- Meta video uses the production manager and actual DAT session/stream callbacks.
  Both sources respect exclusive transport leases and release on cancellation.
  Continuous video uses `connectedDevice` foreground-service type.
- Added 12-second two-scene H.264/HEVC fixtures derived from the existing pinned bus
  and Zidane JPEGs. CI runs actual YOLO11 over EyeVue's production decoder (only
  the connection URL substituted) and actual Meta `MockDeviceKit` video delivery.
- Stronger cross-scene assertions found an existing detector bug: the YOLO11
  export's unquantized UINT8 `class_idx` has scale=0. Multiplying IDs by zero made
  every object a person. Preserve raw IDs for zero-scale integer tensors, and
  require person/bus and person/tie in real-image tests as well as stream tests.
- Actual Meta video testing found that the mock negotiates an HEVC decoder: an
  H.264 feed entered STREAMING but produced no decoded frames. The Meta fixture
  now uses HEVC, and the manager logs first-frame format metadata for diagnosis.
  The passing mock delivered 640×480 I420 (460,800 bytes), detected bus/person and
  then person/tie, and completed `STOPPING -> STOPPED` with no stream/lease leak.
- Final verification is green on `CyanBridge_Walking_Aid_CI` / emulator-5580:
  **586/586 app unit tests**, **80/80 portability tests**, **5/5 real-model/video
  phases** (YOLO11, YOLO-World, depth, EyeVue video, actual Meta DAT video), and
  **28/28 control/lifecycle/DAT regression instrumentation tests**. Both debug
  APK builds pass, including the normal ARM64+x86_64 APK for hardware retesting.
  Evidence: `/tmp/opencode/walking-video-*.log`,
  `build/hil/results/walking-aid-model-ci/`, and
  `build/hil/results/instrumentation-emulator-emulator-5580-*.txt`.
- CI runner also fixes the optional `stop_other_emulators` function returning
  failure under `set -e` when the option is disabled. Documentation:
  `android/CyanBridge/docs/WalkingAid_mvp.md`, `tools/hil/README.md`, and video asset
  provenance under `app/src/androidTest/assets/walkingaid/README.md`.
- No physical EyeVue or Meta glasses are attached. Changes remain uncommitted.
