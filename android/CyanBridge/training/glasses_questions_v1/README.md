# Glasses questions v1

Spoken questions a CyanBridge user asks through the glasses, labelled by what answering needs: the camera, fresh
web information, the device clock, or nothing. Use them to test the routing decision (camera / web / local) and the
end-to-end answer.

- `questions.jsonl` (v1.1): 351 rows. 9 languages × 39 phrasing families.
- `images_manifest.json`: provenance of the 9 fixture photos (source, author, license, SHA-256).
- Audio and images live on Seagate: `/mnt/seagate/ML/cyanbridge-glasses-questions-20261023/`
  (`audio/`, `images/`, `manifest.json`, `SHA256SUMS`; 429 clips + 9 images, 35 MB). This is the full v1.1 set.
  The v1.0 set (`…-20261022/`, 308 clips) is kept unchanged, and its 308 clips are byte-identical inside v1.1.
- A hermetic CI subset lives in `app/src/androidTest/assets/glasses_questions/` (1.4 MB). It has en and pt-BR, the
  primary voice only, and the families `color_of_object`, `read_sign`, `capital_city`, `tell_joke`, `tell_me_more`,
  `weather_today` and `next_holiday`, plus their 3 images and a `manifest.json`.
- Generator: `tools/benchmarks/make_glasses_question_fixtures.py` (repo root).

## Row schema

```json
{"id": "en_color_of_object", "lang": "en", "text": "What color are these socks?", "class": "visual",
 "family": "color_of_object", "image": "socks_yellow", "expect": ["yellow"], "split": "test"}
```

| field | meaning |
|---|---|
| `id` | `<lang code>_<family>`. The lang codes are `en ptbr es de fr it zhcn ko ru`. |
| `lang` | `en`, `pt-BR`, `es`, `de`, `fr`, `it`, `zh-CN`, `ko`, `ru` |
| `text` | The question as a native speaker would say it to glasses. Rows are written per language and are not literal translations. For example, `definition_word` asks about *saudade* in pt-BR, *Fernweh* in de, *内卷* in zh-CN and *눈치* in ko. In fr, `translate_menu` becomes "Qu'est-ce qu'il y a au menu ?", because the fixture menu is French. |
| `class` | `visual`, `plain`, `ambiguous`, `web` or `time` (see below) |
| `family` | Phrasing family shared across languages. Splits are assigned per family. |
| `image` | Fixture image id (`images/<id>.jpg`) when the photo answers the question, else `null` |
| `expect` | A list of lowercase keywords in that language. A correct answer *about that photo* contains at least one of them (case-insensitive substring match). It is `null` for plain, web and time rows and for image-less rows. It is a loose presence check: it does not verify polarity ("ripe" also matches "not ripe"). Match numeric keywords such as `2` or `3` as whole tokens. |
| `split` | `train` / `dev` / `test`, assigned by family |

## Classes

- **visual**: the question needs the camera. It is usually deictic ("these socks", "this sign", "here") and never
  names the camera, photo or image. Examples: colour, reading a sign, label or box, price, ripeness, bus number,
  brand, menu, counting people, identifying a plant.
- **plain**: no camera and no fresh data needed. Examples: facts, unit conversion, arithmetic, definitions, jokes,
  translating a spoken phrase, how-to, small talk.
- **ambiguous**: can go either way ("Tell me more about this", "Is it any good?", "Is this a good deal?",
  "What do you think?"). A photo helps if one exists.
- **web** (v1.1): needs fresh information that the model can't know. Examples: weather today/tomorrow, the next public
  holiday (feriado, festivo, Feiertag, jour férié, giorno festivo, 节假日, 공휴일, праздник), latest news, when a local
  team plays next, the USD exchange rate, a pharmacy open nearby, traffic to work, the current price of a product.
- **time** (v1.1): needs only the device clock/calendar: today's date, the day of the week, days until Christmas, the
  time in Tokyo. These guard against the router over-triggering web.

## Expected routing decision (v1)

| class | image route (photo already attached) | voice route (no photo yet) |
|---|---|---|
| visual | Answer using the existing photo. | Take a photo, then answer, reusing the **same** audio/question (no re-prompt). |
| plain | Answer by voice and ignore the photo. | Answer by voice and skip the camera. |
| ambiguous | Use the photo. | Deictic questions ("Tell me more about this", "Is it any good?", "Is this a good deal?") are now camera-worthy: take a photo, then answer. Non-deictic ones ("What do you think?") get a voice answer. Never ask a clarifying question. **Provisional.** |
| web | Use the web route ("needs fresh information"). A photo is irrelevant. | Use the web route ("needs fresh information"). Do not capture. |
| time | Answer locally with the device date/time context. No web, no photo. | Answer locally with the device date/time context. |

