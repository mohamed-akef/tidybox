# Decisions

## Decided

These were settled explicitly on 2026-09-16, each after the alternatives were laid out.

### D1 — Android first; iOS is phase 2

**Why:** iOS has no API for reading SMS, and this is not a restriction that is loosening —
it is core to Apple's security model. The one automatic path is a user-built Shortcuts
personal automation feeding an App Intent. That is a genuinely different ingestion story
and an onboarding burden, so it does not belong in v1.

**Consequence:** the engine is designed platform-free so iOS later is a new *adapter*,
not a rewrite.

### D2 — Distribute via F-Droid and GitHub APK, not Google Play

**Why:** `READ_SMS` / `RECEIVE_SMS` are Play-**restricted** permissions. An app must be
the device's default SMS handler or win an exception via the Restricted Permission
Declaration Form. Outside Play the permission is unrestricted, and F-Droid is the natural
home for an auditable privacy tool.

> **Correction, verified 2026-09-16.** An earlier draft of this document claimed Google
> *denies* this use case. **That was wrong.** PennyWiseAI ships on Google Play today with
> read-only `READ_SMS` for exactly this purpose, which proves the declaration-form
> exception is obtainable.
>
> D2 therefore stands on a different and weaker footing than first written: it is a
> **risk-and-friction judgement, not an impossibility**. Play access here is
> discretionary, re-reviewable and revocable — and losing it after launch would strand
> users mid-product. F-Droid-first removes that dependency, and a Play build can be added
> later **without changing a line of code**, since it is a distribution choice rather than
> an architectural one.

**Cost accepted:** smaller reach; users must sideload or add the F-Droid repo.

**Rejected alternative:** a Play build using `NotificationListenerService` to read bank
*notifications* instead of SMS. Still restricted, still needs justification, and
Android 15+ redacts notifications it classifies as OTP-bearing from untrusted listeners —
which may silently swallow bank alerts. See [spike S3](spike/README.md#s3).

### D3 — Signed, download-only rule packs

**Why:** bank templates and the merchant dictionary need to change faster than app
releases. A rule pack is versioned JSON, **Ed25519-signed with the public key pinned in
the app**, fetched from GitHub Releases and signature-verified before install.

Signing is not optional. A rule pack is quasi-executable content; fetching one unsigned
over the network would be a real attack surface in an app whose entire pitch is trust.

**Boundary:** download only. No request body, no identifier, no telemetry, no upload of
any kind — and the whole mechanism can be disabled, falling back to the bundled pack.

### D4 — Rules and user corrections; no LLM

**Why:** extraction needs no intelligence (bank SMS are labeled forms), and for
categorization a small model mostly turns *"unknown"* into a confident wrong answer.
Full argument and evidence: [research 03](research/03-categorization-without-an-llm.md).

**Hedge, deliberately kept:** the categorizer is a **pluggable slot**. If measurement
shows the unknown-merchant tail is larger than expected, a small on-device model can
fill that one step later without touching the rest of the engine. The door is left
open on purpose; we just aren't building on it. What that model would concretely be —
runtime, size, cost, iOS status — is worked out in
[research 04](research/04-on-device-model-option.md), so the option is real rather than
rhetorical.

---

## Open decisions

| # | Question | Default if unanswered |
|---|---|---|
| O1 | **Project name.** `Raseed` رصيد (balance), `Kashf` كشف (statement), `Masareef` مصاريف (expenses), `Wasl` وصل (receipt), or something else. | `raseed` — a placeholder. GitHub renames preserve redirects, so this is cheap to change. |
| O2 | **License.** GPLv3 / AGPLv3 / MIT / Apache-2.0. Affects whether a closed fork is possible. | None chosen. Deliberately left to the owner — this is a legal choice, not a technical one. |
| O3 | **Repo visibility.** Currently **private**. | Stays private until the design is settled. Private→public is one click; the reverse is not. |
| O4 | **Discard raw SMS after parsing?** Keeping the raw text lets a rule-pack update re-derive old transactions and lets the UI explain *why* something was categorized. Deleting it is stronger privacy. | Keep, encrypted, with a user-facing setting to discard. |

---

## The assumption still unproven

**D4 rests on a coverage number nobody has measured on real Saudi messages.** The one
figure circulating in prior art — Mizan's "~79%" — turns out to be
[too ambiguously worded to inherit](research/02-prior-art.md#mizan-kioo20082008-specmizan),
and the ~95% casually claimed for rule-based parsing is unsourced. There is simply no
trustworthy baseline, in either direction.

This is why [the spike](spike/README.md) exists and why the design below is marked
draft. If measured auto-categorization comes back weak, D4 is the decision that changes.
