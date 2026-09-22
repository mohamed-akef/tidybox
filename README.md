# Tidy Box

A privacy-first inbox for the messages your bank sends you.

Tidy Box reads bank SMS on your phone, turns them into transactions, and categorizes your
spending — **entirely on the device**. No account. No server. No sync. No telemetry.
Your financial history never leaves your phone, because there is nowhere for it to go.

---

## Status: Android v0.1 built — not yet run on a device

The parsing engine (`shared/`) with the 174-message corpus as its test suite, and an Android
app that captures allowlisted bank SMS, categorizes them, groups them by month, takes
corrections, and exports an encrypted backup. English and Arabic. What is *not* built is
listed per-section in [the design](docs/specs/2026-09-16-design.md) — the short list is
extraction templates as data, rule-pack download, and iOS.
The design's central assumption — rules and user corrections, no model — has been
[measured on 174 real messages](docs/spike/RESULT.md): extraction 104/104, categorization
72% correct / **0% wrong** / 28% abstain on held-out merchants, and an embedding model
added nothing. Building starts from there.

## Install

**F-Droid (recommended).** In the F-Droid client: Settings → Repositories → **+** → paste
`https://mohamed-akef.github.io/tidybox/repo` → then search *Tidy Box*. Installing through a store
client is what Google Play Protect allows; downloading the APK directly is blocked on Android 15+
("App blocked to protect your device") for any app that reads SMS.

**APK.** [GitHub Releases](https://github.com/mohamed-akef/tidybox/releases) — signed; needs Play
Protect scanning turned off for the install, or `adb install`.

## Why this exists

Saudi apps like **Wafeer** and **مصروفي (Masrofi)** already parse bank SMS into
categorized spending, and they work well. They do it on their servers — which means
your salary, your rent, your card numbers and every merchant you have ever paid pass
through someone else's infrastructure.

The parsing does not need a server. Everything these apps do can run on the phone.
Tidy Box is the same idea with the server deleted, and the source open so the claim is
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
keyword rules, and **your own corrections** (local, permanent). There is no model, because
for this problem a model mostly converts *"I don't know"* into a confident wrong answer —
and in an app about your money, a blank category is better than a wrong one. We measured
that rather than asserted it: [the spike](docs/spike/RESULT.md). The full argument is in
[docs/research/03](docs/research/03-categorization-without-an-llm.md).

## Platform reality

| | Automatic capture | Why |
|---|---|---|
| **Android** | Yes — `RECEIVE_SMS` / `READ_SMS` | Distributed via F-Droid / GitHub APK, where the permission is unrestricted. Google Play restricts it (obtainable, but discretionary and revocable). |
| **iOS** | Not in v1 | iOS has **no SMS-read API at all**. Every iOS "SMS tracker" uses a side door — Shortcuts automation, open banking, Apple Wallet or statement import. Which one we take is decided when iOS starts. |

Details and sources: [docs/research/01](docs/research/01-platform-constraints.md).

## Layout

```
shared/        Kotlin Multiplatform — normalize, extract, categorize. No I/O.
androidApp/    SMS receiver, WorkManager parse, SQLDelight store, Compose inbox
iosApp/        later — same shared/, different ingestion
rulepacks/     later — bank templates and merchant dictionary as signed JSON (Kotlin constants today)
fixtures/      corpus.jsonl — 174 labeled real messages, asserted row-by-row in CI
spike/         the throwaway measurement (docs/spike/RESULT.md)

Build: JDK 17, `./gradlew :shared:jvmTest` for the engine, `./gradlew :androidApp:assembleDebug`
for the APK (needs an Android SDK; `local.properties` → `sdk.dir`).
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

[GPL-3.0-or-later](LICENSE). Chosen 2026-09-22 for the F-Droid submission (O2).

## Credits

Interface font: [Geist](https://github.com/vercel/geist-font) by Vercel, SIL Open Font License 1.1 (`androidApp/src/main/assets/geist-OFL.txt`). Bundled in the APK; nothing is downloaded.