The ambiguous row is provisional. In v1.1, deictic ambiguous questions with no photo are treated as camera-worthy;
the owner still needs to confirm this.

## Families and splits

Splits are by family: 21 train (54%), 8 dev (21%), 10 test (26%). In test are `color_of_object` (socks),
`read_sign` (sign) and the weather and next-holiday families (`weather_today`, `weather_tomorrow`, `next_holiday`).

| split | visual | plain | ambiguous | web | time |
|---|---|---|---|---|---|
| test | color_of_object (socks_yellow), read_sign (sign_no_parking) | capital_city, tell_joke, unit_conversion | tell_me_more (plant_aloe) | weather_today, weather_tomorrow, next_holiday | day_of_week |
| dev | banana_ripe (banana_ripe), which_bus (bus_london_2), identify_plant (plant_aloe) | arithmetic, translate_phrase | is_it_good (cereal_box_krave) | news_headline | days_until_christmas |
| train | price_check (price_tag_399), label_text (cereal_box_krave), brand_of_product (cereal_box_krave), whats_written_here (price_tag_399), read_box_text (no image), translate_menu (menu_fr_chalkboard), count_people (people_three) | definition_word, how_to_boil_egg, small_talk, history_fact, science_sky_blue | good_deal (price_tag_399), what_do_you_think (no image) | exchange_rate, team_next_game, pharmacy_open, traffic_to_work, online_price | date_today, time_zone |

Each language has 12 visual, 10 plain, 4 ambiguous, 9 web and 4 time rows. In total there are 108 visual, 90 plain,
36 ambiguous, 81 web and 36 time rows. By split there are 189 train, 72 dev and 90 test rows.

v1.1 reclassified two v1.0 families without changing their text or audio. `weather_tomorrow` moved from plain to web,
and from train to test. `time_zone` ("What time is it in Tokyo right now?") moved from plain to time.

The web teams and currencies are local to each language: Lakers, Flamengo, Real Madrid, FC Bayern, PSG, Juve, the
national football team (国足), LG Twins and Spartak. In en the exchange rate is dollar to euro; elsewhere it is the
dollar against the local currency.

`read_box_text` ("Can you read this box for me? I'm looking for the ibuprofen.") is phrased as a reading request,
not a medical question, and has no fixture photo.

## Fixture images

These are real photos from Wikimedia Commons, pinned by title and Commons SHA-1, EXIF-transposed and resized to a
maximum of 1024 px as JPEG q85. Their attribution is in `images_manifest.json`. CC BY images require attribution
whenever they are redistributed.

