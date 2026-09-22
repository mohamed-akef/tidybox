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

class SchemaV2Test {
    @Test
    fun `a v1 database migrates and hidden rows leave the inbox`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // v1 as it shipped in 0.1.x: tx without `hidden`.
        driver.execute(null, "CREATE TABLE message (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT NOT NULL UNIQUE, sender TEXT NOT NULL, body TEXT NOT NULL, received_at INTEGER NOT NULL, parsed INTEGER NOT NULL DEFAULT 0)", 0)
        driver.execute(null, "CREATE TABLE tx (id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL UNIQUE REFERENCES message(id), type TEXT NOT NULL, amount REAL NOT NULL, currency TEXT NOT NULL, merchant TEXT, merchant_key TEXT, card_last4 TEXT, occurred_at TEXT, category TEXT, category_reason TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE merchant_rule (merchant_key TEXT PRIMARY KEY, category TEXT NOT NULL)", 0)
        driver.execute(null, "INSERT INTO message(hash, sender, body, received_at) VALUES ('h', 'CIB', 'x', 1000)", 0)
        driver.execute(null, "INSERT INTO tx(message_id, type, amount, currency, category_reason, occurred_at) VALUES (1, 'PURCHASE', 5, 'EGP', 'UNKNOWN', '2028-08-26 01:16')", 0)
        TidyBoxDb.Schema.migrate(driver, 1, 2)
        val q = TidyBoxDb(driver).tidyBoxQueries
        assertEquals(1, q.recentTx().executeAsList().size)
        q.clearImplausibleDates()
        assertEquals(null, q.txDetail(1).executeAsOne().occurred_at)
        q.setHidden(1, 1)
        assertEquals(0, q.recentTx().executeAsList().size)
        assertEquals("CIB", q.txDetail(1).executeAsOne().sender) // still stored, still inspectable
    }
}
