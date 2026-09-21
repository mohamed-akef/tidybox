# Spike result — 2026-09-22

**Question:** does rule-based extraction + categorization work on real bank SMS, and does a
small embedding model add anything?

**Answer:** extraction yes, completely. Categorization: rules never guess wrong; what they
miss is information that is not in the string, and the embedding model cannot see it either.
**The embedding step adds zero on this corpus and would have added one wrong answer.**

## Corpus

174 unique messages, 104 transactions, 15 must-reject, collected from public GitHub
sources (`spike/corpus.json`, `source` field on each row): `Muaath5/bank_sms_parser`
(one person's real AlRajhi + Alinma dump, 2023–2026), `KhalidAlsantali/Arabic-SMS-Spam-Data`
(real bank SMS + phishing), PennyWiseAI / SMS-LEDGER / Expense-Tracker / bank-al-bilad
parser test fixtures (SNB, SABB, Alinma, Al Bilad, some synthetic). Mostly AlRajhi and
Alinma. Includes refunds, a declined transfer, an insufficient-funds decline, ATM in and
out, salary, SADAD bills, government fees, FX purchases with settlement, auth holds, OTPs
with amounts, and phishing.

Not your messages. Two people's, mostly. Treat the numbers as a first reading.

## M1 — extraction: 104/104, rejects 15/15

Type, amount, currency, merchant and (for purchases) date all correct on every
transaction. No OTP, auth hold, declined or insufficient-funds message became a
transaction. It took three passes of regex fixes to get there — the first pass was 72% —
and every fix was a template bug, not a limitation of the approach.

Things the corpus taught the parser that the design had only guessed:
- **Presentation-form Arabic.** Some AlRajhi messages arrive as `ﺣواﻟة داﺧﻟﻳة`
  (U+FBxx contextual glyphs), not `حوالة داخلية`. `NFKC` fixes it. Without it, every
  template misses silently.
- **Tatweel is a template marker, not noise.** AlRajhi's short form is `بـSAR 98` /
  `لـPETROLY C`. Stripping tatweel (the design said to) breaks the amount regex. Keep it in
  the text, strip it only when normalizing merchant names.
- **`من:` means sender, account, or merchant depending on the bank and the line.**
  Alinma writes `من: ALBAIK` for a purchase; AlRajhi writes `من:7079` for the paying
  account. A merchant pattern must be pattern-priority, not line-order, and must reject
  account-shaped values.
- **Greeting lines.** Alinma prefixes `هلا محمد` — the type keyword is on line 2.
- **FX purchases carry two amounts.** `مبلغ: 23 USD (86.37 ريال)` plus
  `إجمالي المبلغ المستحق: 88.36 SAR`. Store the original as the amount and the settlement
  separately; the design's "never convert" holds.
- **Bidi marks are real.** U+061C before every AlRajhi 2026-format date, as predicted.
- **Auth holds** (`تفويض عبر الانترنت`) precede the real debit
  (`خصم من التفويض`) with the same amount and merchant — a dedup case, and the hold
  must not be a transaction.

## M2 — categorization, held-out merchants (n=18)

| | correct | **wrong** | abstained |
|---|---|---|---|
| rules only | 72% | **0%** | 28% |
| rules + embedding (threshold 0.56, frozen on tune set) | 72% | **0%** | 28% |

All 50 purchases for reference: 58% / 0% / 42%. Tune set: 50% / 0% / 50%.

**Every abstention is a merchant whose category is not in the string** — `THATI LIMITED`,
`Bss-ballot`, `HAWSABAH COMPAN`, `ABDULAH MOHD`, `BRANCH OF MODER`, `The World`,
`Meed Express`, `Establishment Name`. Of the 50 purchase rows, 21 are labeled `?` for this
reason. Not one merchant with a readable name was missed by the dictionary + keyword rules:
`Mtaam Ns Sfry` → Food, `MAHTA MOSLM ALSAIRE` → Fuel, `NEBRAS ALJANOBEYA LAUN` → Services,
`E206 Tamimi` → Groceries all hit on keywords or fuzzy dictionary match.

**What the embedding model did with those 21:** proposed Food, Telecom, Health, Travel,
Services, Transport, Finance, Groceries — at similarities of 0.13–0.36, i.e. noise. Its one
confident answer was `FAIRWAY` → Transport at 0.55, which is wrong. The threshold search
therefore found no setting that raised *correct* without raising *wrong*, and froze at a
level where the model never fires.

This is the exact failure mode [research 03](../research/03-categorization-without-an-llm.md)
predicted: where the information is in the string, a dictionary entry already wins; where
it is not, the model invents.

## M3 — taps, chronological replay

20 taps over 50 purchases, one per distinct unknown merchant. After a merchant is corrected
once it never asks again. The 2025-08 to 2026-02 stretch (the most recent, densest months)
ran 3/17 taps. With real per-user volume this is the "asks once, then knows" behaviour the
design wanted.

## Recommendation

1. **Build the engine as designed, without the embedding step.** Rules + user corrections.
   D4's model clause is not supported by data; revert it to the 2026-09-16 wording and keep
   the pluggable slot empty. This is a cheaper design, not a compromise.
2. **Spend the effort on the dictionary and keywords instead.** That is where every hit came
   from, and the misses are not fixable by any classifier.
3. **Re-run this spike when a corpus from more than two people exists.** If a third person's
   messages show readable merchant names the rules miss, the model question can reopen with
   evidence. Until then it is closed.

## Caveats, stated

- Held-out n=18. One row is 5.5 points.
- The `?` labels are one reviewer's judgement of "not in the string". Someone who knows
  that `THATI` is a restaurant chain would score it differently — which is precisely what the
  dictionary is for.
- The seed dictionary was written before the corpus merchants were read, but by someone
  who had already seen the messages' *shapes*. Not a blind test.
- Dates in `d/m/yy` vs `yy-m-d` are ambiguous for some days; the parser guesses per bank
  format. Two synthetic fixtures parsed to 2011 and 2029 — harmless here, a real bug later.

## Reproduce

```
cd spike && uv venv -p 3.11 .venv && VIRTUAL_ENV=.venv uv pip install sentence-transformers
.venv/bin/python spike.py
```
