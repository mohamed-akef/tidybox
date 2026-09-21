# Research: the on-device model option, if we ever need it

Researched 2026-09-16. This document exists so that
[D4](../decisions.md#d4--rules-and-user-corrections-no-model)'s hedge is a **real, costed
option** rather than a polite gesture. If [the spike](../spike/README.md) shows the
unknown-merchant tail is bigger than expected, this is what filling the pluggable slot
would actually involve.

> **2026-09-22, twice.** In the morning the slot was filled with a small sentence-embedding
> classifier instead of a generative LLM. In the afternoon [the spike](../spike/RESULT.md)
> measured it: it added nothing on 174 real messages and produced one confident wrong
> answer. The slot is **empty again** — see
> [D4](../decisions.md#d4--rules-and-user-corrections-no-model). This document stays as the
> costing of the generative option, for the day a bigger corpus reopens the question.

It is **not** a plan to build this. Read [research 03](03-categorization-without-an-llm.md)
first for why rules come first.

## What the model would and would not do

**Not extraction.** Amount, date, card and merchant come from labeled fields. A model
there adds latency, battery and non-determinism in exchange for nothing.

**Only step 4 of the categorizer**, and only its *last* resort — after user overrides,
exact dictionary match, keyword rules and fuzzy match have all missed. Input would be a
single merchant string; output, one of a fixed category list.

This narrowness is what makes the slot cheap: one function, one interface, one fallback.

## The runtime

**`flutter_gemma`** is the mature path — a Flutter plugin wrapping Google's **MediaPipe
LLM Inference API**, supporting Android, iOS and Web.

Two model formats matter:

| Format | Notes |
|---|---|
| `.task` | The established MediaPipe format. Works reliably on Android, iOS and Web today. |
| `.litertlm` | Newer Google format — better compression, richer metadata. MediaPipe runs it, but **NPU acceleration requires the separate LiteRT-LM runtime**, which also adds desktop support. |

## Candidate models

| Model | Fit |
|---|---|
| **Gemma 3 270M** | The realistic candidate. Small enough to ship. There is also a **FunctionGemma 270M** variant tuned for on-device function calling — closer in shape to "classify into a fixed set" than a chat model is. |
| **Gemma 3 1B** | Demonstrated running on-device via Flutter + MediaPipe, including iOS. Materially better quality, materially worse download and memory cost. |

## What it would cost

- **Download:** roughly **300 MB – 1 GB** depending on model and quantization. For an app
  whose Android APK would otherwise be tens of megabytes, this is the dominant cost and it
  lands on the user's data plan. It should be an **opt-in download**, never bundled.
- **Memory and battery:** a per-inference cost on a step that currently takes microseconds.
  Acceptable only because it runs on the rare fallback path, not per message.
- **Determinism:** lost. The pipeline can no longer always answer *"which rule fired?"*,
  so any model-assigned category must be **visibly marked as a guess** in the UI and must
  never silently overwrite a user correction.

## iOS status

**Correction, 2026-09-22.** An earlier draft said MediaPipe's iOS LLM inference API was
"not fully public". That was `flutter_gemma`'s status, not Google's: Google publishes the
iOS install and Swift inference guide for the MediaPipe LLM Inference API. On the native
stack chosen in [D5](../decisions.md#d5--kotlin-not-flutter-kotlin-multiplatform-for-the-shared-engine)
the generative option is therefore reachable on both platforms — Android via MediaPipe /
LiteRT-LM from Kotlin, iOS via the MediaPipe Swift API. An embedding model, if ever
wanted, runs through LiteRT on Android and Core ML or LiteRT on iOS.

So iOS is not what keeps the generative option out; the cost and the "confident wrong
answer" argument do. The app must still work with the slot empty, which it does by
construction: the model is a fallback inside one step.

## The honest summary

The technology works and is reachable from Kotlin and Swift today. The reasons not to reach for it
are unchanged by any of the above:

1. It cannot help where the information is **not in the string** (`ALDR 8821 RUH`) — it
   will invent a confident answer, which is worse than a blank one.
2. Where the information **is** in the string, a dictionary entry already wins, for free.
3. Small models are weakest exactly where this problem is hardest: Arabic and
   transliterated Saudi merchant abbreviations.

So the slot stays empty until measurement says otherwise — and now, if it ever does, the
design already knows what goes in it.

## Sources

- [flutter_gemma](https://pub.dev/packages/flutter_gemma) · [docs](https://fluttergemma.dev/docs/models)
- [Deploy Gemma on mobile devices — Google AI](https://ai.google.dev/gemma/docs/integrations/mobile)
- [On-device function calling with FunctionGemma](https://medium.com/google-developer-experts/on-device-function-calling-with-functiongemma-39f7407e5d83)
- [Building an on-device Gemma 3 chat app with Flutter](https://medium.com/@kennethan/building-an-on-device-gemma-3-chat-app-with-flutter-baaa53de69e3)
