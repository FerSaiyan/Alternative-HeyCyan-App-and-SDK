# Mentra miniapps in CyanBridge

Status: experimental, Android only, behind a switch on the Plugins page ("Mentra miniapps (experimental)", off by default).

Sources:
- The MentraOS repository (Apache-2.0) at commit `f500314` (2026-10-25), cloned read-only at `/mnt/seagate/ML/mentra-ref/MentraOS`. Paths below are relative to it.
- The live Mentra Miniapp Store (apps.mentraglass.com) and the public first-party miniapp host (miniapps.mentraglass.com), checked on 2026-10-27 with plain anonymous HTTPS requests.

## 1. What a miniapp needs from its host

### Runtime: two layers

A miniapp is a ZIP built by `mentra-miniapp pack` (`sdk/miniapp-cli/src/pack.ts`). It holds `miniapp.json`, `icon.png`, `background/index.js` and `ui/…` (`index.html` plus hashed ESM chunks). An optional `META-INF/MENTRA.SIG` carries the signature. The doc is `mintlify-docs/app-devs/core-concepts/two-layer-architecture.mdx`.

- **Background layer.** `background/index.js` is one classic IIFE script with the `@mentra/miniapp` SDK compiled in (`format:"iife"` in every example's `build.ts`). It owns the `session` and runs for as long as the miniapp runs.
  - Mentra runs it in bare QuickJS on Android, using `io.github.dokar3:quickjs-kt` (`mobile/modules/crust/android/build.gradle:131`, `…/jsc/JSCRuntime.kt`). On iOS it uses JavaScriptCore (`…/crust/ios/Source/JSCRuntime.swift`). It never runs in a WebView.
  - The host installs six native globals: `__dispatch(iface, method, argsJson)` (synchronous, returns JSON or null), `__hostLog`, `__hostError`, `__hostUnhandledRejection`, `__nativeSetTimeout(token, ms)` and `__nativeClearTimer(token)`.
  - It then evaluates the polyfill `mobile/modules/jspolyfill/assets/startup.js` (Apache-2.0, about 32 KB). That polyfill supplies console, timers, `queueMicrotask`, `AbortController`, `localStorage`, `crypto.getRandomValues`/`randomUUID`, `TextEncoder`/`TextDecoder`, `atob`/`btoa`, `fetch` and `WebSocket` on top of `__dispatch`, plus `__deliver(envelopeJson)` for host-to-JS delivery.
  - Next the host evaluates the bundle. Finally it delivers `{kind:"init", sessionId}`, which runs the miniapp's `registerMiniapp` handler.
- **UI layer.** `ui/index.html` runs in a WebView only while the user has it open. It never gets native access. The host injects `window.mentra` (`send`, `on`, `request`, `onOpen`, `onClose`, `ready`) and `window.MentraOS` (color scheme, safe-area insets) before the page's scripts run (`mobile/modules/engine/src/services/mentraUiShim.ts`, `…/utils/miniappGlobals.ts`). The page must call `mentra.ready()`.
- **Wire protocol.** Every SDK call is one envelope `{"payload":{"type":…},"requestId"?}` sent with `__dispatch("__bridge","send",JSON.stringify([raw]))`. The host answers through `__deliver({kind:"bridge", raw})`. Requests get `{type:"miniapp_request_result", ok, data | error:{code,message}}` with the same `requestId`.
  - Handshake (`mobile/modules/miniapp/src/session.ts:600-664, 822-1048`): `miniapp_connect` gets `miniapp_connect_ack {userId, packageName, capabilities, permissions, hostFeatures:{initReady}}`. After the handler settles, the SDK sends `miniapp_ready`.
  - Subscriptions are a one-shot `miniapp_subscribe` carrying the full current set (`modules/events.ts:320-345`). Events arrive as `miniapp_event {streamType, data}`.
  - The UI bus rides the same pipe. UI to background travels as `miniapp_event` with `streamType:"_ui"`, whose `data.type` is `UI_OPEN`, `UI_CLOSE`, `UI_MESSAGE` or `UI_CANCEL`. Background to UI travels as a one-shot `UI_SEND {channel, payload, seq, requestId?}`, which the host passes to `window.__mentra.recv({type:"msg",…})` (`…/engine/src/services/MentraUIRouter.ts:300-464`).

### Session API surface

The full list is in `mobile/modules/miniapp/src/protocol.ts:15-272`, about 120 request types. The docs group it as follows:

| Namespace | Main calls | Needs Mentra cloud? |
|---|---|---|
| display | `render(elements, {view, durationMs, includeTextLayout})`; legacy `miniapp_display` layouts | no; the host wraps text and returns `textLayout` |
| transcription | `on`, `forLanguage`, `configure` (streams `transcription:<lang>`) | yes, unless `forceLocal` |
| translation | `to`, `fromTo` (streams `translation:<src>:<tgt>`) | yes, always |
| mic | `onAudioChunk`, `onVoiceActivity`, acquire/release | no |
| speaker | `play`, `speak` (TTS), `createStream`, `stop` | TTS is cloud with an offline fallback |
| input | `onButtonPress {buttonId, pressType}`, `onTouch {kind}` | no |
| storage | `get/set/delete/has/keys/clear/getAll/setMultiple` (per app, phone-local) | no |
| blob | file store, 32 MB reads | no |
| camera / stream | `takePhoto`, video, RTMP/SRT/WHIP, managed streams | photos and managed streams yes |
| location / heading / navigation | `getOnce`, `onUpdate`, turn-by-turn, `computeRoute`, places | routing and places yes |
| phone | notifications, calendar, battery, Wi-Fi | no |
| glasses / imu / led | battery, connection, head position, accelerometer, LED | no |
| system | share, open URL, clipboard, download | no |
| auth / cloud | miniapp JWT, cloud status | yes (Core) |
| actions / miniapps / meeting | interop, install, ACS meetings | system-only, or cloud |

**What store apps use.** Every first-party store app goes through `display.render`; none calls the legacy `show*` layouts. The common subset is:
- `render` with text elements;
- `transcription.on`;
- `storage.get` and `set`;
- the `ui.*` bus;
- `capabilities` and the lifecycle hooks.

Per app (source: the `miniapps/*` controllers):

| App | Extra APIs beyond the common subset |
|---|---|
| Captions | `transcription.forLanguage`/`configure`, `cloud.onStatusChanged` |
| Teleprompter | needs `textLayout.script` with line offsets and `capacity`; otherwise it never scrolls |
| Translation | `translation.to`, which needs cloud translation |
| Recorder | `mic.onAudioChunk`, blob store, speaker playback, `system.share` |
| Navigation | location, heading, `navigation.*` (cloud routing, Mapbox token), TTS, BMP images |

### Permissions

Manifest `permissions[].type` is one of MICROPHONE, CAMERA, PHONE_CAMERA, CALENDAR, LOCATION, BACKGROUND_LOCATION, READ_NOTIFICATIONS or POST_NOTIFICATIONS. `required` defaults to true (`sdk/miniapp-cli/schema/miniapp.schema.json`).

**Prompting.** Mentra asks once, before a miniapp opens (`mobile/src/utils/PermissionsUtils.tsx:738-790`). It then enforces the manifest at runtime: a subscription or request whose permission the manifest does not declare fails with `PERMISSION_NOT_DECLARED` (`…/engine/src/services/LocalMiniappRuntime.ts:2069-2091`).

**Gates:**

| Feature | Gate |
|---|---|
| mic, transcription, translation | MICROPHONE |
| location, heading, navigation | LOCATION |
| photo, video, preview | CAMERA |
| notifications | READ_NOTIFICATIONS |
| calendar | CALENDAR |
| display, storage, input, LED, IMU | none (hardware gating only) |

### Accounts and cloud

- **Mentra account.** A Mentra Core account backs these:
  - `session.auth` tokens (`POST /api/client/auth/miniapp-token`, a JWT with `aud` set to the package name and `workspaceId` only for Fleet-assigned apps);
  - private miniapps (a `401` challenge with `WWW-Authenticate: Bearer realm="mentra"`);
  - registry registration and archive status.
  - Its absence only disables those features. A public miniapp installs and runs without it: `userId` may be empty, and `auth` is optional in the CONNECT_ACK.
- **Cloud features.** Cloud transcription, translation, TTS, photos, managed streams, maps and meetings run on Mentra's cloud. A third-party host must supply its own transcription (or the miniapp's `forceLocal` path). Without one, these features are unavailable.

