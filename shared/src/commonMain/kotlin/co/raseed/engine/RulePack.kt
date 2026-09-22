package co.raseed.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The merchant section of a rule pack (design §6, D3). Source of truth: `rulepacks/merchants.json`,
 * compiled into [RulePack.bundled] at build time. An updated pack can be loaded from JSON at
 * runtime and handed to [categorize]; the app falls back to the bundled one.
 *
 * ponytail: BUNDLED_RULEPACK_JSON is a `const val`, so the bundled pack is capped by the JVM's
 * 64 KB constant-pool limit. merchants.json is ~6 KB today; if community contributions approach
 * that ceiling, move it to a platform resource read at startup.
 *
 * ponytail: extraction templates are still Kotlin (Extract.kt) — they are per-bank regex that
 * changed on every spike pass and are not yet stable enough to be data. Download + Ed25519
 * verification is the next step of D3; nothing here opens a socket.
 */
@Serializable
data class RulePack(
    val version: Int,
    val name: String,
    val categories: List<String>,
    /** category → merchant names; matched exact and fuzzy after [normalizeMerchant]. */
    val merchants: Map<String, List<String>>,
    val keywords: Keywords,
) {
    @Serializable
    data class Keywords(
        /** category → Latin whole-token keywords (`rest`, `mtaam`, `mahta`). */
        val latin: Map<String, List<String>> = emptyMap(),
        /** category → Latin multi-word phrases matched as substrings (`gas station`). */
        val latinPhrases: Map<String, List<String>> = emptyMap(),
        /** category → Arabic substrings (`مطعم`, `محطة`); Arabic merchants glue prefixes on. */
        val arabic: Map<String, List<String>> = emptyMap(),
    )

    // Precomputed lookup shapes; computed once per pack instance.
    internal val dict: List<Pair<String, String>> by lazy {
        merchants.flatMap { (cat, names) -> names.map { normalizeMerchant(it) to cat } }.filter { it.first.isNotEmpty() }
    }
    internal val latinTokens: Map<String, String> by lazy {
        keywords.latin.flatMap { (cat, words) -> words.map { it.lowercase() to cat } }.toMap()
    }
    internal val latinPhrases: List<Pair<String, String>> by lazy {
        keywords.latinPhrases.flatMap { (cat, ps) -> ps.map { it.lowercase() to cat } }
    }
    internal val arabic: List<Pair<String, String>> by lazy {
        keywords.arabic.flatMap { (cat, ws) -> ws.map { it to cat } }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a pack. Throws on malformed JSON or missing fields; the caller decides whether to fall back. */
        fun parse(text: String): RulePack = json.decodeFromString<RulePack>(text).also { p ->
            require(p.version >= 1) { "unsupported rule pack version ${p.version}" }
            // Every map keyed by category is checked, not just `merchants` — a keyword rule can emit
            // a category just as a dictionary entry can, and an undeclared one would be invisible to
            // knownCategories() and so unpickable in the correction dialog.
            val undeclared = (p.merchants.keys + p.keywords.latin.keys + p.keywords.latinPhrases.keys +
                p.keywords.arabic.keys) - p.categories.toSet()
            require(undeclared.isEmpty()) { "categories not declared in `categories`: $undeclared" }
        }

        /** The pack compiled in from rulepacks/merchants.json. */
        val bundled: RulePack by lazy { parse(BUNDLED_RULEPACK_JSON) }
    }
}