| image | Commons file | author | license |
|---|---|---|---|
| socks_yellow | [Bright yellow toe socks.jpg](https://commons.wikimedia.org/wiki/File:Bright_yellow_toe_socks.jpg) | hkboyee | CC BY 2.0 |
| sign_no_parking | [No Parking Any Time sign with crackle patina, New Orleans.jpg](https://commons.wikimedia.org/wiki/File:No_Parking_Any_Time_sign_with_crackle_patina,_New_Orleans.jpg) | Bart Everson | CC BY 2.0 |
| banana_ripe | [Ripe-bananas.jpg](https://commons.wikimedia.org/wiki/File:Ripe-bananas.jpg) | MartinThoma | CC0 |
| price_tag_399 | ["wow" price tag.jpg](https://commons.wikimedia.org/wiki/File:%22wow%22_price_tag.jpg) ($3.99) | Tessa Bury | CC BY 4.0 |
| bus_london_2 | [London Bus route 2.jpg](https://commons.wikimedia.org/wiki/File:London_Bus_route_2.jpg) (route 2, Norwood Garage) | Oxyman | CC BY 2.5 |
| cereal_box_krave | [Box of Kellog's Krave Double Chocolate cereal.jpg](https://commons.wikimedia.org/wiki/File:Box_of_Kellog%27s_Krave_Double_Chocolate_cereal.jpg) | VarunRajendran | CC BY 4.0 |
| menu_fr_chalkboard | [Menu de restaurant à Pont-Saint-Esprit.JPG](https://commons.wikimedia.org/wiki/File:Menu_de_restaurant_%C3%A0_Pont-Saint-Esprit.JPG) (French chalkboard) | Benoît Prieur | CC0 |
| plant_aloe | [Aloe vera D190512.jpg](https://commons.wikimedia.org/wiki/File:Aloe_vera_D190512.jpg) | Tangopaso | Public domain |
| people_three | [Three walking students, Tulane University New Orleans, March 2008.jpg](https://commons.wikimedia.org/wiki/File:Three_walking_students,_Tulane_University_New_Orleans,_March_2008.jpg) (3 people) | Tulane Public Relations | CC BY 2.0 |

The walking-aid `bus.jpg` was not reused. Its license is not documented in `tools/hil/walking_aid_assets.tsv` or
`app/src/androidTest/assets/walkingaid/README.md`.

## Audio

Every row is synthesized with stock voices only. There are no cloned voices and no reference recordings.

- Format: 16 kHz mono PCM16 WAV.
- Resampling: scipy `resample_poly`.
- Trimming: the engine's own silence is trimmed below −50 dB re peak, keeping 40 ms of margin.
- Padding: 300 ms of digital silence before and 600 ms after.
- Level: peak-normalized to −3 dBFS, with no clipping.
- File names: `audio/<id>__<voice>.wav`.

| lang | voice id | model | license |
|---|---|---|---|
| en | piper-lessac (primary) | Piper `en_US-lessac-medium` | Piper MIT; dataset: Blizzard 2013 Lessac license (non-commercial research) |
| en | kokoro-af_heart | Kokoro-82M preset `af_heart` | Apache-2.0 |
| pt-BR | piper-faber (primary) | Piper `pt_BR-faber-medium` | CC0 dataset |
| pt-BR | kokoro-pf_dora | Kokoro-82M preset `pf_dora` | Apache-2.0 |
| es | piper-davefx | Piper `es_ES-davefx-medium` | CC0 dataset |
| de | piper-thorsten | Piper `de_DE-thorsten-medium` | CC0 dataset |
| fr | piper-siwis | Piper `fr_FR-siwis-medium` | CC-BY 4.0 dataset |
| it | piper-paola | Piper `it_IT-paola-medium` | CC0-1.0 dataset |
| ru | piper-dmitri | Piper `ru_RU-dmitri-medium` | CC0 dataset |
| zh-CN | piper-huayan | Piper `zh_CN-huayan-medium` | dataset license **unknown** per its MODEL_CARD |
| ko | supertonic2-F1 | Supertonic 2 preset `F1` (supertonic 1.3.1) | OpenRAIL-M weights, MIT code |

The models are pinned as follows:

- Piper voices: `rhasspy/piper-voices@5041a7a49ae5b3a0f30e6837769eb03fdbb231bd`. The `.onnx` SHA-256 is checked
  before use.
- Kokoro: `hexgrad/Kokoro-82M@f3ff3571791e39611d31c381e3a41a3af07b4987`, run on CPU. Torch is seeded per text
  because the vocoder draws random noise.
  - The v1.0 venv (`~/nvme_tmp/tts_bench/venv_kokoro`, torch 2.14) and its HF cache were removed before v1.1.
  - The 44 new v1.1 Kokoro clips were made with the TRIBE env (`~/nvme_tmp/tribev2/env`: kokoro/misaki 0.9.4,
    torch 2.6) from the same pinned weights in `~/nvme_tmp/tribev2/hf`. The voice files' SHA-256 values match.
  - Re-synthesizing a v1.0 clip there gives the same length with correlation 0.99999 (max sample diff ≈ 5e-3), so
    it is not bit-exact. The v1.0 clips were not regenerated.
- Supertonic 2: `Supertone/supertonic-2@75e6727618a02f323c720cba9478152d4bc16ca4`. Its noise is seeded per text,
  so output is deterministic.
  - Its v1.0 venv and model directory were also removed. For v1.1 they were reinstalled at the same pins, outside
    conda base: `~/nvme_tmp/glasses_q_fixtures/venv_supertonic` (supertonic 1.3.1, onnxruntime 1.31.0, numpy 2.4.6),
    with the model in `~/nvme_tmp/glasses_q_fixtures/models/supertonic2`.
  - This reproduces the v1.0 Korean clips bit-exactly.

Piper runs with `noise_scale=0` and `noise_w_scale=0`. Two full runs of `all` produced byte-identical
`SHA256SUMS` within one environment (v1.0).

The CC0 voice `zh_CN-chaowen-medium` was not used because it needs the g2pW phonemizer, which is not installed.
`zh_CN-huayan` uses espeak-ng instead. Swap the voice in `VOICES` if the huayan license matters.

## Reproduce (v1.1 commands)

The engines live in different existing environments. Nothing is installed into conda `base`; Piper only *runs*
from it.

```bash
S=tools/benchmarks/make_glasses_question_fixtures.py
# 0. Piper voices (pinned; lessac/faber already in ~/nvme_tmp/tribev2/inputs/voices)
REV=5041a7a49ae5b3a0f30e6837769eb03fdbb231bd; D=~/nvme_tmp/glasses_q_fixtures/dl_piper_voices; mkdir -p $D
for p in es/es_ES/davefx/medium de/de_DE/thorsten/medium fr/fr_FR/siwis/medium it/it_IT/paola/medium \
         ru/ru_RU/dmitri/medium zh/zh_CN/huayan/medium; do n=$(echo $p | awk -F/ '{print $2"-"$3"-"$4}')
  for f in $n.onnx $n.onnx.json MODEL_CARD; do
    curl -sfL -o $D/$( [ $f = MODEL_CARD ] && echo $n.MODEL_CARD || echo $f ) \
      https://huggingface.co/rhasspy/piper-voices/resolve/$REV/$p/$f; done; done
# 1. images -> ~/nvme_tmp/glasses_q_fixtures/build/images (originals in .../dl_commons)
~/anaconda3/bin/python -I $S images
# 2. synthesize (CPU)
~/anaconda3/bin/python -I $S synth --engine piper
HF_HUB_OFFLINE=1 HF_HOME=~/nvme_tmp/tribev2/hf ~/nvme_tmp/tribev2/env/bin/python -I $S synth --engine kokoro --device cpu
SUPERTONIC_CACHE_DIR=~/nvme_tmp/glasses_q_fixtures/models/supertonic2 ~/nvme_tmp/glasses_q_fixtures/venv_supertonic/bin/python -I $S synth --engine supertonic
# 3. resample/pad/normalize + manifest.json + SHA256SUMS, then the CI subset
~/anaconda3/bin/python -I $S finalize
~/anaconda3/bin/python -I $S ci-subset
# 4. publish a NEW dated dir on Seagate (idle I/O) and verify; never overwrite an earlier version
B=~/nvme_tmp/glasses_q_fixtures/build; D=/mnt/seagate/ML/cyanbridge-glasses-questions-20261023; mkdir -p $D
ionice -c3 cp -r $B/audio $B/images $B/manifest.json $B/SHA256SUMS $D/ && (cd $D && ionice -c3 sha256sum -c --quiet SHA256SUMS)
```

`python -I $S all` chains steps 1–3 using the same interpreters. `synth` skips clips that already exist unless you
pass `--force`. `finalize` refuses to run if a row's text changed after synthesis.

## Sanity check (v1.0, 308 clips)

- 308 clips. Every clip is 16 kHz/mono/PCM16 and peaks at −3.0 dBFS. None is clipped or empty, and every clip has
  exactly 300/600 ms of digital-zero padding.
- Median duration is 2.1–2.7 s per voice. The range is 1.44 s (it) to 5.70 s (ko).
- ASR round trip used Parakeet-TDT-0.6B-v3 (onnx-asr, CPU, an existing local install). It covered the 252 clips in
  en/pt-BR/es/de/fr/it/ru. Overall WER was 0.089, and 193 of 252 clips matched exactly.
  - Per voice: en lessac 0.019, en af_heart 0.026, de 0.047, fr 0.079, it 0.101, es 0.129, pt-BR faber 0.139,
    pt-BR pf_dora 0.139, ru 0.142.
  - A random 20-clip sample had a WER of 0.029, with 19 of 20 exact.
  - Most errors are numerals ("fifteen percent of eighty" → "15% of 80"), rare words (serendipia/serendipità) and
    very short clips. The ru Dmitri voice weakens some initial consonants (Что→То, Сколько→Колька). Both effects
    reproduce on the untrimmed audio.
- zh-CN and ko were not ASR-checked, because no installed local ASR covers them. Only their durations and levels
  were checked.

## Sanity check (v1.1 additions, 121 new clips)

- The new clips are 11 families × 9 languages, plus the second en/pt-BR voices. They pass the same format, level
  and padding checks: −3.0 dBFS peak, none clipped or empty.
- Median duration is 2.3–2.8 s per voice. The range is 1.67–3.62 s.
- Parakeet-TDT-0.6B-v3 on the 99 new en/pt-BR/es/de/fr/it/ru clips: overall WER 0.052, with 80/99 exact.
  - Per voice: en lessac 0.000, en af_heart 0.000, fr 0.013, es 0.016, de 0.042, ru 0.043, it 0.083, pt-BR faber
    0.145, pt-BR pf_dora 0.145.
  - The pt-BR "errors" are mostly the recogniser formalizing colloquial speech: tá→está, pro→para o.
- zh-CN and ko were not ASR-checked; only their durations and levels were.