### Updates and integrity

- **Install source.** The miniapp URL is an HTTPS folder. `<url>miniapp.json` is the author's manifest plus `bundle:{url, sha256}`; it is capped at 64 KB, and the ZIP at 50 MB.
- **Integrity checks.** The ZIP's SHA-256 must equal `bundle.sha256`. The ZIP's own `miniapp.json` must name the same package and version (`…/engine/src/services/installMiniapp.ts`, `validateInstallBundle.ts`).
- **Update checks** use `If-None-Match` with the stored ETag plus `Cache-Control: no-cache` (`miniappFetch.ts:161-166`).
  - A newer SemVer installs, the same version is a no-op, and an older one is refused.
  - A 301/308 to the same registrable domain moves the miniapp.
- **Signing** is optional. `META-INF/MENTRA.SIG` is an Ed25519 signature over the manifest and content hashes, trusted on first use: the first signed install pins the key (`sdk/miniapp-cli/src/bundle-signing.ts`, `publisherIdentityPolicy.ts`).
- **Domain rule.** The package name must start with the reversed registrable domain of the URL, plus at least one more part; hyphens become `_`. `com.mentra.*` may only be served from mentraglass.com (`cloud-v2/packages/protocol/src/miniapp-url.ts:84-104`).

## 2. May a third-party host install and run store miniapps?

