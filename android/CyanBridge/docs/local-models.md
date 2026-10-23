# Local Models in HeyCyan

This app supports fully offline on-device chat inference with both GGUF (llama.cpp) and LiteRT-LM model packages.

## Supported format

- `GGUF` model files (`.gguf`) for llama.cpp runtime.
- LiteRT-LM model bundles (`.litertlm`) and LiteRT task bundles (`.task`) for LiteRT runtime.

## Where models are stored

- App-private storage: `files/local_models/models`
- Temporary downloads: `files/local_models/tmp`

## Current local backend

- llama.cpp via Android binding (`llamacpp-kotlin`)
- LiteRT-LM via Android SDK (`com.google.ai.edge.litertlm:litertlm-android`)
- One active local model per session (loaded on demand, unloaded when requested)

## Curated starter catalog

- Qwen2.5 0.5B Instruct (Q4)
- Gemma 4 E2B IT (LiteRT-LM)
- Gemma 4 E4B IT (LiteRT-LM)
- Qwen2.5 1.5B Instruct (Q4)

## Gemma gating behavior

- Current curated Gemma 4 LiteRT entries are direct downloads.
- Gated-model UI and token support remains available for future gated entries.
- Optional Hugging Face token field is available in Local Models settings for future gated integrations.

## How to use

1. Open Settings -> AI / Automation -> Configure Local Models.
2. Download a curated model or import a local `.gguf` / `.litertlm` file.
3. Select "Local Models" as provider.
4. Open Chat and send a message.

## LensMoo photo and voice questions

LensMoo Lab uses the same Local Models backend for photo chat and hands-free
questions. With **Use remote OpenAI-compatible server** off, select an installed
Gemma model with the LiteRT runtime. With it on, configure the cloud endpoint,
model name, and any required API key, then Save. A failed cloud request is not
silently retried on the phone or another cloud provider.

Voice questions use Android speech recognition and speech synthesis; selecting
an on-device LLM does not guarantee offline speech recognition. The hands-free
answer deadline allows ten minutes for first-use model initialization. LensMoo
photo chats skip background title generation and automatic memory extraction.

## Runtime states shown in UI

- not downloaded
- loading
- ready
- generating
- error

## Tuning knobs

- profile: Fast / Balanced / High quality
- temperature, top-p, top-k
- max output tokens
- repetition penalty
- context size
- seed
- compute backend (CPU / GPU Experimental)
- GPU layers (`-1` auto offload, or explicit layer count)
- template override
- experimental structured JSON mode (off by default)

## GPU fallback behavior

- GPU mode first attempts your selected `n_gpu_layers` value.
- If initialization fails, runtime retries a lower GPU-layer ladder before giving up.
- If all attempts fail, app falls back to CPU and surfaces the fallback reason.

## How prompt templates are selected

- Default template comes from model metadata.
- User can override per model in advanced settings.
- Built-in templates:
  - `qwen_chat`
  - `gemma_it`
  - `generic_chatml`
  - `raw_completion`

## Adding curated models

Edit `LocalModelCatalogRepository.curatedModels` in:

- `app/src/main/java/com/fersaiyan/cyanbridge/localmodels/catalog/LocalModelCatalog.kt`

Each entry should include:

- id, displayName, family
- source URL/page URL
- expected filename
- size/quantization/context defaults
- prompt template id
- RAM/storage recommendations
- tags, gating, license notes

## Testing checklist

- Configure Local Models screen shows catalog and installed models.
- Importing `.gguf` registers model and makes it selectable.
- Selecting Local Models provider in Settings routes chat to local backend.
- Chat streams incremental tokens and supports Stop.
- Switching away during generation does not crash.

## Known limitations

- Structured JSON mode is experimental and grammar-constrained; output can still fail to parse in some prompts.
- Multimodal image/audio input is currently supported on LiteRT models; llama.cpp remains text-only.
- Download pause/resume is not implemented; cancel is supported.
