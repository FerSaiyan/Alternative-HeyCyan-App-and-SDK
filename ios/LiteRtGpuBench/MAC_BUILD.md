# LiteRT GPU Bench: build the iPhone test archive on a Mac

These are instructions for the person, or the coding agent, building on the Mac. Read all of them
before starting.

## Goal

Build one file, `LiteRtGpuBench-xctest.zip`, and send it to Akio. It is a small iOS app plus an
XCTest bundle. Akio runs it on Google's real iPhones (iPhone 17 Pro and iPhone 16 Pro, through Google
Device Run). It measures CyanBridge's vision and audio models (YOLO11n, PP-OCRv6, WeSpeaker) on the
iPhone CPU and the Metal GPU with LiteRT 2.4.0.

The app has no models inside. Akio pushes the models, inputs and the test plan to the phone when he
submits the run, so this is a one-time build. You don't need a Google account, Google Cloud access,
the models, or an iPhone.

## What you need

- **A Mac with Xcode.** Device Run runs the tests with **Xcode 26.2**, its default, or 16.4.
  - Build with Xcode 26.2 if you can (`xcodes install 26.2`, or the Apple developer downloads page).
  - A newer Xcode may produce an archive the lab's 26.2 can't run. If you only have a newer one, build
    anyway and tell Akio which version you used.
  - With several Xcodes installed, choose one with `export DEVELOPER_DIR=/Applications/Xcode-26.2.app`.
- **An Apple ID signed in to Xcode** (Xcode > Settings > Accounts), and its **team ID**: the
  10-character code shown there, or under Membership at developer.apple.com.
  - The build must be signed once with a valid development identity. Google re-signs it with its own
    lab profile before installing, so you don't register Google's devices anywhere.
  - A paid Apple Developer Program team works directly.
  - A free "Personal Team" can only create a signing profile after an iPhone has been registered to
    it. Connect your own iPhone once, with Developer Mode on, and run any app on it from Xcode.
    Without that, signing fails with "no devices".
- **Homebrew, and `xcodegen`:** `brew install xcodegen`.
- **Network access** to github.com (the LiteRT Swift package, about 150 MB) and huggingface.co (the
  public test models, about 75 MB). Plan for about 3 GB of free disk.

## Steps

```bash
# 1. Get the branch
git fetch origin ios-litert-gpu-bench
git checkout ios-litert-gpu-bench
cd ios/LiteRtGpuBench

# 2. Tool
brew install xcodegen        # skip if `xcodegen --version` works

# 3. Pre-flight on the iOS Simulator: no signing needed, about 5-10 min the first time
./scripts/sim_smoke.sh

# 4. The signed device archive
DEVELOPMENT_TEAM=<YOUR_TEAM_ID> ./scripts/build_xctest_zip.sh
```

**Step 3** compiles everything and runs the bench on the simulator with 4 public models and synthetic
inputs.
- It must end with `simulator smoke test passed` and `cpu rows ok: N` with N ≥ 1.
- GPU rows that show `ERROR` on the simulator are fine. Only the real phones count for the GPU.
- If it fails, see Troubleshooting below. Do not go on to step 4 until it passes.

**Step 4** writes:
- `out/LiteRtGpuBench-xctest.zip`, the file to send (a few tens of MB)
- `out/LiteRtGpuBench-xctest.zip.sha256`
- a `BUILD_INFO.json` block printed at the end. It is also inside the zip.

The app's bundle ID defaults to `dev.litertgpubench.<team id in lowercase>`, so it can't collide with
anyone else's. Leave it at the default unless signing complains that the ID is taken. In that case set
`BUNDLE_ID=dev.litertgpubench.<something unique>`.

## What to send Akio

1. `out/LiteRtGpuBench-xctest.zip` and `out/LiteRtGpuBench-xctest.zip.sha256`, through a private
   channel (Google Drive link, WeTransfer, ...). Don't post it publicly: it is signed with your
   development certificate.
2. The text printed at the end of step 4 (the archive, sha256, bundle and BUILD_INFO lines).
3. The last ~20 lines of the step 3 output (the table of model/config rows).
4. If you had to change any source file to make it compile, the diff (`git diff`), or a commit on a
   new branch, `ios-litert-gpu-bench-mac-fixes`, pushed to the repo.

## Rules for an agent doing this

- Only run the commands above, and whatever installs are needed for them: Xcode components, xcodegen
  through Homebrew. No `sudo` beyond what an Xcode install asks for.
- **Allowed edits:** only to fix compile errors in `App/*.swift` or `Tests/*.swift`, or a wrong setting
  in `project.yml`. Keep each change minimal, and keep what the code measures. Don't remove configs,
  metrics or the CPU comparison. Report every edit.
- **Don't:**
  - commit `out/` or the generated `LiteRtGpuBench.xcodeproj`
  - change the LiteRT version (pinned to 2.4.0)
  - add other dependencies
  - upload the archive anywhere public
- **If signing fails,** stop and ask the Mac's owner. They need to sign in to Xcode or register a
  device. Don't try to work around signing.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `xcodegen missing` | `brew install xcodegen` |
| `No Account for Team` / `No profiles for 'dev.litertgpubench...' were found` | Sign in to Xcode with the Apple ID of that team. With a free Personal Team, connect an iPhone once (see "What you need"). |
| `Communication with Apple failed ... no devices` | Free Personal Team without a registered device: connect an iPhone with Developer Mode on, run any app on it once, then retry. |
| `... bundle identifier is not available` | `BUNDLE_ID=dev.litertgpubench.<unique> DEVELOPMENT_TEAM=... ./scripts/build_xctest_zip.sh` |
| Swift package resolution fails (LiteRT-Swift) | Check access to github.com and retry. To start clean: `rm -rf out/build out/build-sim ~/Library/Developer/Xcode/DerivedData/LiteRtGpuBench-*` |
| Compile error in `BenchRunner.swift` | Fix it minimally (see the rules above) and report the diff. The code was written against the LiteRT-Swift 2.4.0 sources but never compiled on a Mac before this build. |
| `missing: .../LiteRtMetalAccelerator.framework` (step 4) | The GPU plugin was not embedded. In `project.yml` the app target must depend on the `LiteRtMetalAccelerator` product. Report it. |
| Simulator smoke test: `no results.jsonl` | Look at `out/sim_smoke_xcodebuild.log`. Usually a test crash (search for `Fatal error` / `Thread 0 Crashed`). Send that part to Akio. |

## How it works (for reference)

- `project.yml` is the XcodeGen spec. It defines the app `LiteRtGpuBench` (SwiftUI shell) and the
  hosted unit-test bundle `LiteRtGpuBenchTests`. Dependencies are LiteRT-Swift 2.4.0, with the
  products `LiteRT` and `LiteRtMetalAccelerator`.
- `App/BenchRunner.swift` reads `Documents/bench_plan.json` and compiles each model with LiteRT's
  `CompiledModel` on the CPU and on the Metal GPU, under several GPU options: precision FP32, FP16 and
  FP16 with FP32 accumulation, texture storage, and the program cache.
  - It times write+run+read and run alone.
  - It compares every GPU output with the CPU output (cosine, max diff, Jaccard).
  - It writes `Documents/bench_out/results.jsonl`, the same format as CyanBridge's Android
    `GpuSweepTest`.
- `Tests/LiteRtGpuBenchTests.swift` holds `test01_device` (device facts) and `test02_plan` (the run).
- `scripts/` are the Mac scripts above. `host/` holds Akio's Linux-side scripts, which you don't need:
  pack preparation, Device Run submission and the summary.