- **License.** The SDK, the runtime pieces (polyfill, UI shim) and the example miniapps are Apache-2.0. CyanBridge vendors only `startup.js`, with attribution, and ports the shim's behaviour.
- **Terms.** No host allowlist, attestation or terms of service for third-party hosts were found in the repository or the docs. The docs plan for one: Mentra's embeddable runtime for OEM hosts, `@mentra/engine`, exists in preview and is "not yet packaged for external install" (`mintlify-docs/glasses-oems/engine.mdx:6-11`). Its `requireRegistry=false` setting comes with the note "A host app that turns it off answers to the app stores' rules itself" (`engine.mdx:381-385`).
- **Registry.** Registering with Core is "an index, not a gate".
- **Store bundles are gated.** `GET https://api.apps.mentraglass.com/api/store/bundles/<id>/download` answers `401 {"error":"unauthorized","error_description":"Store credential rejected"}` without a Mentra store credential. **CyanBridge does not download store bundles.**
- **What CyanBridge installs.** It uses the same public paths the Mentra App uses:
  1. A developer's miniapp URL. The developer chooses to publish it, and the domain rule ties the package name to the site.
  2. For store listings, the public directory lookup `GET https://api.apps.mentraglass.com/api/directory/miniapps/<pkg>` → `{packageName, url}`. It is anonymous (`mobile/src/services/miniapps/miniappInstallFlow.ts:268-284`).
  3. For first-party `com.mentra.*` listings, Mentra's public first-party host `https://miniapps.mentraglass.com/<pkg>/`. The Mentra App updates its own first-party apps from that host (`.github/workflows/miniapp-publish.yml`). On 2026-10-27 it served `miniapp.json` with an ETag, answered `304` to `If-None-Match`, and served the ZIP anonymously; for teleprompter the SHA-256 matched.
- **Open question for the owner.** Using route 3 relies on Mentra's first-party bundles being public, without an explicit grant beyond Apache-2.0. Ask Mentra (help@mentra.glass) whether third-party hosts are welcome, before shipping this outside experimental builds.

## 3. Is there a browse feed?

**Yes, a public one.** It is not in the repository: the store is a separate service (`cloud-v2/docs/store-integration.md`).
- **Feed.** `GET https://api.apps.mentraglass.com/api/store/apps?limit=24&page=1[&q=…]` is anonymous and returns `{apps:[…], page, limit, total, hasMore}`. The store's own web client calls it (`https://apps.mentraglass.com/client.js`).
- **Listing fields:**
  - `packageName`, `name`, `subtitle`, `description`;
  - `categories` (for example `accessibility`, `communication`, `productivity`);
  - `iconUrl` and `coverUrl` (anonymous PNGs under `/api/store/assets/<id>`);
  - `reviewTier` (`community`/`verified`), `featured`;
  - `release {version, installable, bundleUrl, bundleSha256, manifestSha256, permissions, hardwareRequirements, minHostVersion, sdkVersion}`.
- **Single listing:** `GET …/api/store/apps/<pkg>`.
- **Size.** On 2026-10-27 the store listed 10 apps, all `com.mentra.*`.

