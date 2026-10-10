# Public dataset discovery inventory for Laya and Needle

Reviewed 2026-10-03. `catalog.json` is metadata, **not a training corpus**.
Detailed source suitability, conversion rules and specialist contracts:
[`LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md`](../../docs/LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md).

Recommended acquisition order:

1. MASSIVE/SLURP **text** and CLINC OOS for voice routing/grounded slots.
2. Google Mobile Actions `metadata=train` and reviewed DroidCall structured
   training rows for compatible phone capabilities.
3. AndroidControl official train episodes for provenance-tagged projected UI
   selection; retain separate Tasker-observed deployment data.
4. CyanBridge capability/plugin/setting examples collected from finalized voice
   requests and typed app state; public data has no native labels for these.

The October 3 review parsed pinned Mobile Actions and DroidCall training
sources in memory, counted call cardinality, and inspected a training example
from each. Mobile Actions evaluation rows were counted by metadata, not replayed
or used for tuning. Other inventories were reviewed through publisher docs,
cards and metadata; no UI TFRecords/screenshots, audio archives or model weights
were acquired. Counts marked `publisher` are not independent file audits.

The [specialist pilot](../laya_needle_specialists_v1/README.md) now converts
bounded **operation-only subsets** of Mobile Actions and DroidCall; the other
sources remain unconverted. It does not learn original source arguments or
observe Android execution. Preserve explicit source partitions, source IDs
and derived-group provenance before any augmentation;
MASSIVE/SLURP and related NLU corpora must not cross our splits as independent
rows. Put acquired raw data under a dedicated external directory such as
`/tmp/opencode/laya-needle-datasets/`, with byte hashes and source notices.
Keep the new product final test sealed. These sources do not make the existing
Tasker pool independently split-ready.

Important measured data gaps:

- Mobile Actions train: 8,693 positives, **zero no-call examples**; internal
  metadata excludes 961 evaluation rows even though HF calls the whole file train.
- DroidCall structured train: 10,071 positives, **zero no-call examples**;
  source tools/defaults/dependencies need explicit grounding/schema review.
- Neither source is Tasker UI supervision. Add scoped unsupported, ambiguous,
  negated, invalid and missing-target cases from our actual contracts.
