package co.raseed

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import co.raseed.db.RaseedDb
import co.raseed.engine.EngineResult
import co.raseed.engine.categorize
import co.raseed.engine.extract
import java.security.MessageDigest

/**
 * Sender allowlist — the privacy enforcement point (design §2.2). Anything not here is dropped
 * in the receiver before it is stored, parsed or hashed.
 * ponytail: constant list of Saudi bank sender IDs; user-editable allowlist is a settings PR.
 */
val ALLOWED_SENDERS = setOf(
    "AlRajhiBank", "Alinma", "AlinmaBank", "SNB", "AlAhli", "SABB", "Riyad Bank", "RiyadBank", "ANB",
    "BSF", "AlBilad", "Bank AlBilad", "AlJazira", "BankAlJazira", "stc pay", "stcpay", "STC Bank", "D360",
)

fun isAllowedSender(sender: String?): Boolean =
    sender != null && ALLOWED_SENDERS.any { it.equals(sender, ignoreCase = true) }

object Db {
    @Volatile private var instance: RaseedDb? = null
    fun get(context: Context): RaseedDb = instance ?: synchronized(this) {
        instance ?: RaseedDb(AndroidSqliteDriver(RaseedDb.Schema, context.applicationContext, "raseed.db")).also { instance = it }
    }
}

private fun sha256(vararg parts: String): String =
    MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Store a raw message. Idempotent on (sender, body, received_at). Returns false if dropped. */
fun storeMessage(db: RaseedDb, sender: String?, body: String, receivedAt: Long): Boolean {
    if (!isAllowedSender(sender)) return false
    db.raseedQueries.insertMessage(sha256(sender!!, body, receivedAt.toString()), sender, body, receivedAt)
    return true
}

/** Parse everything stored but not yet parsed. Pure engine in, rows out. Safe to run any time. */
fun parsePending(db: RaseedDb) {
    val overrides = db.raseedQueries.rules().executeAsList().associate { it.merchant_key to it.category }
    for (m in db.raseedQueries.unparsed().executeAsList()) {
        when (val r = extract(m.body)) {
            is EngineResult.Parsed -> {
                val tx = r.tx
                val c = categorize(tx, overrides)
                db.raseedQueries.insertTx(
                    m.id, tx.type.name, tx.amount, tx.currency, tx.merchant, tx.cardLast4,
                    tx.occurredAt?.let { "%04d-%02d-%02d %02d:%02d".format(it.year, it.month, it.day, it.hour, it.minute) },
                    c.category, c.reason.name,
                )
            }
            is EngineResult.Rejected -> Unit // informational; stays in `message` only
        }
        db.raseedQueries.markParsed(m.id)
    }
}
