# Fixtures — the test corpus

This directory holds **redacted, real** bank SMS. It is the most valuable asset in the
repository: every parser template is guesswork until it runs against real messages.

## We need 30–50 messages to start

Across whichever banks you actually use (any country; Saudi first because that is where the samples are), in **both Arabic and English** — the same
bank sends either depending on the customer's language setting, and the formats differ.

### Please make sure the set includes

These are where parsers quietly break, so they matter more than another twenty ordinary
purchases:

- [ ] A **refund / reversal** — must produce the correct sign
- [ ] A **declined / failed** transaction — must produce *no* transaction at all
- [ ] An **ATM withdrawal**
- [ ] A **transfer** in and out
- [ ] A **salary deposit**
- [ ] A **SADAD bill payment**
- [ ] A **foreign-currency purchase** (these carry two amounts — original and SAR settlement)
- [ ] An **online / e-commerce** purchase, which often formats the merchant differently
- [ ] A **non-transaction** bank message (a promo, an OTP, a balance notice) — needed to
      prove the parser correctly *rejects* things

## How to redact

**Do:**
- Replace card and account digits with **other digits**, same length (`**1234` → `**7381`).
  Not `####` — templates match digits, and a placeholder that is not a digit makes the
  message fail extraction for the wrong reason.
- Replace personal names with a placeholder like `SYNTHETIC MERCHANT` or `اسم تجريبي`
- Replace IBANs and phone numbers with same-shape synthetic values

**Keep the format, change the values if you want:**
- Amounts: scramble the digits, keep the format (`1,234.56` stays `d,ddd.dd`)
- Dates and times: shift them, keep the exact original format
- The exact Arabic wording, spacing and line breaks — never retype these

**Merchant names are the point of the exercise — and they are your spending history.**
A merchant plus a date under your GitHub name says where you were and what you bought.
Card masking does not change that. So:
- keep the merchant if you are comfortable with that being public;
- otherwise **swap it for a different real chain of the same kind**, in the same
  format the bank printed it (`PANDA 1234 RIYADH` → `DANUBE 5510 RIYADH`). The parser
  and the categorizer need the *shape* and the *kind*, not your actual shop;
- or send the messages privately to a maintainer, who redacts and commits them.

Review your set once more for merchants before opening the PR. It is a public record.

> ⚠️ **Copy the text exactly, don't retype it.** Bank SMS contain invisible bidirectional
> control marks (U+200E / U+200F / U+061C) sitting inside amounts and account numbers.
> They are a real source of parser bugs, and retyping silently removes them — which makes
> the fixture *easier* than reality and hides the bug until production.

## How to export

Any SMS backup app that exports to XML or JSON works — *SMS Backup & Restore* is the
common one. Export, filter to your bank senders, redact, paste.

## Layout

```
fixtures/
  alrajhi/
    pos-purchase-ar.txt          # the raw message, exactly as received
    pos-purchase-ar.json         # expected parse output
    refund-ar.txt
    declined-en.txt
  snb/
  albilad/
  alinma/
  stcpay/
  _rejects/                      # messages that must NOT parse as transactions
```

Each `.txt` is one message. Each paired `.json` is the expected output — written by hand,
once, and then guarded by CI forever.

## Contributing coverage for a new bank

This is how the project out-covers a closed competitor: open a PR with redacted fixtures
for a bank we do not handle yet. You do not need to write the parser — the fixtures alone
are the valuable part, and they are what makes the template correct rather than plausible.
