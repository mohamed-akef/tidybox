package app.tidybox.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RulePackTest {
    @Test
    fun bundledIsTheRepoFile() {
        // The build copies rulepacks/merchants.json into BUNDLED_RULEPACK_JSON; prove nothing drifted.
        assertEquals(RulePack.parse(File("../rulepacks/merchants.json").readText()), RulePack.bundled)
    }

    @Test
    fun bundledHasTheShapeTheEngineNeeds() {
        val p = RulePack.bundled
        assertTrue(p.merchants.values.sumOf { it.size } > 100, "seed dictionary shrank")
        assertTrue("mtaam" in p.latinTokens && "محطة" in p.arabic.map { it.first }, "transliterated/arabic keywords missing")
        assertTrue(knownCategories().containsAll(listOf("Food", "Transfer", "Other")))
    }

    @Test
    fun aNewerPackIsUsedWhenGiven() {
        val custom = RulePack.parse("""{"version":2,"name":"t","categories":["Food"],"merchants":{"Food":["thati"]},"keywords":{}}""")
        val tx = Transaction(TxType.PURCHASE, 9.0, "SAR", "THATI LIMITED", null, null, null)
        assertEquals(Categorized(null, CategoryReason.UNKNOWN), categorize(tx))
        assertEquals(Categorized("Food", CategoryReason.FUZZY), categorize(tx, pack = custom))
    }

    @Test
    fun aLoadedPacksNewCategoryIsOfferedToTheUser() {
        // Regression: knownCategories read the bundled pack, so a loaded pack could categorize a
        // row into a category the correction dialog had no way to offer.
        val custom = RulePack.parse("""{"version":2,"name":"t","categories":["Food","Charity"],"merchants":{"Charity":["ehsan"]},"keywords":{}}""")
        assertTrue("Charity" !in knownCategories())
        assertTrue("Charity" in knownCategories(custom))
    }

    @Test
    fun anUndeclaredKeywordCategoryIsRejected() {
        assertFailsWith<IllegalArgumentException> { RulePack.parse("""{"version":1,"name":"t","categories":["Food"],"merchants":{},"keywords":{"arabic":{"Charity":["جمعية"]}}}""") }
    }

    @Test
    fun malformedPackIsRejectedNotSwallowed() {
        assertFailsWith<Exception> { RulePack.parse("{not json") }
        assertFailsWith<IllegalArgumentException> { RulePack.parse("""{"version":1,"name":"t","categories":[],"merchants":{"Food":["x"]},"keywords":{}}""") }
    }
}

class TemplatesTest {
    private val base = """"version":2,"name":"t","categories":["Food"],"merchants":{},"keywords":{}"""

    @Test
    fun aPackWithoutTemplatesUsesTheBundledOnes() {
        val custom = RulePack.parse("{$base}")
        assertEquals(extract("شراء\nمبلغ:SAR 5.00\nلدى:BK"), extract("شراء\nمبلغ:SAR 5.00\nلدى:BK", custom))
    }

    @Test
    fun aPacksTemplatesTeachANewBankWithoutARelease() {
        val custom = RulePack.parse("""{$base,"templates":{"currency":"(?:XYZ)","number":"(\\d+)",
            "types":[{"re":"spent","type":"PURCHASE","scope":"whole"}],
            "amount":[{"re":"{NUM} ({CUR})"}],"merchantTypes":["PURCHASE"],
            "merchant":[{"re":"at (.+)$","scope":"whole"}]}}""")
        val tx = (extract("spent 42 XYZ at CORNER SHOP", custom) as EngineResult.Parsed).tx
        assertEquals(Transaction(TxType.PURCHASE, 42.0, "XYZ", "CORNER SHOP", null, null, null), tx)
        assertTrue(extract("spent 42 XYZ at CORNER SHOP") is EngineResult.Rejected) // bundled does not know it
    }

    @Test
    fun aBadTemplateFailsAtLoadNotAtParse() {
        assertFailsWith<IllegalArgumentException> { RulePack.parse("""{$base,"templates":{"currency":"x","number":"x","types":[{"re":"(","type":"PURCHASE"}],"amount":[]}}""") }
        assertFailsWith<IllegalArgumentException> { RulePack.parse("""{$base,"templates":{"currency":"x","number":"x","types":[{"re":"a","type":"NOPE"}],"amount":[]}}""") }
    }
}

class CibDebitDateTest {
    @Test
    fun cibDebitDateIsDayMonthYear() {
        val tx = (extract("تم خصم مبلغ EGP 5135.00  من بطاقة الخصم المباشر المنتهية بـ **6538 عند FAWRY IKEA CFC في 19/09/26 14:08 ، الرصيد المتاح EGP 3927.34") as EngineResult.Parsed).tx
        assertEquals(LocalDateTime(2026, 9, 19, 14, 8), tx.occurredAt)
        assertEquals("FAWRY IKEA CFC", tx.merchant)
        assertEquals("6538", tx.cardLast4)
    }
}

class CibDeclineTest {
    @Test
    fun cibDeclinesAreDeclinedNotSpend() {
        for (text in listOf(
            "لقد تم رفض المعاملة من RAM على بطاقتكم الائتمانية المنتهية ب8016 بقيمة 12500.00 EGP نظراً لوجود خطأ في إدخال تاريخ انتهاء البطاقة.",
            "لقد تم رفض المعاملة من IRAM-NAZEH KHAL على بطاقتكم الائتمانية المنتهية بـ8016 بقيمة 15000.00 EGP لعدم كفاية رصيد البطاقة.",
        )) assertEquals(EngineResult.Rejected(Rejection.DECLINED), extract(text))
    }
}
