# Decisions

## Decided

D1–D4 were settled on 2026-09-16; D5–D7 on 2026-09-22, after review of the first draft.

### D1 — Android first; iOS is phase 2

**Why:** iOS has no API for reading SMS, and this is not a restriction that is loosening —
it is core to Apple's security model. The one automatic path is a user-built Shortcuts
personal automation feeding an App Intent. That is a genuinely different ingestion story
and an onboarding burden, so it does not belong in v1.

**Consequence:** the engine is designed platform-free so iOS later is a new *adapter*,
not a rewrite.

**iOS ingestion path — deferred (2026-09-22).** Every iOS "SMS tracker" uses one of four
side doors, none of which reads the inbox: (1) a user-installed Shortcuts automation
feeding an App Intent, (2) open-banking aggregation, (3) Apple Wallet (Apple Pay only),
(4) statement/CSV import. Which door we take is decided when iOS starts, not now. The
likely one is (1): ship a `.shortcut` the user installs in one tap, app exposes one
`ingest(text)` intent, same engine.

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

**Correction, 2026-09-22 — "unrestricted outside Play" was too strong.** The installer
whitelists restricted permissions by default, so F-Droid and raw-APK installs *can* hold
`READ_SMS` / `RECEIVE_SMS`. But from **Android 15** the platform treats them as
hard-restricted for apps not installed by Play: the user must first open
App info → ⋮ → *Allow restricted settings*, and only then can the grant dialog appear.
That is onboarding friction, not a blocker — and it must be verified on real devices
([spike S4](spike/README.md#s3)) and written into the first-run screen, not discovered
by users.

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

### D4 — Rules for extraction; rules + user corrections + a small embedding model for categorization; no LLM

**Extraction:** no model, anywhere. Bank SMS are labeled forms in every country. Regex
templates per bank, contributed as signed rule packs.

**Categorization** (revised 2026-09-22, after [D6](#d6--global-not-saudi-only) made a
pre-seeded merchant dictionary impossible): strict priority —
user override → exact dictionary → keyword rules → **embedding nearest-neighbour** →
`Uncategorized`. The model is a small multilingual **sentence-embedding** model
(MiniLM class, tens of MB), not a generative LLM. It embeds the merchant string and
compares it to category vectors; below a similarity threshold it answers *Uncategorized*
rather than guessing. That keeps the two properties the original D4 was protecting:
deterministic, and "I don't know" instead of a confident wrong answer.

**What updates, and where:** the embedding model ships inside the app binary and changes
rarely. The dictionary, keyword rules and category vectors live in the signed rule pack
([D3](#d3--signed-download-only-rule-packs)) and update every release.

**Not chosen:** a generative on-device LLM (Gemma-class). Costed in
[research 04](research/04-on-device-model-option.md). It reopens only if the spike shows
the embedding classifier fails on real messages.

### D5 — Kotlin, not Flutter; Kotlin Multiplatform for the shared engine

**Why:** SMS capture is Android-native — a `BroadcastReceiver`, not a platform channel.
On-device ML is native on both sides (LiteRT on Android, Core ML on iOS). PennyWiseAI, the
closest prior art, is already KMP with the parser in `commonMain`. Performance is *not*
the reason — regex over 200 bytes is microseconds in any language.

**Shape:** `shared/` = engine + classifier (`commonMain`, no I/O). `androidApp/` = SMS
receiver + Compose UI. `iosApp/` later, same `shared/`.

**Cost accepted:** two UIs eventually (Compose on Android, SwiftUI or Compose
Multiplatform on iOS), instead of Flutter's one. Acceptable because the iOS product is a
different ingestion story anyway ([D1](#d1--android-first-ios-is-phase-2)).

### D6 — Global, not Saudi-only

**Why:** the parsing approach is the same everywhere; the only country-specific parts are
bank templates and the merchant dictionary, and both already live in the rule pack.
Saudi banks remain the first fixtures because that is where the real messages are.

**Consequence:** no pre-seeded merchant dictionary can cover 50 countries, which is what
forced the embedding step into [D4](#d4--rules-for-extraction-rules--user-corrections--a-small-embedding-model-for-categorization-no-llm).
The rule-pack contribution flywheel is the product, not a nice-to-have.

### D7 — Privacy is the product

Everything in [D4](#d4--rules-for-extraction-rules--user-corrections--a-small-embedding-model-for-categorization-no-llm)
runs on the device. No server, no account, no sync, no telemetry; the only network call
is a signed download. This is the constraint every other decision is checked against.

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

**D4 rests on a coverage number nobody has measured on real messages.** The one
figure circulating in prior art — Mizan's "~79%" — turns out to be
[too ambiguously worded to inherit](research/02-prior-art.md#mizan-kioo20082008-specmizan),
and the ~95% casually claimed for rule-based parsing is unsourced. There is simply no
trustworthy baseline, in either direction.

This is why [the spike](spike/README.md) exists and why the design below is marked
draft. If measured auto-categorization comes back weak even with the embedding step, D4
is the decision that changes — the generative-LLM option in research 04 reopens.
