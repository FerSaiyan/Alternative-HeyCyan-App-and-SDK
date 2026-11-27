# LiteRtGpuBench

CPU vs Metal GPU benchmark of CyanBridge's LiteRT models on real iPhones (Google Device Run), with
LiteRT 2.4.0 (`google-ai-edge/LiteRT-Swift`). It is the iOS port of the Android `GpuSweepTest`: same
configs, metrics and JSON row keys. NPU (Apple Neural Engine) is out of scope until LiteRT supports it.

- **Mac build** (once per code change): [`MAC_BUILD.md`](MAC_BUILD.md). The output is
  `LiteRtGpuBench-xctest.zip`.
- **Runs** (Linux, no rebuild needed to change models or configs):

```bash
# 1. Pack: models + public inputs (COCO, LibriSpeech, rendered text) + bench_plan.json
~/nvme_tmp/rt1126/qenv/bin/python -I host/prepare_pack.py --out ~/nvme_tmp/ios-bench/pack_$(date +%Y%m%d)

# 2. Submit to iPhone 17 Pro + iPhone 16 Pro, pull bench_out, write SUMMARY.md
host/submit_device_run.sh LiteRtGpuBench-xctest.zip ~/nvme_tmp/ios-bench/pack_YYYYMMDD

# Re-summarize a downloaded session
python3 -I host/summarize.py ~/nvme_tmp/ios-bench/sessions/<stamp>
```

## Design

- **Where files go:** Device Run pushes each pack file to `BUNDLE_ID:/Documents/<name>`
  (`--other-files-to-push`) and pulls `BUNDLE_ID:/Documents/bench_out` (`--paths-to-pull`). Every
  result row is also logged as `LRTBENCH {json}` into the device syslog (`system.log`).
- **GPU options** go through the `gpu_options` opaque option as a TOML string. That is the format the
  Metal accelerator parses (`LrtCreateGpuOptionsFromToml`): `precision`, `buffer_storage_type`,
  `serialization_dir` / `model_cache_key` / `serialize_program_cache`, `enable_metal_residency_set`.
  - The Swift package has no `GpuOptions` type in 2.4.0.
  - It is not yet confirmed that Metal honors every key. Compare the rows per config.
- **Finding the Metal plugin:** the runner first lets LiteRT find `LiteRtMetalAccelerator.framework`
  by itself. If GPU compilation fails, it retries with `runtimeLibraryDir` pointing at the framework.
  The `gpu_probe` row records which attempt worked.
- **Config tokens** (`bench_plan.json`): `cpu[:tN]`, `gpu[:gc]:<DEFAULT|FP16|FP32|FP16_WITH_FP32_ACCUM>[:tex|:buf][:cache][:nores]`.
  The header of `App/BenchRunner.swift` documents them.
