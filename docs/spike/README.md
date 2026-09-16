# The spike: measure before building

**Purpose:** answer one question with a number instead of an opinion —

> *Does rule-based extraction and categorization work well enough on **your** bank
> messages to build a product on?*

**Output:** a recommendation, not code. Everything built here is throwaway and labeled
as such. **Cost:** about a day.

## Why this exists

The design rests on [D4](../decisions.md#d4--rules-and-user-corrections-no-llm) — rules
and user corrections, no model. The argument for it is
[strong and evidence-backed](../research/03-categorization-without-an-llm.md), but the
coverage figure is **unmeasured on real Saudi messages**, and the only published number
found anywhere is Mizan's **~79%**.

79% is not obviously good enough. One message in five landing wrong is the difference
between an app you trust and an app you abandon. So we measure it before committing,
on real messages, and let the number decide.

## The three numbers

| Metric | Definition | Rough bar |
|---|---|---|
| **M1 — Extraction rate** | % of transaction messages where amount, date, card and merchant are all extracted correctly | Should be very high (>95%). The messages are labeled forms; if this is low, something is wrong with the approach, not the data. |
| **M2 — Auto-categorization rate** | % of extracted transactions given a *correct* category with **no** user input, using only the seed dictionary | The decisive number. High → D4 confirmed. Low → the model question genuinely reopens. |
| **M3 — Residual tap rate** | % needing one user tap, and how fast that curve decays as corrections accumulate | Determines whether "it teaches itself in two weeks" is true or wishful. |

Two correctness checks run alongside, because they are the quiet killers:

- **No declined transaction becomes a transaction.**
- **Every refund carries the correct sign.**

## Method

1. Collect 30–50 redacted real SMS — see [fixtures/](../../fixtures/README.md).
2. Write a throwaway parser (Dart or Python, whichever is faster to iterate) using the
   real formats already documented in [prior art](../research/02-prior-art.md).
3. Write a Saudi seed dictionary — a few hundred merchants across supermarkets, delivery,
   telcos, petrol, pharmacies, SADAD billers.
4. Run it. Hand-label the truth. Report M1, M2, M3.
5. Recommend: proceed as designed / expand the dictionary / reopen the model question.

## How to read the result

| M2 | Reading |
|---|---|
| **High** | D4 confirmed. Build the engine as designed. |
| **Middling** | Coverage problem, not an architecture problem — invest in the seed dictionary and fuzzy matching. Still no model. |
| **Low** | The design's core assumption is wrong. Reopen the on-device model for the categorize step — the pluggable slot exists precisely for this. |

A low number is a *useful* result, not a failed spike. It is the whole reason for
spending a day before spending a month.

<a id="s3"></a>
## Side spikes (cheap, unblock later phases)

- **S1 — iOS Shortcuts.** Does the Message automation trigger actually expose the message
  *body* to an App Intent on current iOS, and does its sender filter accept alphanumeric
  bank sender IDs or only phone numbers? Gates the whole iOS phase-2 story.
- **S2 — OEM background survival.** Does the `RECEIVE_SMS` receiver survive Xiaomi and
  Huawei battery management, or is re-scan-on-open doing all the work?
- **S3 — Android 15 notification redaction.** Do bank transaction alerts survive the
  OTP-redaction filter applied to untrusted notification listeners? Only matters if
  [D2](../decisions.md#d2--distribute-via-f-droid-and-github-apk-not-google-play) is
  ever revisited in favour of a Play build.

## Status

**Blocked on real message samples.** Everything else is ready to run.