## 4. JS engine

| Option | License | Size | Fit |
|---|---|---|---|
| **QuickJS via `io.github.dokar3:quickjs-kt` 1.0.3** (chosen) | Apache-2.0 (QuickJS itself MIT) | AAR 1.7 MB for 4 ABIs, about 0.4 MB per ABI in an APK split | What Mentra ships on Android (they use 1.0.15). 1.0.3 is the newest release built with Kotlin 2.3.10 / coroutines 1.10.2, which match CyanBridge; 1.0.8+ pull kotlin-stdlib 2.4. It is KMP and has iOS targets, though iOS should use JavaScriptCore. It offers synchronous host functions, the job loop and memory and stack limits. |
| `app.cash.quickjs` 0.9.2 | Apache-2.0 | ~1 MB | Archived since 2021, no Kotlin API for the job loop |
| J2V8 | EPL-1.0 | several MB per ABI | V8 is much larger than needed; releases lag V8 |
| Javet-android 3.0.4 | Apache-2.0 | 10+ MB per ABI | V8/Node, far too large for one background script |
| Hidden WebView | system | 0 | A Chromium renderer per app (tens of MB). Timers are throttled while the view is detached or the app is in the background. It is tied to a `Context` and the main thread, and has no synchronous bridge; Mentra moved away from it. |
| iOS | JavaScriptCore | 0 (system) | `JSContext` per miniapp on a serial queue. `__dispatch` and friends are `@convention(block)` closures; `evaluateScript` for delivery. |

A miniapp therefore costs one QuickJS context: a 32 MB heap cap and 512 KB stack, as in Mentra's host. The bundles that run are 180-200 KB of JS.

## 5. Mapping miniapp calls onto CyanBridge devices

A miniapp talks to "the glasses" and never to a vendor. CyanBridge serves it from the selected family's `GlassesFamilyProfile` capabilities and the Android display bridge (`GlassesBridge` → `GlassesDeviceAdapter`), so it runs on any connected glasses that have what it needs.

| Miniapp call | CyanBridge contract | MYVU | MemoMind | Mentra Live | Mentra display models (G1/G2, Mach1, Z100…) |
|---|---|---|---|---|---|
| `display.render` (text) | Host wraps text (`MiniappTextLayout`) → `DisplayCommand.Text` via `GlassesBridge.showText` (`canPosition:false`, as on G1) | yes | yes | no display: `status:"blocked"` | yes, through the `mentra_display` adapter (`MentraDisplayAdapter`); the canvas follows the model's `MentraDisplayLimits` (G1 40×5, G2 40×8, NIMO 60×11) |
| `render([])` | `GlassesBridge.clearDisplay` | yes | yes | — | same |
| `render` image/rect | dropped and reported in `dropped` (no `IMAGE_DISPLAY` contract yet) | — | — | — | gap |
| `transcription.on` / `forLanguage` | `PluginVoiceRecognizer` (Android speech recognition through the plugin audio session; the glasses mic over SCO when routed) → `transcription:<lang>` events | phone/SCO mic | phone/SCO mic | phone/SCO mic | phone mic (their vendor PCM stream is not routed to Android speech recognition yet) |
| `input.onButtonPress` / `onTouch` | `GlassesBridge.bridgeEvents` `InputEvent.Button` → `button_press {buttonId, pressType}` / `touch_event {kind}` | — | button and head gestures | button | via their adapter |
| `storage.*` | per-app JSON file in app storage | all | all | all | all |
| `speaker.speak` | phone TTS (Android `TextToSpeech`) | yes | yes | yes | yes |
| `capabilities` | built from `GlassesFamilyProfile` (`hasDisplay`, `hasMicrophone`, `hasSpeaker`, `hasCamera`, `hasButton`, `display {width:500, height:220, canPosition:false, maxTextLines}`) | | | | |

**Hardware requirements.** A REQUIRED hardware requirement the selected family lacks blocks the start with an explanation. MICROPHONE is always met, because the phone microphone is the fallback, as in Mentra.

**Gaps, all answered `NOT_IMPLEMENTED`:**
- translation (needs a translation provider);
- camera and photos (the `CameraCapture` contract has no "photo to miniapp" path);
- streams;
- mic PCM (`audio_chunk`);
- blob store;
- location, heading and navigation;
- notifications and calendar;
- LED and IMU;
- `session.auth`;
- interop and actions.

