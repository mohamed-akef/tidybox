package app.tidybox.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden corpus: fixtures/corpus.jsonl, 186 messages: 174 Saudi from the spike, 12 Egyptian (CIB English, NBE Arabic) from public parser test suites.
 * `expect == null` means the message must NOT become a transaction.
 * The bar is the spike's bar: every row, not a percentage.
 */
private val HAS_YEAR = Regex("\\d{2,4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}")

class CorpusTest {
    private val rows = corpus

    @Test
    fun corpusIsNotEmpty() = assertTrue(rows.size >= 186, "expected the full corpus, got ${rows.size}")

    @Test
    fun everyRejectStaysRejected() {
        val failures = rows.filter { it.expect == null }.mapNotNull { r ->
            val res = extract(r.text)
            if (res is EngineResult.Parsed) "#${r.id} became $res" else null
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun everyTransactionExtractsExactly() {
        val failures = rows.filter { it.expect != null }.mapNotNull { r ->
            val e = r.expect!!
            when (val res = extract(r.text)) {
                is EngineResult.Rejected -> "#${r.id} rejected ${res.why}, expected $e"
                is EngineResult.Parsed -> {
                    val tx = res.tx
                    val problems = buildList {
                        if (tx.type.name != e.type.uppercase()) add("type ${tx.type} != ${e.type}")
                        if (tx.amount != e.amount) add("amount ${tx.amount} != ${e.amount}")
                        if (tx.currency != e.currency) add("currency ${tx.currency} != ${e.currency}")
                        if (e.merchant != null && normalizeMerchant(tx.merchant) != normalizeMerchant(e.merchant)) add("merchant '${tx.merchant}' != '${e.merchant}'")
                        // Some banks (NBE) send day-month only; a date without a year is no date.
                        if (e.type == "purchase" && tx.occurredAt == null && HAS_YEAR.containsMatchIn(r.text)) add("no date")
                    }
                    if (problems.isEmpty()) null else "#${r.id} ${problems.joinToString("; ")}"
                }
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun fxPurchaseKeepsOriginalAndSettlement() {
        val r = rows.first { it.text.contains("إجمالي المبلغ المستحق") }
        val tx = (extract(r.text) as EngineResult.Parsed).tx
        assertEquals(23.0 to "USD", tx.amount to tx.currency)
        assertEquals(88.36 to "SAR", tx.settled)
    }
}
