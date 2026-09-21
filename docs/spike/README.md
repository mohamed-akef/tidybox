# The spike: measure before building

**Purpose:** answer one question with a number instead of an opinion —

> *Does rule-based extraction and categorization work well enough on **your** bank
> messages to build a product on?*

**Output:** a recommendation, not code. Everything built here is throwaway and labeled
as such. **Cost:** about a day.

## Why this exists

The design rests on [D4](../decisions.md#d4--rules-for-extraction-rules--user-corrections--a-small-embedding-model-for-categorization-no-llm) — rules
and user corrections, no model. The argument for it is
[strong and evidence-backed](../research/03-categorization-without-an-llm.md), but its
coverage is **unmeasured on real messages**, and the one figure circulating in prior
art — Mizan's "~79%" — is
[too ambiguously worded to inherit](../research/02-prior-art.md#mizan-kioo20082008-specmizan).

So there is no trustworthy baseline to borrow. We produce our own, on real messages,
before committing to the build — because the gap between "works" and "nearly works" is the
gap between an app you trust and one you abandon.

## The three numbers

| Metric | Definition | Rough bar |
|---|---|---|
| **M1 — Extraction rate** | % of transaction messages where amount, date, card and merchant are all extracted correctly | Should be very high (>95%). The messages are labeled forms; if this is low, something is wrong with the approach, not the data. |
| **M2 — Auto-categorization** | Of extracted transactions, with **no** user input, three buckets that sum to 100%: **correct** / **wrong** / **abstained** (`Uncategorized`). Reported twice: dictionary alone, then dictionary + embedding step. | **Wrong is the bar, not correct.** 80 correct / 20 abstained is a good result; 80 correct / 20 wrong is a bad one, and a single "correct %" cannot tell them apart. Acceptance: wrong ≤ 2%, and correct high enough that taps are tolerable (M3). Low correct even with embeddings → the generative-LLM question reopens. |
| **M3 — Residual tap rate** | % needing one user tap, and how fast that decays. Simulated **chronologically**: replay the corpus in received order, apply each correction as a permanent rule, count taps per week. | Determines whether "it teaches itself in two weeks" is true or wishful. |

Two correctness checks run alongside, because they are the quiet killers:

- **No declined transaction becomes a transaction.**
- **Every refund carries the correct sign.**

## Method

1. Collect 30–50 redacted real SMS — see [fixtures/](../../fixtures/README.md).
2. Write a throwaway parser in Python using the real formats already documented in
   [prior art](../research/02-prior-art.md).
3. Write a seed dictionary for the banks in the corpus (Saudi first — a few hundred
   merchants across supermarkets, delivery, telcos, petrol, pharmacies, SADAD billers)
   **and** the embedding fallback: a multilingual MiniLM-class model, category vectors,
   one similarity threshold. Report M2 both with and without the embedding step, so the
   model's contribution is a number rather than an assumption.
4. **Hold out before tuning.** Hand-label the truth for every message first. Then split
   by *merchant* (not by message): ~80% of merchants to tune the dictionary and pick the
   embedding threshold, ~20% never seen during tuning. **Freeze the threshold** before
   scoring. Report M1, M2, M3 on the held-out set only; the tuning-set numbers are not
   the result. With 30–50 messages this is coarse, and that is stated with the number.
5. Run it. Report M1, M2 (three buckets, twice), M3 (chronological).
6. Recommend: proceed as designed / expand the dictionary / reopen the generative-LLM
   question (research 04).

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
- **S4 — Sideload permission gate on Android 15/16.** Install via F-Droid and via a raw
  APK on a device running Android 15 and 16. Confirm whether `RECEIVE_SMS` / `READ_SMS`
  can be granted directly, or only after App info → ⋮ → *Allow restricted settings*.
  Whatever the answer, it becomes the onboarding screen — see
  [D2](../decisions.md#d2--distribute-via-f-droid-and-github-apk-not-google-play).

## Status

**Blocked on real message samples.** Everything else is ready to run.
