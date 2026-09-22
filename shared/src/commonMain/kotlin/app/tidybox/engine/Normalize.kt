package app.tidybox.engine

/** NFKC is platform-provided (java.text.Normalizer on JVM/Android, Foundation on iOS). */
internal expect fun nfkc(s: String): String

private val BIDI = setOf('‎', '‏', '؜', '‪', '‫', '‬', '‭', '‮',
    '⁦', '⁧', '⁨', '⁩')
private val ARABIC_INDIC = "٠١٢٣٤٥٦٧٨٩"
private val EASTERN_ARABIC = "۰۱۲۳۴۵۶۷۸۹"
private val ALEF_VARIANTS = Regex("[أإآٱ]")
private val RUN_OF_SPACE = Regex("[ \\t]+")

/**
 * Message-level normalization. Everything a template regex sees goes through here first.
 *
 * Learned from the spike (docs/spike/RESULT.md):
 *  - NFKC folds presentation-form Arabic (ﺣواﻟة, U+FBxx) back to real letters. Without it
 *    every template misses silently on some AlRajhi messages.
 *  - Tatweel (U+0640) is KEPT. AlRajhi's short form uses it as a marker: `بـSAR 98`, `لـPETROLY C`.
 *    Strip it only in [normalizeMerchant].
 *  - Bidi controls sit inside amounts and before dates (U+061C on every AlRajhi 2026 date).
 */
fun normalize(text: String): String {
    val folded = nfkc(text)
    val sb = StringBuilder(folded.length)
    for (ch in folded) {
        when {
            ch in BIDI -> continue
            ch in 'ً'..'ْ' -> continue // harakat
            ch == ' ' -> sb.append(' ')
            else -> {
                val d = ARABIC_INDIC.indexOf(ch).takeIf { it >= 0 } ?: EASTERN_ARABIC.indexOf(ch)
                if (d >= 0) sb.append('0' + d) else sb.append(ch)
            }
        }
    }
    val s = ALEF_VARIANTS.replace(sb, "ا").replace('ى', 'ي')
    return s.split('\n')
        .map { RUN_OF_SPACE.replace(it, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

private val NOT_MERCHANT_CHARS = Regex("[^a-z0-9\\u0600-\\u06FF ]+")

/** Merchant key used for dictionary lookup and user overrides. Lossy on purpose. */
fun normalizeMerchant(m: String?): String =
    NOT_MERCHANT_CHARS.replace((m ?: "").lowercase().replace("ـ", ""), " ").trim()