**Two display gaps:**
- There is no positioned or bitmap display contract, so scenes degrade to a text wall.
- Line metrics are approximations. They use a 500×220 canvas, 44 px lines (5 rows) and 12 px glyphs. The real per-device line width needs calibrating on hardware.

## 6. Design

### Shared (`shared/commonMain/.../shared/plugins/mentra/`)

All the protocol logic is pure and tested:

| File | What it does |
|---|---|
| `MiniappManifest` | parses and validates `miniapp.json` |
| `MiniappSource` | URL normalization, the domain rule (a short Public Suffix subset), the served manifest, `bundle {url, sha256}`, SemVer update decisions |
| `MiniappBundle` | ZIP reading through the `.mmpkg` reader with Mentra's limits, the SHA-256 check, inner-manifest match |
| `MiniappStoreCatalog` | the store feed parser |
| `MiniappPermissions` | approvals, the start condition, stream/request → permission gates (`PERMISSION_NOT_DECLARED` / `PERMISSION_DENIED`), the CONNECT_ACK permission record, hardware compatibility against `GlassesFamilyProfile` |
| `MiniappTextLayout` | the host-side line wrapping that `render` and `textLayout` need |
| `MiniappHostSession` | the background protocol: handshake, subscriptions, render, storage, speak, UI bus, events in, `NOT_IMPLEMENTED` for the rest |
| `MiniappRuntimeBridge` | the `__dispatch` table (`__bridge`, `localStorage`, `crypto`, `fetch`, `__runtime`) and `__deliver` envelopes |
| `PluginEcosystems` | which Plugins-page sections the selected family gets |

### Android (`app/.../plugins/mentra/`)

| Class | What it does |
|---|---|
| `MiniappStore` | per-package files `files/mentra/miniapps/<key>/{package,data}` with atomic replace, `source.json` (URL, ETag, version, bundle hash) and approvals |
| `MiniappInstaller` | HTTPS fetches with caps, a conditional GET for updates, the directory and first-party lookups |
| `QuickJsMiniappEngine` | one QuickJS context per running miniapp on its own thread: the six globals, the vendored `startup.js`, bundle evaluation, init, timers |
| `MentraMiniappHost` | process-wide: the running miniapps, the device glue (`GlassesBridge`, `PluginVoiceRecognizer`, TTS), and the microphone foreground service while transcription is subscribed |
| `MiniappUiWebView` | serves the package from `https://<key>.mentra-miniapp.androidplatform.net` as the MemoMind host does (only listed files, no file/content access, navigation pinned to the origin, origin-scoped `WebMessageListener` instead of `addJavascriptInterface`); document-start scripts inject `window.MentraOS`, a `ReactNativeWebView` shim and the `mentra` shim |
| `MentraMiniappsDestination` | a `MainActivity` route (no new Activity): installed list, "Install from URL", store browse, the consent dialog, the UI screen |

### iOS port (designed, not built)

| Piece | Plan |
|---|---|
| `MiniappHostSession`, `MiniappRuntimeBridge`, policy, layout, store and catalog | commonMain, used as is |
| Engine | `JSContext` per miniapp on a serial `DispatchQueue`; the six globals as Swift blocks; `startup.js` as a bundle resource |
| UI | `WKWebView` with a `WKURLSchemeHandler` for `mentra-miniapp://<key>/`, `WKUserScript`s at document start for the shims, and a `WKScriptMessageHandler` named `ReactNativeWebView` |
| Device glue | iOS already has `IosGlassesDevices.answerDisplay` (MemoMind) for display; transcription through `SFSpeechRecognizer` |

## 7. Plugins page

The page shows sections by the selected family (`PluginEcosystems.sectionsFor`):

| Section | When it shows |
|---|---|
| CyanBridge native plugins | always |
| MemoMind Web plugins | MemoMind is selected; the card opens the existing `.mmpkg` host |
| Mentra miniapps | a Mentra SDK family is selected (`MentraSdkModel.usesMentraSdk`: Mentra Live, Even G1/G2, Mentra Display, NIMO, AR99), or the family has `TEXT_DISPLAY` (MYVU, MemoMind) |

