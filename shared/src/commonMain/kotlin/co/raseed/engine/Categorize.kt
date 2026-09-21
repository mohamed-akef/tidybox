package co.raseed.engine

/** Which step answered. The UI shows this so "why is this in Groceries?" always has an answer. */
enum class CategoryReason { TYPE, OVERRIDE, DICTIONARY, FUZZY, KEYWORD, UNKNOWN }

data class Categorized(val category: String?, val reason: CategoryReason)

// ---------------------------------------------------------------- seed data
// ponytail: Kotlin constants for this slice; these three tables ARE the rule pack's
// merchant section and move into signed JSON together with the templates.

/** Categories decided by transaction type alone. No merchant needed. */
private val TYPE_CATEGORY = mapOf(
    TxType.TRANSFER_IN to "Transfer", TxType.TRANSFER_OUT to "Transfer", TxType.TRANSFER_SELF to "Transfer",
    TxType.SALARY to "Income", TxType.DEPOSIT to "Income",
    TxType.ATM_OUT to "Cash", TxType.GOV to "Government", TxType.INVESTMENT to "Finance",
)

/** Seed dictionary. Written before the corpus merchants were read (see docs/spike/RESULT.md caveats). */
private val SEED: Map<String, List<String>> = mapOf(
    "Food" to listOf("albaik", "al baik", "herfy", "kudu", "mcdonald", "kfc", "burger king", "hardee", "dunkin",
        "starbucks", "barn", "shawarmer", "hungerstation", "jahez", "keeta", "talabat", "toyou", "mrsool",
        "pizza hut", "dominos", "subway", "maestro", "kababji", "tazaj", "hamburgini", "half million", "dose cafe"),
    "Groceries" to listOf("panda", "tamimi", "othaim", "danube", "carrefour", "lulu", "bin dawood", "nesto",
        "farm superstores", "manuel", "sadhan", "raya", "abdullah al othaim"),
    "Shopping" to listOf("amazon", "noon", "jarir", "extra", "ikea", "saco", "shein", "namshi", "centrepoint",
        "max", "h&m", "zara", "redtag", "lc waikiki"),
    "Transport" to listOf("careem", "uber", "kaiian", "jeeny", "sapn", "riyadh metro"),
    "Fuel" to listOf("aldrees", "sasco", "naft", "petromin", "petroly", "adnoc", "tas'helat", "liter"),
    "Telecom" to listOf("mobily", "stc", "zain", "sawa", "lebara", "virgin mobile", "salam", "موبايلي", "سوا", "زين"),
    "Software" to listOf("apple.com/bill", "apple.com", "google", "netflix", "spotify", "shahid", "osn", "anghami",
        "anthropic", "claude.ai", "openai", "chatgpt", "digitalocean", "github", "microsoft", "adobe", "steam",
        "playstation"),
    "Health" to listOf("nahdi", "dawaa", "al dawaa", "whites", "kunooz", "al-dawaa", "sulaiman al habib", "mouwasat"),
    "Travel" to listOf("airport", "saudia", "flynas", "flyadeal", "booking.com", "airbnb", "almosafer", "hilton",
        "marriott"),
    "Government" to listOf("absher", "muqeem", "tamm", "vat", "zakat", "moi", "رخص القيادة"),
    "Finance" to listOf("investment", "capital", "tadawul", "derayah", "alinma capital"),
    "Transfer" to listOf("stc pay", "urpay", "barq", "tweeq"), // wallet top-ups move money, they do not spend it
    "Services" to listOf("laundry", "car wash", "salon", "barber"),
)

/**
 * Keyword rules — only where the meaning is IN the string. Latin keywords match whole tokens
 * (no regex `\b`, whose Unicode behaviour differs across JDKs); Arabic keywords match as substrings,
 * because Arabic merchants glue prefixes on (`المطعم`, `للمغسلة`).
 * Transliterations (`mtaam`, `mahta`) are where the spike's hardest hits came from.
 */
private val LATIN_KEYWORDS: Map<String, String> = listOf(
    "Food" to "rest restaurant resto cafe caffe coffee bakery kitchen grill shawarma burger pizza mtaam mataam matam mtam",
    "Groceries" to "market supermarket hyper grocery mart super",
    "Health" to "pharm pharmacy",
    "Fuel" to "petrol fuel station mahta mahatta",
    "Services" to "laun laundry wash",
    "Travel" to "hotel airline airways airport",
    "Software" to "subscription",
).flatMap { (cat, words) -> words.split(' ').map { it to cat } }.toMap()

private val ARABIC_KEYWORDS: List<Pair<String, String>> = listOf(
    "مطعم" to "Food", "كافيه" to "Food", "كوفي" to "Food", "مخبز" to "Food", "شاورما" to "Food", "بوفيه" to "Food",
    "بقالة" to "Groceries", "تموينات" to "Groceries", "سوبر ماركت" to "Groceries", "هايبر" to "Groceries",
    "صيدلية" to "Health",
    "محطة" to "Fuel", "بنزين" to "Fuel", "وقود" to "Fuel",
    "مغسلة" to "Services",
    "فندق" to "Travel", "طيران" to "Travel",
)

/** Multi-word Latin keywords, matched as phrases on the normalized merchant. */
private val LATIN_PHRASES = listOf("gas station" to "Fuel")

// Precomputed normalized dictionary: (normalized name, category)
private val DICT: List<Pair<String, String>> =
    SEED.flatMap { (cat, names) -> names.map { normalizeMerchant(it) to cat } }.filter { it.first.isNotEmpty() }

private fun containsTokenSequence(tokens: List<String>, needle: List<String>): Boolean {
    if (needle.isEmpty() || needle.size > tokens.size) return false
    return (0..tokens.size - needle.size).any { i -> tokens.subList(i, i + needle.size) == needle }
}

/**
 * Strict priority: type → user override → exact dictionary → fuzzy dictionary → keyword → unknown.
 * Never guesses: a miss is `Categorized(null, UNKNOWN)` and the UI asks once.
 * @param overrides the user's corrections, keyed by [normalizeMerchant].
 */
fun categorize(tx: Transaction, overrides: Map<String, String> = emptyMap()): Categorized {
    TYPE_CATEGORY[tx.type]?.let { return Categorized(it, CategoryReason.TYPE) }
    if (tx.type == TxType.REFUND && tx.merchant == null) return Categorized("Income", CategoryReason.TYPE)

    val nm = normalizeMerchant(tx.merchant)
    if (nm.isEmpty()) return Categorized(null, CategoryReason.UNKNOWN)
    overrides[nm]?.let { return Categorized(it, CategoryReason.OVERRIDE) }

    DICT.firstOrNull { it.first == nm }?.let { return Categorized(it.second, CategoryReason.DICTIONARY) }

    val tokens = nm.split(' ')
    for ((name, cat) in DICT) {
        if (name.length < 4) continue
        val hit = containsTokenSequence(tokens, name.split(' ')) || (name.length >= 6 && nm.contains(name))
        if (hit) return Categorized(cat, CategoryReason.FUZZY)
    }

    for (tok in tokens) LATIN_KEYWORDS[tok]?.let { return Categorized(it, CategoryReason.KEYWORD) }
    for ((phrase, cat) in LATIN_PHRASES) if (nm.contains(phrase)) return Categorized(cat, CategoryReason.KEYWORD)
    for ((word, cat) in ARABIC_KEYWORDS) if (nm.contains(word)) return Categorized(cat, CategoryReason.KEYWORD)

    return Categorized(null, CategoryReason.UNKNOWN)
}
