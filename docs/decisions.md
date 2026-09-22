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

**Verified on a device, 2026-09-22 (S4, partial).** Google Play Protect's enhanced fraud
protection **hard-blocks** installing a downloaded APK that declares `RECEIVE_SMS` /
`READ_SMS` — "App blocked to protect your device", no *install anyway*. Workarounds: turn
Play Protect scanning off for the install, `adb install`, or install through a store client
(F-Droid) that uses the session installer. So distribution is a **self-hosted F-Droid
repository on GitHub Pages** (`.github/workflows/fdroid-repo.yml`, built from each release), with
the official F-Droid catalogue as a later step (draft metadata in `fdroid/app.tidybox.yml`).

**S4 closed, 2026-09-22.** Installing v0.1.1 through the F-Droid client from that repo went
through on the same device with no Play Protect prompt at all. The store path is the answer;
the README says so and nothing else is needed.

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

**Status 2026-09-22:** the pack now carries the **extraction templates** too (`templates`
section of `rulepacks/merchants.json`: currencies, type rules, amount/merchant/card/date
patterns). `Extract.kt` is only the algorithm; a new bank is a pack edit. Loading a pack in
Settings re-reads stored messages the old templates rejected. A pack without `templates`
keeps the bundled ones. Download ([O5](#open-decisions)) is still unbuilt on purpose: packs
ship with releases or load from a file; the app has no network.

### D4 — Rules and user corrections; no model

**Extraction:** no model, anywhere. Bank SMS are labeled forms in every country. Regex
templates per bank, contributed as signed rule packs.

**Categorization:** strict priority — user override → exact dictionary → keyword rules →
fuzzy dictionary match → `Uncategorized`. The dictionary and keyword rules live in the
signed rule pack ([D3](#d3--signed-download-only-rule-packs)) and update every release.

**History of this decision.** On 2026-09-22, after [D6](#d6--global-not-saudi-only) made a
pre-seeded global dictionary impossible, a small sentence-embedding classifier was added as
a step before `Uncategorized`. [The spike](spike/RESULT.md) then measured it on 174 real
messages: **rules alone scored 72% correct / 0% wrong / 28% abstain on held-out merchants,
and the embedding step changed nothing** — every abstention was a merchant whose category
is not in the string, and the model's proposals for those were noise with one confident
wrong answer. The clause was removed the same day. This is the outcome
[research 03](research/03-categorization-without-an-llm.md) predicted.

**Hedge, kept:** the categorizer remains a **pluggable slot**, empty. Both model options —
embedding classifier and generative LLM (costed in
[research 04](research/04-on-device-model-option.md)) — reopen only if a corpus from more
than two people shows readable merchant names the rules miss.

### D5 — Kotlin, not Flutter; Kotlin Multiplatform for the shared engine

**Why:** SMS capture is Android-native — a `BroadcastReceiver`, not a platform channel.
On-device ML is native on both sides (LiteRT on Android, Core ML on iOS). PennyWiseAI, the
closest prior art, is already KMP with the parser in `commonMain`. Performance is *not*
the reason — regex over 200 bytes is microseconds in any language.

**Shape:** `shared/` = engine (`commonMain`, no I/O). `androidApp/` = SMS
receiver + Compose UI. `iosApp/` later, same `shared/`.

**Cost accepted:** two UIs eventually (Compose on Android, SwiftUI or Compose
Multiplatform on iOS), instead of Flutter's one. Acceptable because the iOS product is a
different ingestion story anyway ([D1](#d1--android-first-ios-is-phase-2)).

### D6 — Global, not Saudi-only

**Why:** the parsing approach is the same everywhere; the only country-specific parts are
bank templates and the merchant dictionary, and both already live in the rule pack.
Saudi banks remain the first fixtures because that is where the real messages are.

**Consequence:** no pre-seeded merchant dictionary can cover 50 countries. The rule-pack
contribution flywheel is the product, not a nice-to-have — and the spike showed that the
dictionary and keywords, not a model, are where every categorization hit comes from.

### D7 — Privacy is the product

Everything in [D4](#d4--rules-and-user-corrections-no-model)
runs on the device. No server, no account, no sync, no telemetry; the only network call
is a signed download. This is the constraint every other decision is checked against.

---

### D8 — Sender allowlist is seeded from the phone, not from a country list

**Decision:** the default allowlist only ever contains sender IDs *observed on a real
device*. Settings has a **"Scan phone for sender IDs"** button that reads the `ADDRESS`
column of the SMS inbox — never bodies, never stored — drops anything shaped like a phone
number (people), and lets the user tap the rest to allow them. Matching ignores case,
spaces and dashes but is otherwise exact: `CIB` does not admit `CIB OTP`, because with
"Keep raw messages" on that would store OTP text.

**Why:** the first device outside Saudi Arabia (Egypt: `CIB`, `KFH Egypt`, `VF-Cash`)
imported 0 messages. The seed list was written from memory, so it encoded one country.
A global product cannot ship a bank list; it has to discover the one on the phone.

**What this relaxes:** [design §2.2](specs/2026-09-16-design.md#22-identify-sender) said
nothing outside the allowlist is ever read. Sender *metadata* now is, on an explicit tap.
Bodies are still untouched until the ID is allowlisted.

**Still open (the real global gap):** the extraction templates are Arabic Saudi shapes.
An allowlisted English CIB message imports and then fails `NO_TEMPLATE`. Import status now
shows `N messages, M transactions` so the two failures are distinguishable. Templates per
bank need real bodies in `fixtures/corpus.jsonl`. First non-Saudi rows landed 2026-09-22:
12 Egyptian messages (CIB English card charge/refund, NBE Arabic خصم/إضافة) taken from
public parser test suites, plus a generic English rule set (charged/debited/refund/received/
sent/ATM/recharge, `at <merchant> on`, `CUR 0.00` or `0.00 CUR`). Still unseen: VF-Cash and
KFH Egypt wording. Settings has **"Copy 20 unreadable messages"** (digits masked) so a user
can hand over real samples without screenshots.

## Open decisions

| # | Question | Default if unanswered |
|---|---|---|
| O1 | **Project name.** | **Decided 2026-09-22: Tidy Box** (`app.tidybox`). English, about the inbox rather than the money, so it still fits when non-payment channels arrive. `Raseed` was the working title through v0.1.0. |
| O2 | **License.** GPLv3 / AGPLv3 / MIT / Apache-2.0. Affects whether a closed fork is possible. | **Decided 2026-09-22: GPL-3.0-or-later**, at F-Droid submission time. |
| O3 | **Repo visibility.** Currently **private**. | **Decided 2026-09-22: public**, required for F-Droid. |
| O5 | **Rule-pack download.** D3 is half built: packs are JSON, bundled, and loadable from a file — but the app has no `INTERNET` permission and CI enforces that. Adding the GitHub Releases download means adding `INTERNET` and the Ed25519 check. Alternative: keep zero network forever and ship packs only with app releases + manual file import. | Ship packs with releases; decide on download when there is a pack worth downloading. |
| O4 | **Discard raw SMS after parsing?** Keeping the raw text lets a rule-pack update re-derive old transactions and lets the UI explain *why* something was categorized. Deleting it is stronger privacy. | Keep, encrypted, with a user-facing setting to discard. **Built:** Settings → Privacy → *Keep raw messages* (default on); off erases stored text immediately and after every parse. |

---

## The assumption still unproven

**D4 rests on a coverage number nobody has measured on real messages.** The one
figure circulating in prior art — Mizan's "~79%" — turns out to be
[too ambiguously worded to inherit](research/02-prior-art.md#mizan-kioo20082008-specmizan),
and the ~95% casually claimed for rule-based parsing is unsourced. There is simply no
trustworthy baseline, in either direction.

This is why [the spike](spike/README.md) exists. **It has now run** — see
[the result](spike/RESULT.md): extraction 104/104, rules 72% correct / 0% wrong on held-out
merchants, model adds nothing. D4 stands. The number that would reopen it is a *wrong*
rate above 2% or a readable-merchant miss rate that a bigger dictionary cannot close.
