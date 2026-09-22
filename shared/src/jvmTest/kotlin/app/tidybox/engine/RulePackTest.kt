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
