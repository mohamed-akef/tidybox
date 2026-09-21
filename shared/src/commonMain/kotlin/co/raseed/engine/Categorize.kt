package co.raseed.engine

/** Which step answered. The UI shows this so "why is this in Groceries?" always has an answer. */
enum class CategoryReason { TYPE, OVERRIDE, DICTIONARY, FUZZY, KEYWORD, UNKNOWN }

data class Categorized(val category: String?, val reason: CategoryReason)

/** Categories decided by transaction type alone. No merchant needed. Structural, so not in the rule pack. */
private val TYPE_CATEGORY = mapOf(
    TxType.TRANSFER_IN to "Transfer", TxType.TRANSFER_OUT to "Transfer", TxType.TRANSFER_SELF to "Transfer",
    TxType.SALARY to "Income", TxType.DEPOSIT to "Income",
    TxType.ATM_OUT to "Cash", TxType.GOV to "Government", TxType.INVESTMENT to "Finance",
)

/** Every category the engine can emit, plus "Other" for the user's catch-all. What the correction picker shows. */
val KNOWN_CATEGORIES: List<String> = (RulePack.bundled.categories + TYPE_CATEGORY.values + "Other").distinct().sorted()

private fun containsTokenSequence(tokens: List<String>, needle: List<String>): Boolean {
    if (needle.isEmpty() || needle.size > tokens.size) return false
    return (0..tokens.size - needle.size).any { i -> tokens.subList(i, i + needle.size) == needle }
}

/**
 * Strict priority: type → user override → exact dictionary → fuzzy dictionary → keyword → unknown.
 * Never guesses: a miss is `Categorized(null, UNKNOWN)` and the UI asks once.
 * @param overrides the user's corrections, keyed by [normalizeMerchant].
 * @param pack the merchant dictionary and keywords; bundled unless the app has loaded a newer one.
 */
fun categorize(tx: Transaction, overrides: Map<String, String> = emptyMap(), pack: RulePack = RulePack.bundled): Categorized {
    TYPE_CATEGORY[tx.type]?.let { return Categorized(it, CategoryReason.TYPE) }
    if (tx.type == TxType.REFUND && tx.merchant == null) return Categorized("Income", CategoryReason.TYPE)

    val nm = normalizeMerchant(tx.merchant)
    if (nm.isEmpty()) return Categorized(null, CategoryReason.UNKNOWN)
    overrides[nm]?.let { return Categorized(it, CategoryReason.OVERRIDE) }

    pack.dict.firstOrNull { it.first == nm }?.let { return Categorized(it.second, CategoryReason.DICTIONARY) }

    val tokens = nm.split(' ')
    for ((name, cat) in pack.dict) {
        if (name.length < 4) continue
        val hit = containsTokenSequence(tokens, name.split(' ')) || (name.length >= 6 && nm.contains(name))
        if (hit) return Categorized(cat, CategoryReason.FUZZY)
    }

    for (tok in tokens) pack.latinTokens[tok]?.let { return Categorized(it, CategoryReason.KEYWORD) }
    for ((phrase, cat) in pack.latinPhrases) if (nm.contains(phrase)) return Categorized(cat, CategoryReason.KEYWORD)
    for ((word, cat) in pack.arabic) if (nm.contains(word)) return Categorized(cat, CategoryReason.KEYWORD)

    return Categorized(null, CategoryReason.UNKNOWN)
}
