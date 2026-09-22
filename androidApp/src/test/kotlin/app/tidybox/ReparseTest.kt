package app.tidybox

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tidybox.db.TidyBoxDb
import app.tidybox.engine.RulePack
import kotlin.test.Test
import kotlin.test.assertEquals

class ReparseTest {
    private fun db(): TidyBoxDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TidyBoxDb.Schema.create(driver)
        return TidyBoxDb(driver)
    }

    @Test
    fun `a rejected message is re-read after the engine learns its shape`() {
        val db = db()
        val q = db.tidyBoxQueries
        q.insertMessage("h1", "CIB", "Your credit card ending with#8016 was charged for EGP 118.00 at SAOOD MARKET on 24/11/25  at 18:27.", 1L)
        q.markParsed(q.unparsed().executeAsList().single().id) // the old engine rejected it
        assertEquals(0, q.unparsed().executeAsList().size)
        assertEquals(1, reparseUnread(db, RulePack.bundled, keepRaw = true))
        assertEquals(1, q.recentTx().executeAsList().size)
        assertEquals(0, reparseUnread(db, RulePack.bundled, keepRaw = true)) // idempotent
    }
}
