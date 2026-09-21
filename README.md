# Raseed — رصيد

> **Working title.** The name is a placeholder — see [open decisions](docs/decisions.md#open-decisions).

A privacy-first inbox for the messages your bank sends you.

Raseed reads bank SMS on your phone, turns them into transactions, and categorizes your
spending — **entirely on the device**. No account. No server. No sync. No telemetry.
Your financial history never leaves your phone, because there is nowhere for it to go.

---

## Status: design phase — nothing is implemented yet

This repository currently holds research and a design under review. No code exists.
The design is **not final**: its central assumption (that rules plus a small embedding
classifier cover enough merchants without a generative model) is deliberately unproven
and scheduled for measurement. See [the spike](docs/spike/README.md).

## Why this exists

Saudi apps like **Wafeer** and **مصروفي (Masrofi)** already parse bank SMS into
categorized spending, and they work well. They do it on their servers — which means
your salary, your rent, your card numbers and every merchant you have ever paid pass
through someone else's infrastructure.

The parsing does not need a server. Everything these apps do can run on the phone.
Raseed is the same idea with the server deleted, and the source open so the claim is
checkable rather than promised.

## The privacy guarantee

1. **Only senders you allowlist are ever read.** Messages from anyone else are not
   parsed, not stored, not hashed. The receiver drops them.
2. **The parsing engine has no I/O.** It is a pure Kotlin module (`shared/commonMain`)
   with no network, no filesystem, and no database dependency — a pure function of
   `(text, sender, rulepack) → result`. It is structurally incapable of phoning home,
   and you can verify that by reading one `build.gradle.kts`.
3. **The only network call in the app is a download.** Rule packs are fetched from
   GitHub Releases, signature-verified, and installed. No request body, no identifier,
   no telemetry — and the whole feature can be switched off.
4. **The local database is encrypted** (SQLCipher, key in the Android Keystore).

## How it works, in one paragraph

Bank SMS are labeled forms, not prose — a Saudi bank writes `مبلغ:` then the amount,
`لدى:` then the merchant, `بطاقة:` then the card; banks elsewhere do the same in their
own words. Extraction is therefore template matching, not comprehension, and templates
are per-bank rule packs anyone can contribute. Categorization is a merchant dictionary,
**your own corrections** (local, permanent), and — for merchants nobody has seen — a
small on-device **embedding model** that says *Uncategorized* when it is not sure. There
is no generative LLM, because for this problem it mostly converts *"I don't know"* into a
confident wrong answer — and in an app about your money, a blank category is better than
a wrong one. The full argument is in
[docs/research/03](docs/research/03-categorization-without-an-llm.md); the model choice
in [docs/decisions.md](docs/decisions.md#d4--rules-for-extraction-rules--user-corrections--a-small-embedding-model-for-categorization-no-llm).

## Platform reality

| | Automatic capture | Why |
|---|---|---|
| **Android** | Yes — `RECEIVE_SMS` / `READ_SMS` | Distributed via F-Droid / GitHub APK, where the permission is unrestricted. Google Play restricts it (obtainable, but discretionary and revocable). |
| **iOS** | Not in v1 | iOS has **no SMS-read API at all**. Every iOS "SMS tracker" uses a side door — Shortcuts automation, open banking, Apple Wallet or statement import. Which one we take is decided when iOS starts. |

Details and sources: [docs/research/01](docs/research/01-platform-constraints.md).

## Planned layout

```
shared/        Kotlin Multiplatform — parse + categorize + embedding classifier. No I/O.
androidApp/    SMS receiver, SQLCipher store, Compose UI
iosApp/        later — same shared/, different ingestion
rulepacks/     bank templates, merchant dictionary, category vectors, signing
fixtures/      redacted real SMS — the test corpus
```

## Documents

- [Decisions made and still open](docs/decisions.md)
- [Design](docs/specs/2026-09-16-design.md) — **draft**
- [Research: platform constraints](docs/research/01-platform-constraints.md)
- [Research: prior art](docs/research/02-prior-art.md)
- [Research: categorization without an LLM](docs/research/03-categorization-without-an-llm.md)
- [Research: the on-device model option, if we ever need it](docs/research/04-on-device-model-option.md)
- [The spike: measure before building](docs/spike/README.md)
- [How to contribute redacted SMS fixtures](fixtures/README.md)

## License

Not yet chosen — see [open decisions](docs/decisions.md#open-decisions). A copyleft
license (GPLv3 / AGPLv3) is the usual fit for a privacy tool distributed through
F-Droid, but this is the owner's call and deliberately left open.