Even Hub is removed. The `EVENHUB_RUNTIME` route had no caller, so the screen, its JavaScript bridge, ViewModel, strings and `evenhub-compat-shim.js` were dead code.

## 8. Risks

- **Terms.** There is no explicit permission for third-party hosts. Store bundle downloads are credential-gated, and CyanBridge respects that. The first-party host route relies on public URLs; confirm with Mentra.
- **Cloud dependence.** Captions and Teleprompter's voice-follow work with on-phone speech recognition. Translation, photos, TTS voices, maps and `session.auth` would need Mentra's cloud or a CyanBridge replacement.
- **API churn.** The SDK is beta: 3.4.0 here, while manifests say `sdkVersion 0.3.0` and `minHostVersion 3.2.1`, and the wire protocol changed several times in `agents/*.md`. CyanBridge answers unknown requests with `NOT_IMPLEMENTED` and pins the polyfill to the upstream commit above. Re-check `protocol.ts` and `startup.ts` when updating.
- **Security.** Miniapp background code can `fetch` any http(s) URL, as in Mentra. It is sandboxed by QuickJS limits and has no file or Android API access. The UI WebView is origin-locked and gets no host object besides the message port. A malicious miniapp can still exfiltrate what the user types into its UI and the transcripts it subscribes to; the consent dialog says so.
- **Signatures.** `META-INF/MENTRA.SIG` is detected but not verified yet, and no key is pinned. The SHA-256 from the HTTPS-served manifest is the integrity check.

## 9. First version (2026-10-27)

Built (Android, experimental switch on the Plugins page, off by default):
- Install from a miniapp URL (or an apps.mentraglass.com `open?url=` link), from the store browser (public feed → directory lookup → first-party host), update checks with `If-None-Match`, remove. SHA-256, ZIP limits, inner-manifest match and the domain rule are enforced; downgrades are refused.
- QuickJS background runtime with Mentra's polyfill; the host protocol in shared code (handshake, subscriptions, `render` with host text layout, legacy layouts, storage, speak, open URL, UI bus, visibility); everything else answers `NOT_IMPLEMENTED`.
- Sandboxed UI WebView with Mentra's `window.mentra` shim over an origin-scoped message listener.
- Consent dialog from `miniapp.json` permissions; manifest + consent gates on every gated subscription and request; Android microphone permission requested when MICROPHONE is granted.
- Speech recognition through the plugins' recognizer in a microphone foreground service while a miniapp subscribes; button and gesture events from the display bridge.
- Device-aware Plugins page; Even Hub removed.

Verified without a device: the four public first-party bundles (Captions 1.0.24, Teleprompter 1.0.12, Translation 1.0.25, Recorder 1.0.19, downloaded from miniapps.mentraglass.com) were checked by `MiniappBundle.read` (SHA-256 against the served manifest, DEFLATE ZIPs with directory entries) and run in a JVM harness. The harness used the same QuickJS library (JVM build of quickjs-kt 1.0.3), the vendored `startup.js`, the shared `MiniappHostSession`/`MiniappRuntimeBridge` and a copy of `QuickJsMiniappEngine`; it is not committed.
- **Captions:** connects with `initReady`, sends READY, subscribes `transcription:auto` (the host starts recognition), turns host transcripts into `render` calls (the glasses get "hello there", then the final sentence), and drives its UI (snapshot, live transcript, display preview). Closing stops recognition and clears the glasses.
- **Teleprompter:** an older SDK build without `initReady` (the host now treats such a CONNECT as ready). It loads settings from storage, renders the script with `includeTextLayout`, takes `tp:set-script`/`tp:set-wpm`/`tp:play`/`tp:pause` from the UI, and scrolls line by line on the host's `textLayout` offsets.
- **Translation:** starts and subscribes `translation:*:en-US`, its UI hydrates; no translations arrive (no provider). Gap.
- **Recorder:** starts, `blob.*` answers `NOT_IMPLEMENTED`, the miniapp logs it and shows "Recorder ready"; recording needs `audio_chunk` and blobs. Gap.

Deferred: translation, audio chunks, blobs, camera/photos, location/navigation, phone notifications/calendar, LED/IMU, `session.auth`, actions/interop, signature verification (TOFU pinning), autostart after process death, background crash respawn, a watchdog for runaway scripts (QuickJS interrupt), the iOS port, and per-device display calibration.
