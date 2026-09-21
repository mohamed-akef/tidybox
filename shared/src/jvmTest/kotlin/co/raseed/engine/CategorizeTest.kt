package co.raseed.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The spike's bar (docs/spike/RESULT.md): `wrong` must be zero. A `?` label means the category
 * is not in the string, so any assignment is wrong and only abstaining is correct.
 * `correct` is frozen at the spike's count so a dictionary or keyword regression is visible.
 */
class CategorizeTest {
    private val rows = corpus.filter { it.expect != null }

    private fun bucket(r: Row): String {
        val truth = r.expect!!.category
        val tx = (extract(r.text) as EngineResult.Parsed).tx
        val got = categorize(tx).category
        return when {
            got == null -> "abstain"
            truth == "?" -> "WRONG #${r.id} guessed $got for '${tx.merchant}'"
            got == truth -> "correct"
            else -> "WRONG #${r.id} $got != $truth for '${tx.merchant}'"
        }
    }

    @Test
    fun neverWrong() {
        assertEquals(emptyList(), rows.map(::bucket).filter { it.startsWith("WRONG") })
    }

    @Test
    fun typeDerivedRowsAreAlwaysCategorized() {
        val typed = rows.filter { it.expect!!.type !in setOf("purchase", "refund", "bill") }
        assertEquals(typed.size, typed.map(::bucket).count { it == "correct" })
    }

    @Test
    fun merchantRowsMatchTheSpike() {
        // 50 purchase/refund rows with a merchant: spike scored 29 correct / 0 wrong / 21 abstain.
        val merchantRows = rows.filter { it.expect!!.type in setOf("purchase", "refund") && it.expect!!.merchant != null }
        val b = merchantRows.map(::bucket)
        assertEquals(50, merchantRows.size)
        assertEquals(29 to 21, b.count { it == "correct" } to b.count { it == "abstain" })
    }

    @Test
    fun overrideWinsAndReasonIsRecorded() {
        val tx = Transaction(TxType.PURCHASE, 9.0, "SAR", "THATI LIMITED", "8791", null, null)
        assertEquals(Categorized(null, CategoryReason.UNKNOWN), categorize(tx))
        assertEquals(Categorized("Food", CategoryReason.OVERRIDE), categorize(tx, mapOf(normalizeMerchant("THATI LIMITED") to "Food")))
    }

    @Test
    fun transliteratedArabicKeywordsHit() {
        fun cat(m: String) = categorize(Transaction(TxType.PURCHASE, 1.0, "SAR", m, null, null, null))
        assertEquals(Categorized("Food", CategoryReason.KEYWORD), cat("Mtaam Ns Sfry Ltqdym"))
        assertEquals(Categorized("Fuel", CategoryReason.KEYWORD), cat("MAHTA MOSLM ALSAIRE"))
        assertEquals(Categorized("Services", CategoryReason.KEYWORD), cat("NEBRAS ALJANOBEYA LAUN"))
        assertEquals(Categorized("Groceries", CategoryReason.FUZZY), cat("E206 Tamimi"))
        assertEquals(Categorized("Software", CategoryReason.DICTIONARY), cat("APPLE.COM/BILL"))
    }
}
