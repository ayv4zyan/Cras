# DeepInfra Voxtral + Gemma 4 for in-app STT

**Status (2026-08-18):** research for [What do DeepInfra Voxtral Small and Gemma 4 support for in-app STT?](https://github.com/ayv4zyan/Cras/issues/9). Product lock on [How should voice create, edit, and extract task metadata?](https://github.com/ayv4zyan/Cras/issues/8) is **closed** (in-app Voice capture + Drafts). Token placement is still open on [How do DeepInfra credentials live on web and Android?](https://github.com/ayv4zyan/Cras/issues/18) — Hono may now hold the secret. Android capture is Kotlin (not RN); DeepInfra formats, slugs, and “proxy for secret + transcode” facts do not change.

Primary sources only: DeepInfra catalog/docs/API, Mistral and Google model-owner docs, and the local OpenWhispr shim at `/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim`.

Checked 2026-08-17. Prices and catalog tags can change; slugs below are from DeepInfra’s live `/models/list`.

## Verdict

Voxtral **Small exists on DeepInfra** as `mistralai/Voxtral-Small-24B-2507`. The working OpenWhispr stack uses **Mini**, not Small: `mistralai/Voxtral-Mini-3B-2507` + `google/gemma-4-E4B-it`. DeepInfra’s speech docs only promise **mp3/wav**; the shim exists because **WebM 500s**. An in-app client can reach the API (CORS is `*`), but a **proxy is still required** to hold the secret and transcode recorder output. Current Gemma cleanup is **dictation cleanup**, not reliable title/date/time JSON — that is a separate structured-output job, and E4B is **not** tagged for JSON mode.

---

## 1. Voxtral slugs: Small exists; Mini is what the shim uses

DeepInfra’s ASR catalog and live model list both publish two Voxtral models:

| DeepInfra slug | Type | Price | Context | Precision |
| --- | --- | --- | --- | --- |
| `mistralai/Voxtral-Mini-3B-2507` | automatic-speech-recognition | **$0.00100 / minute** of audio | 32,768 | bf16 |
| `mistralai/Voxtral-Small-24B-2507` | automatic-speech-recognition | **$0.00300 / minute** of audio | 32,768 | bf16 |

Sources: [ASR catalog](https://deepinfra.com/models/automatic-speech-recognition/), [Mini page](https://deepinfra.com/mistralai/Voxtral-Mini-3B-2507), [Small page](https://deepinfra.com/mistralai/Voxtral-Small-24B-2507), [Mistral family page](https://deepinfra.com/mistral), [pricing](https://deepinfra.com/pricing), and `GET https://api.deepinfra.com/models/list` (`cents_per_input_sec` 0.00166667 and 0.005 → $0.001 and $0.003 per minute). Both carry tag `openai` (OpenAI-compatible transcriptions). Neither is marked deprecated or replaced on DeepInfra.

The local shim **defaults to Mini**, not Small:

- `DEEPINFRA_MODEL || "mistralai/Voxtral-Mini-3B-2507"` in [`deepinfra-voxtral-shim.js`](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/deepinfra-voxtral-shim.js)
- Same slug in [`openwhispr-settings.json`](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/openwhispr-settings.json), [`.env.example`](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/.env.example), and [README](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/README.md)

Older shim logs once printed `Default model: mistralai/Voxtral-Small-24B-2507`; current code and settings are Mini.

### What the model owner says

Mistral still documents both 2507 weights, with different product status **on Mistral’s own API** (not DeepInfra):

- **Voxtral Mini** (`voxtral-mini-2507`): Apache 2.0, 32k context, **deprecated** 2026-02-27; replacement is [Voxtral Mini Transcribe 2](https://docs.mistral.ai/models/voxtral-mini-transcribe-26-02). ([Mini card](https://docs.mistral.ai/models/voxtral-mini-25-07))
- **Voxtral Small** (`voxtral-small-25-07`): Apache 2.0, 32k, audio-instruct, structured outputs on Mistral chat/conversations. ([Small card](https://docs.mistral.ai/models/voxtral-small-25-07))
- Mistral’s current transcription *recommendation* is Mini Transcribe 2 / Realtime, not 2507 Mini or Small. ([Audio overview](https://docs.mistral.ai/studio/audio/overview))

HF / DeepInfra model copy (owner weights): dedicated transcription mode, 32k context, **up to 30 minutes transcription / 40 minutes understanding**, auto language detect (EN, ES, FR, PT, HI, DE, NL, IT). Mini has **no system prompts**; Small adds experimental function calling. ([Mini](https://huggingface.co/mistralai/Voxtral-Mini-3B-2507), mirrored on the DeepInfra demo pages.)

DeepInfra hosts both only as **speech-recognition** (`type` / `reported_type` = `automatic-speech-recognition`). There is no separate DeepInfra chat/instruct Voxtral slug. Use them as transcribers, then run cleanup on a text model.

---

## 2. Gemma 4 variants DeepInfra actually hosts

Live `GET https://api.deepinfra.com/models/list` (2026-08-17). `google/gemma-4-E2B-it` and `google/gemma-4-12B-it` are **not** in that list (their pages 404 / empty).

| Slug | Context | Quant | In / out per 1M | Catalog tags that matter |
| --- | --- | --- | --- | --- |
| `google/gemma-4-E4B-it` | **131,072** | bfloat16 | **$0.02 / $0.10** (priority 1.5×, flex 0.8×) | `openai`, `tools`, `reasoning`, `can-disable-reasoning`, `priority`, `flex` — **no `json` / `structured-output`** |
| `google/gemma-4-26B-A4B-it` | **262,144** | fp8 | **$0.07 / $0.34** | `json`, `structured-output`, `tools`, `multimodal`, `reasoning`, `featured` |
| `google/gemma-4-31B-it` | **262,144** | fp8 | **$0.13 / $0.38** | `json`, `structured-output`, `tools`, `multimodal`, `input-audio`, `input-video` |
| `google/gemma-4-31B-it-turbo` | 262,144 | fp4 | **$0.09 / $0.34**, cached $0.05 | `tools`, `reasoning` — **no `json`** |
| `google/gemma-4-31B-it-Ultra` | 131,072 | fp8 | **$0.27 / $0.76** | `json`, `tools`, `multimodal`, `input-video` |

Pages: [E4B](https://deepinfra.com/google/gemma-4-E4B-it), [26B-A4B](https://deepinfra.com/google/gemma-4-26B-A4B-it), [31B](https://deepinfra.com/google/gemma-4-31B-it), [31B-turbo](https://deepinfra.com/google/gemma-4-31B-it-turbo).

The shim default cleanup model is **`google/gemma-4-E4B-it`**. ([README](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/README.md), settings JSON, `.env.example`)

### Structured output / JSON mode

DeepInfra documents two chat `response_format` modes for **many** models: `json_object` (any JSON) and `json_schema` (strict schema, `"strict": true`). They tell you to prefer schema in production, always prompt for JSON, validate (truncation breaks JSON), and warn that JSON mode **hallucinates** values instead of saying “I don’t know.” ([Structured Outputs](https://docs.deepinfra.com/chat/structured-outputs))

Catalog **tags** are the per-model signal: `26B-A4B-it` and `31B-it` (and Ultra) are tagged `json` / `structured-output`. **E4B is not.** E4B *is* tagged `tools` (OpenAI function calling). Platform tool-calling docs apply to models that support tools. ([Tool calling](https://docs.deepinfra.com/chat/tool-calling), [chat overview](https://docs.deepinfra.com/chat/overview))

Owner card: all Gemma 4 sizes have native **function calling** and a **system** role. Thinking is `<|think|>` in the system prompt; OpenWhispr settings set `cleanupDisableThinking: true`. E2B/E4B/12B have native **audio** (max **30 s**); 26B/31B do not (owner). Context: 128K small, 256K medium. ([Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4), [overview](https://ai.google.dev/gemma/docs/core), [function calling](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4))

DeepInfra’s `31B-it` tag `input-audio` conflicts with Google’s “31B has no audio encoder.” Treat 31B-on-DeepInfra audio input as **unverified**; cleanup in this pipeline is **text-only** chat.

---

## 3. Audio formats

### What DeepInfra documents

Speech-recognition docs (Whisper tutorial + generic speech API) list supported formats as **`mp3` and `wav`**. Native examples POST `-F audio=@….mp3` to `/v1/inference/{model}`. ([Speech Recognition](https://docs.deepinfra.com/apis/speech), [Whisper tutorial](https://docs.deepinfra.com/tutorials/whisper), [Mini/Small API pages](https://deepinfra.com/mistralai/Voxtral-Mini-3B-2507/api))

The OpenAI-compatible transcriptions body is `multipart/form-data` with `file` (binary), `model`, optional `language`, `prompt`, `response_format` (`json` | `verbose_json` | `text` | `srt` | `vtt`), `temperature`, `timestamp_granularities`, `service_tier`. **No format enum.** ([Openai Audio Transcriptions](https://docs.deepinfra.com/api-reference/audio/openai-audio-transcriptions))

Mini native `in_schema` (`GET https://api.deepinfra.com/models/mistralai/Voxtral-Mini-3B-2507`): required `audio` (`format: binary`, `is_audio: true`); `task` transcribe|translate; `chunk_length_s` 1–30 (default 30); Whisper-like language codes.

### Why the shim exists

> OpenWhispr records **WebM**; DeepInfra Voxtral returns HTTP 500 on WebM. The shim converts to WAV, trims **leading/trailing silence** (not mid-phrase pauses), and holds your API token.

([README](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/README.md))

Pipeline in [`deepinfra-voxtral-shim.js`](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/deepinfra-voxtral-shim.js):

1. Accept multipart `file` (filename suffix default `.webm`).
2. `ffmpeg -ar 16000 -ac 1 -f wav`.
3. Optional edge `silenceremove` (default on, pad 0.25s, −50 dB RMS).
4. `POST https://api.deepinfra.com/v1/openai/audio/transcriptions` with `audio/wav` named `audio.wav`, `Authorization: Bearer`.
5. Return `{ text, object: "transcription" }`.

Mistral’s **own** platform lists transcription formats **WAV, MP3, FLAC, OGG, WEBM** (max 60 min, 500 MB). ([Known limitations](https://docs.mistral.ai/resources/known-limitations)) So WebM 500 is a **DeepInfra serving** issue, not a Voxtral-weights limitation.

### What Android / web recorders emit

**Android `MediaRecorder`** output formats: `THREE_GPP`, `MPEG_4`, `AMR_NB`/`AMR_WB`, `AAC_ADTS`, `MPEG_2_TS`, `OGG` (Opus), `WEBM` (VP8/Vorbis). Encoders: `AMR_NB`, `AMR_WB`, `AAC`, `HE_AAC`, `AAC_ELD`, `OPUS`, `VORBIS`. ([OutputFormat](https://developer.android.com/reference/android/media/MediaRecorder.OutputFormat), [AudioEncoder](https://developer.android.com/reference/android/media/MediaRecorder.AudioEncoder))

Google’s own sample records **`THREE_GPP` + `AMR_NB` → `.3gp`**. ([MediaRecorder overview](https://developer.android.com/media/platform/mediarecorder)) That container is **not** in DeepInfra’s mp3/wav list nor Mistral’s WAV/MP3/FLAC/OGG/WEBM list.

Platform encode support includes **PCM/WAVE** (encoder Android 4.1+), AAC in MPEG-4, Opus in Ogg/WebM. ([Supported media formats](https://developer.android.com/media/platform/supported-formats))

**Web `MediaRecorder`**: constructor takes a MIME type such as `"video/webm"` or `"video/mp4"`; `isTypeSupported()` is per-UA; MDN’s audio example builds `audio/ogg; codecs=opus`. ([MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder)) OpenWhispr’s observed output is **WebM** (shim README).

**Safe default for this pipeline:** record or transcode to **16 kHz mono WAV** (what the shim already sends) or **mp3**. Do not send WebM/3GP/AMR to DeepInfra Voxtral.

---

## 4. Auth, pricing, rate limits, latency

**Auth.** All DeepInfra endpoints need `Authorization: Bearer $DEEPINFRA_TOKEN` (dashboard keys). Scoped JWTs can lock models, expiry (≤ 1 year), and USD spend without sharing the root key. ([Authentication](https://docs.deepinfra.com/account/authentication), [API intro](https://docs.deepinfra.com/api-reference/introduction), [Quickstart](https://docs.deepinfra.com/quickstart))

Shim token order: `DEEPINFRA_TOKEN` env → project `.env` → `~/.openwhispr/deepinfra.env`. Process exits if missing. Binds **`127.0.0.1` only**. ([README](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/README.md), shim source)

**Pricing (DeepInfra, first-party):**

- Voxtral Mini **$0.001 / min**, Small **$0.003 / min**
- Gemma 4 E4B **$0.02 in / $0.10 out** per 1M tokens
- 26B-A4B **$0.07 / $0.34**; 31B **$0.13 / $0.38**
- Chat **priority** = 1.5×; **flex** = 0.8×, may wait up to 10 minutes or 429 ([chat overview](https://docs.deepinfra.com/chat/overview))

**Rate limits.** Default **200 concurrent requests per model** (not RPM). 429 `Rate limited`; occasional 429 under load even under the cap. `fail_fast: true` → immediate 429 `engine_overloaded` instead of queue. ([Rate limits](https://docs.deepinfra.com/account/rate-limits), [chat overview](https://docs.deepinfra.com/chat/overview))

**Latency.** DeepInfra does **not** publish Voxtral or E4B STT/cleanup latency. Owner Mini Transcribe 2 / Realtime numbers are **Mistral’s API**, not DeepInfra 2507. Local shim A/B log (`logs/cleanup-bench.jsonl`) shows **E4B cleanup ~0.4–1.5 s** for short dictation (text only). Shim STT/chat `fetch` timeout is **120 s**. Max chat generation is model-dependent, hard cap **16384** tokens for most models. ([chat overview](https://docs.deepinfra.com/chat/overview))

---

## 5. Direct in-app client vs still needing a shim

| Concern | First-party fact | Implication |
| --- | --- | --- |
| CORS | `OPTIONS` on `https://api.deepinfra.com/v1/openai/chat/completions` and `…/audio/transcriptions` returns `access-control-allow-origin: *`, methods include POST, headers `authorization,content-type` (live API, 2026-08-17) | A **browser** can call DeepInfra. CORS is not the reason for a proxy. |
| Secret | Full API keys are unrestricted; scoped JWTs exist ([auth docs](https://docs.deepinfra.com/account/authentication)) | Shipping a root token in a web bundle is unsafe. Android can keep a secret in app storage, but a leaked APK still burns money unless scoped. |
| Transcode | DeepInfra docs: mp3/wav; shim: WebM → HTTP 500; Android sample: 3GP/AMR | Unless the recorder emits wav/mp3, **something** must transcode (device ffmpeg/AudioRecord WAV, or a proxy). |
| Token + prompt policy | Shim injects `cleanup-prompt-short.txt`, rewrites `max_completion_tokens` → `max_tokens`, trims silence | Those are product choices, not DeepInfra requirements. |

**Conclusion:** a browser or RN app *can* POST to DeepInfra directly. A **proxy is still required** if you (a) must not expose a spendable key, or (b) cannot guarantee wav/mp3. Scoped JWTs can shrink (a) for a trusted single-operator app. They do not fix (b).

Endpoints the shim already uses:

- STT: `https://api.deepinfra.com/v1/openai/audio/transcriptions`
- Chat: `https://api.deepinfra.com/v1/openai/chat/completions`
- Native alternative: `https://api.deepinfra.com/v1/inference/mistralai/Voxtral-Mini-3B-2507` (field name `audio`, not `file`)

---

## 6. Cleanup vs smart metadata (title + date + time JSON)

What the shim **reliably** does today is **dictation cleanup**:

- Default short prompt: output **only** cleaned text; remove fillers/false starts; do **not** answer questions or add content. ([`cleanup-prompt-short.txt`](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/cleanup-prompt-short.txt))
- Stock OpenWhispr prompt (passthrough mode): same contract, plus spoken punctuation, **written forms** of numbers/dates/times (“January 15, 2026 / 5:30 PM”), self-corrections. Still “exactly the cleaned transcript and nothing else.” ([`scripts/_stock-cleanup-prompt.txt`](/Users/arturayvazyan/Sync/Projects/openwhispr-deepinfra-shim/scripts/_stock-cleanup-prompt.txt))

That is **not** `{ title, date, time }` extraction. Asking the current cleanup prompt for JSON would fight its “output only the cleaned text” rule.

What **can** be done with published APIs:

1. Keep Mini/Small for **verbatim-ish transcript**.
2. Second chat call with a **different** system prompt + `response_format: json_schema` (or tools) for title/date/time.
3. Prefer a model DeepInfra tags for JSON: **`google/gemma-4-26B-A4B-it` or `google/gemma-4-31B-it`**. E4B has **tools** but not a JSON tag — usable for function-call extraction, not documented as constrained JSON mode.
4. Pass **client clock + timezone** in the prompt. The model has no “now.” Relative speech (“tomorrow 3”) cannot be grounded otherwise.
5. Treat extracted dates as **untrusted**: DeepInfra explicitly says JSON mode fabricates real-world values. Validate, and leave fields null when the transcript has no date/time.

Owner Voxtral Small can do structured summaries / voice function-calling **on Mistral or self-hosted vLLM**, not as DeepInfra’s ASR wrapper.

---

## Implications for Cras (decisions, not implementation)

1. Say **Mini** (`mistralai/Voxtral-Mini-3B-2507`) if matching the working shim; **Small** (`mistralai/Voxtral-Small-24B-2507`) if matching the ticket name — both are public on DeepInfra. Mini Transcribe 2 is **not** on DeepInfra today.
2. Record **WAV or MP3** (or transcode). Do not send WebM/3GP/AMR to DeepInfra Voxtral.
3. Keep a **server or local proxy** for the API key and/or transcode. CORS is not the blocker.
4. Keep E4B for **cleanup prose**. Use **26B-A4B or 31B + `json_schema`** (and an injected “now”) for smart metadata — a second prompt, not the OpenWhispr cleanup prompt.

## Sources

- DeepInfra: [ASR models](https://deepinfra.com/models/automatic-speech-recognition/), [Mini](https://deepinfra.com/mistralai/Voxtral-Mini-3B-2507), [Small](https://deepinfra.com/mistralai/Voxtral-Small-24B-2507), [Mini API](https://deepinfra.com/mistralai/Voxtral-Mini-3B-2507/api), [Small API](https://deepinfra.com/mistralai/Voxtral-Small-24B-2507/api), [Mistral](https://deepinfra.com/mistral), [pricing](https://deepinfra.com/pricing), [E4B](https://deepinfra.com/google/gemma-4-E4B-it), [26B-A4B](https://deepinfra.com/google/gemma-4-26B-A4B-it), [31B](https://deepinfra.com/google/gemma-4-31B-it), [31B-turbo](https://deepinfra.com/google/gemma-4-31B-it-turbo), [speech](https://docs.deepinfra.com/apis/speech), [Whisper](https://docs.deepinfra.com/tutorials/whisper), [transcriptions OpenAPI](https://docs.deepinfra.com/api-reference/audio/openai-audio-transcriptions), [structured outputs](https://docs.deepinfra.com/chat/structured-outputs), [tools](https://docs.deepinfra.com/chat/tool-calling), [chat](https://docs.deepinfra.com/chat/overview), [auth](https://docs.deepinfra.com/account/authentication), [rate limits](https://docs.deepinfra.com/account/rate-limits), [quickstart](https://docs.deepinfra.com/quickstart), `GET /models/list`, `GET /models/mistralai/Voxtral-Mini-3B-2507`, live `OPTIONS` CORS headers
- Mistral: [Mini card](https://docs.mistral.ai/models/voxtral-mini-25-07), [Small card](https://docs.mistral.ai/models/voxtral-small-25-07), [Audio](https://docs.mistral.ai/studio/audio/overview), [known limitations](https://docs.mistral.ai/resources/known-limitations), [HF Mini](https://huggingface.co/mistralai/Voxtral-Mini-3B-2507)
- Google: [Gemma 4 card](https://ai.google.dev/gemma/docs/core/model_card_4), [overview](https://ai.google.dev/gemma/docs/core), [function calling](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4)
- Recorders: [MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder), [Android MediaRecorder](https://developer.android.com/media/platform/mediarecorder), [OutputFormat](https://developer.android.com/reference/android/media/MediaRecorder.OutputFormat), [AudioEncoder](https://developer.android.com/reference/android/media/MediaRecorder.AudioEncoder), [formats](https://developer.android.com/media/platform/supported-formats)
- Local shim: README, `deepinfra-voxtral-shim.js`, `openwhispr-settings.json`, `.env.example`, `cleanup-prompt-short.txt`, `scripts/_stock-cleanup-prompt.txt`, `logs/cleanup-bench.jsonl`
