# Research: categorization without an LLM

Researched 2026-09-16, in direct response to a fair challenge:

> *"If we didn't have an LLM model, how will we know if this message is coming from
> billing or what? How will you give this list, how can you know?"*

The question splits cleanly in two. The first half is already solved. The second half is
the real problem — and an LLM does not solve it either.

---

## Half one: reading the message. There is nothing to understand.

A real Bank Al Bilad SMS:

```
شراء عبر نقاط البيع
بطاقة: **1234;الإئتمانية
لدى: Some merchant
دولة: السعودية
مبلغ: 12.00 SAR
رصيد: 1234.56 SAR
في: 2019-05-07 23:44
```

The bank **labels every field**. `مبلغ:` then the amount. `لدى:` then the merchant.
`بطاقة:` then the card. `في:` then the date. SNB does the same thing with `بـSAR` and
`من`.

This is a form, not prose. Asking a language model to read `مبلغ: 12.00 SAR` is like
hiring a translator to read a price tag. Extraction — amount, merchant, card, date,
type — is genuinely solved, and not by cleverness.

---

## Half two: the merchant → category list

This is the legitimate question. Here is what a real shipping product uses.

### What PennyWiseAI actually does

PennyWiseAI is an open-source Android SMS expense tracker on the Play Store with active
Saudi parser development. Its **entire** categorization engine is a Kotlin file of
keyword sets:

```kotlin
private val FOOD = setOf("swiggy", "zomato", "dominos", "pizza", "burger",
    "kfc", "restaurant", "cafe", "starbucks", "al baik", "talabat", ...)
private val HEALTHCARE = setOf("pharmacy", "hospital", "clinic", "boots",
    "life pharmacy", "watsons", ...)
private val TRANSPORT = setOf("uber", "careem", "petrol", "fuel", "parking",
    "adnoc", "emarat", ...)

fun getCategory(merchantName: String): String {
    for (rule in RULES) {
        if (matches(merchantName, rule.includes, rule.excludes)) return rule.categoryName
    }
    return "Others"          // <- says "I don't know"
}
```

Ordered rules, word-boundary matching for single tokens, substring matching for phrases,
explicit `excludes` to stop `jiomart` falling into Shopping via `mart`. **No model.**
Shipping today.

### The weakness of that list — which is the actual opportunity

Read what is *in* it. `swiggy`, `zomato`, `bigbasket`, `jiomart` — India. `careem`,
`lulu`, `union coop`, `emarat` — UAE. `villa market`, `7-11 sukhumvit22`,
`gourmet market` — Thailand.

Saudi merchants are almost absent. The list contains `al baik`, `bateel` and `alsafadi`
and very little else. There is **no Panda, no Tamimi, no Othaim, no Danube, no Jarir, no
Extra, no Nahdi, no Dawaa, no Aldrees, no Sasco, no Hungerstation, no Jahez, no SEC, no
STC, no Mobily.**

The list grew wherever contributors happened to live. **That gap is the project.**

### So how do you get the list?

Not by inventing intelligence — by building inventory, from three sources:

1. **A Saudi seed dictionary.** A few hundred entries covering supermarkets, delivery
   apps, telcos, petrol, pharmacies and SADAD billers. Spending concentrates hard: a
   typical user's transactions cluster into a small set of merchants they visit
   repeatedly.
2. **The user's own corrections.** One tap on an unknown merchant, stored locally and
   permanently. This is the layer no competitor can match, because it encodes *your*
   truth, not the average user's.
3. **Community pull requests to the rule pack.** The open-source flywheel, and the
   reason this project can out-cover a closed competitor rather than merely match it.

Wafeer has exactly this same list. They keep it on their servers. Same list, different
owner.

---

## Why an LLM does not rescue the hard cases

Take a real-shaped merchant string: `ALDR 8821 RUH`.

- A dictionary says **"I don't know — tell me once."**
- A small model says **"Restaurants"** — confidently, and wrong.

**The information is not in the string.** The model is not retrieving anything; it is
inventing. In an app about money, a confident wrong category is worse than a blank one,
because a user who sees plausible-looking categories stops checking them — and silently
wrong totals are the failure mode that destroys trust in this product class.

Where the information *is* in the string — `صيدلية النهدي`, `PIZZA HUT AL OLAYA` — a
keyword catches it. Knowing that صيدلية means pharmacy is a dictionary entry, not
reasoning.

Two further practical objections to a bundled model:

- **Arabic-transliterated Saudi merchant abbreviations are the worst case for a small
  model.** A 270M-parameter model's world knowledge of `ALDR`, `OTHAIM`, `TMS` is
  approximately zero, and its Arabic is weak.
- **Non-determinism is a support nightmare.** "Why did it put my groceries in Travel?"
  has no answer. The rule pipeline records *which rule fired*, so the UI can always show
  its reasoning.

### The one place a model could earn its slot

Genuinely novel, semantically *readable* merchant names that the dictionary has never
seen — `BURGER BOUTIQUE AL KHOBAR` → Food. A model's world knowledge helps there, where
a dictionary miss is otherwise a user tap.

So the design keeps the categorizer a **pluggable slot**
([D4](../decisions.md#d4--rules-and-user-corrections-no-llm)). If the spike shows the
unknown-merchant tail is larger than expected, a small on-device model can fill that one
step later without touching extraction, storage or UI. The door stays open. We just are
not building the foundation on it.

---

## The other reading of the question: payment vs promotion vs important

If the question was about classifying the **message** rather than the merchant, that is
a much easier problem, and it is structural rather than semantic:

| Signal | Classification |
|---|---|
| Allowlisted bank sender + matches a transaction template | **Payment** |
| Allowlisted bank sender + matches no transaction template | **Important / informational** |
| Allowlisted marketing sender | **Promotion** |
| Not allowlisted | never read at all |

Sender plus template match. No meaning required.

---

## Conclusion, stated honestly

Rules are the right foundation, and the evidence is that real products ship exactly this.
But **the coverage number is unmeasured on real Saudi messages**, and the only published
figure — Mizan's ~79% — is well below comfortable.

That is why the next step is [a measurement, not a build](../spike/README.md).
