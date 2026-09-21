# Research: prior art

Researched 2026-09-16.

## The Saudi apps we are answering

**Wafeer** — Riyadh fintech, founded 2019. Its app tracks expenses automatically by
connecting to banks or **reading bank SMS**, then applies AI for spending advice. The
reading is real and it works; it is just not local.

**مصروفي (Masrofi)** — Saudi-built expense tracker with SAR support, budget tracking,
spending forecasts, and **import of expenses from bank SMS**.

Both do server-side parsing. **Nothing about the parsing requires a server** — which is
the entire premise of this project. The competitive claim is not "we parse better," it
is "we parse in the same place your messages already are."

## Open-source parsers worth reading

### Bank Al Bilad SMS parser (`obahareth/bank-al-bilad-sms-parser`)

The most valuable artifact found — it publishes **verbatim real message shapes**:

```
شراء عبر نقاط البيع
بطاقة: **1234;الإئتمانية
لدى: Some merchant
دولة: السعودية
مبلغ: 12.00 SAR
رصيد: 1234.56 SAR
في: 2019-05-07 23:44
```

```
مشتريات نقاط البيع
بطاقة: **4567;مدى
من: xx005
مبلغ: 34.00 SAR
لدى: Some restaurant
دولة: السعودية
في: 2019/05/07 01:29
```

**The bank labels every field.** `مبلغ:` then the amount, `لدى:` then the merchant,
`بطاقة:` then the card, `في:` then the date. This is a form, not prose — which is the
whole reason extraction needs no model. Note also the two formats differ in field
*order* and date format, which is why templates are per-bank-per-variant and ordered.

### PennyWiseAI (`sarim2000/pennywiseai-tracker`)

A shipping open-source Android SMS expense tracker, Kotlin, with **active Saudi
parser work** (PR #708 covering SNB, STC/STCPay and D360).

Real SNB Arabic POS format:

```
شراء نقاط بيع SamsungPay
بـSAR 19.45
من SYNTHETIC MERCHANT
مدى *0002
في 07:53 03/04/26
```

Its extraction patterns are plain regex:

```kotlin
"بـ\\s*SAR\\s*([0-9,]+(?:\\.\\d{1,2})?)"                       // amount
"مبلغ\\s*:?\\s*SAR\\s*([0-9,]+(?:\\.\\d{1,2})?)"                // labeled amount
"Amount\\s*:\\s*[A-Z]{3}\\s*[0-9,]+...\\(\\s*SAR\\s*([0-9,]+...)\\)"  // FX settlement
"(?:^|\\n)من\\s*([^\\n]+)"                                      // merchant
```

It also handles debit verbs in both languages —
`purchase, payment, transfer, withdrawal, charge, debit, pos` /
`شراء، حوالة، سداد، خصم، سحب، إيداع، مشتريات`.

Its **categorizer** is examined separately in
[research 03](03-categorization-without-an-llm.md); the short version is that it
contains no machine learning whatsoever.

**Its distribution is the most useful fact in this file.** PennyWiseAI ships on
**Google Play, the Apple App Store, F-Droid and GitHub Releases**, and its README states:
*"Grant SMS permission (read-only) — no account creation, no inbox changes, no messages
sent."*

Two consequences, both of which corrected earlier drafts of this repo:

1. **`READ_SMS` on Google Play is achievable** for transaction parsing. It forced the
   rewrite of [D2](../decisions.md#d2--distribute-via-f-droid-and-github-apk-not-google-play).
2. **Its iOS app cannot be doing the same thing** — iOS has no SMS-read API. The codebase
   contains an `ImportStatementUseCase`, i.e. the iOS story is statement import, not
   message capture. That independently corroborates
   [D1](../decisions.md#d1--android-first-ios-is-phase-2): even a team that solved Play
   distribution could not solve iOS SMS, because it is not solvable.

### Mizan (`kioo20082008-spec/mizan`)

A Saudi SMS parser project that did the thing nobody else did: **published a number**.
After a parser overhaul using regex alternation over merchant anchors (`لدى` / `عند` / …),
its PR reports that *"coverage of genuine transactions went from a small fraction to ~79%,
with the remainder correctly rejected as non-transactional."*

**That sentence is ambiguous, and we should not build on it.** It reads two ways:

- **(a)** 79% of genuine transactions parsed — 21% missed; or
- **(b)** 79% of a mixed corpus *were* transactions and all parsed, while the other 21%
  were non-transactional messages correctly rejected — i.e. near-total accuracy.

Reading **(b)** is the more logical one, since a *genuine* transaction cannot be
"correctly rejected as non-transactional". But the wording does not settle it, and the
corpus size is never stated. An earlier draft of this file treated it as (a) and built a
pessimistic "1-in-5 messages land wrong" narrative on top — that was over-reading someone
else's PR description.

The durable conclusion is **not a number**: no one has published a trustworthy,
reproducible coverage measurement for Saudi bank SMS parsing on a real corpus. *That
absence* is what justifies [the spike](../spike/README.md).

### Others

`saurabhgupta050890/transaction-sms-parser`, `Pavel401/transaction_sms_parser`,
`gotenksIN/bank-sms-parser` — India-focused, same architecture: regex over labeled
fields. Useful as structural references, not for Saudi coverage.

## What prior art establishes

1. Extraction from Saudi bank SMS by template matching is **proven, shipping, and
   boring**. The formats are labeled key-value forms.
2. Categorization by keyword dictionary is **what real products actually do**.
3. Nobody has published strong Saudi merchant coverage — the gap this project fills.
4. **No trustworthy published coverage measurement exists** for Saudi SMS parsing — so
   there is no baseline to inherit, and we must produce our own.

## Sources

- [bank-al-bilad-sms-parser](https://github.com/obahareth/bank-al-bilad-sms-parser)
- [PennyWiseAI PR #708 — Saudi parser reliability](https://github.com/sarim2000/pennywiseai-tracker/pull/708)
- [PennyWiseAI SharedCategoryMapping.kt](https://github.com/sarim2000/pennywiseai-tracker/blob/main/shared/src/commonMain/kotlin/com/pennywiseai/shared/domain/mapping/SharedCategoryMapping.kt)
- [Mizan PR #2 — parser overhaul, 79% coverage](https://github.com/kioo20082008-spec/mizan/pull/2)
- [Wafeer privacy policy — SMS reading](https://wafeer.net/PrivacyPolicy)
- [Wafeer on Arab News](https://www.arabnews.com/node/1976481/business-economy)
- [مصروفي Masrofi on the App Store](https://apps.apple.com/sa/app/%D9%85%D8%B5%D8%B1%D9%88%D9%81%D9%8A-masrofi/id1467616866)
