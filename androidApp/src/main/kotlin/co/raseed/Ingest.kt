package co.raseed

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import co.raseed.db.RaseedDb
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import co.raseed.engine.CategoryReason
import co.raseed.engine.EngineResult
import co.raseed.engine.RulePack
import co.raseed.engine.Transaction
import co.raseed.engine.TxType
import co.raseed.engine.categorize
import co.raseed.engine.extract
import co.raseed.engine.normalizeMerchant
import java.security.MessageDigest

/**
 * Sender allowlist — the privacy enforcement point (design §2.2). Anything not here is dropped
 * in the receiver before it is stored, parsed or hashed. Seeded with Saudi bank IDs; the user
 * edits it in Settings and the edited set is what the receiver consults.
 */
val DEFAULT_SENDERS = setOf(
    "AlRajhiBank", "Alinma", "AlinmaBank", "SNB", "AlAhli", "SABB", "Riyad Bank", "RiyadBank", "ANB",
    "BSF", "AlBilad", "Bank AlBilad", "AlJazira", "BankAlJazira", "stc pay", "stcpay", "STC Bank", "D360",
)

object Senders {
    private const val PREFS = "raseed-senders"
    private const val KEY = "allow"
    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null) ?: DEFAULT_SENDERS
    fun set(context: Context, senders: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, senders).commit()
    }
    fun allows(context: Context, sender: String?): Boolean = allows(get(context), sender)

    /** The one matching rule. Everything that can persist a message routes through here. */
    fun allows(allowed: Set<String>, sender: String?): Boolean =
        sender != null && allowed.any { it.equals(sender.trim(), ignoreCase = true) }
}

/** The active merchant pack: a user-imported JSON if one parsed, else the bundled one (design §6). */
object RulePacks {
    private const val PREFS = "raseed-rulepack"
    private const val KEY = "json"
    fun current(context: Context): RulePack {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return RulePack.bundled
        return runCatching { RulePack.parse(text) }.getOrDefault(RulePack.bundled)
    }
    /** Parses first; a malformed pack is rejected and the previous one stays. */
    fun install(context: Context, text: String): RulePack = RulePack.parse(text).also {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).commit()
    }
    fun reset(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
}

object Db {
    @Volatile private var instance: RaseedDb? = null
    fun get(context: Context): RaseedDb = instance ?: synchronized(this) {
        instance ?: run {
            System.loadLibrary("sqlcipher")
            val app = context.applicationContext
            // Design §3: SQLCipher, key held in the Android Keystore. The passphrase is cleared by the factory after open.
            val factory = SupportOpenHelperFactory(dbPassphrase(app))
            RaseedDb(AndroidSqliteDriver(RaseedDb.Schema, app, "raseed.db", factory = factory)).also { instance = it }
        }
    }
}

private fun sha256(vararg parts: String): String =
    MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * Store a raw message. Idempotent on (sender, body, received_at).
 *
 * The allowlist check lives HERE, not in the callers: this is the only function that can put a
 * bank message on disk, so it is the only place the design §2.2 guarantee can actually be enforced.
 * Callers pass the allowed set rather than a Context so the check stays a pure function of its
 * arguments.
 *
 * @return false if the sender is not allowlisted — nothing was stored, parsed or hashed.
 */
fun storeMessage(db: RaseedDb, allowed: Set<String>, sender: String?, body: String, receivedAt: Long): Boolean {
    if (!Senders.allows(allowed, sender)) return false
    db.raseedQueries.insertMessage(sha256(sender!!, body, receivedAt.toString()), sender, body, receivedAt)
    return true
}

/** The user's correction: persists as a rule and rewrites every row of that merchant. */
fun correct(db: RaseedDb, merchantKey: String, category: String) {
    db.raseedQueries.upsertRule(merchantKey, category)
    db.raseedQueries.applyRule(category, merchantKey)
}

/** Re-categorize every transaction with the given pack. User overrides are kept as they are. */
fun recategorizeAll(db: RaseedDb, pack: RulePack) {
    val overrides = db.raseedQueries.rules().executeAsList().associate { it.merchant_key to it.category }
    db.transaction {
        for (t in db.raseedQueries.allTxForRecategorize().executeAsList()) {
            if (t.category_reason == CategoryReason.OVERRIDE.name) continue
            val c = categorize(Transaction(TxType.valueOf(t.type), 0.0, "SAR", t.merchant, null, null, null), overrides, pack)
            db.raseedQueries.setCategory(c.category, c.reason.name, t.id)
        }
    }
}

/**
 * Parse everything stored but not yet parsed. Pure engine in, rows out. Safe to run any time.
 *
 * `pack` is deliberately not defaulted: a default is right at two call sites and silently wrong at
 * the third, and the compiler is the only thing that reliably notices.
 */
fun parsePending(db: RaseedDb, pack: RulePack) {
    val overrides = db.raseedQueries.rules().executeAsList().associate { it.merchant_key to it.category }
    for (m in db.raseedQueries.unparsed().executeAsList()) {
        when (val r = extract(m.body)) {
            is EngineResult.Parsed -> {
                val tx = r.tx
                val c = categorize(tx, overrides, pack)
                db.raseedQueries.insertTx(
                    m.id, tx.type.name, tx.amount, tx.currency, tx.merchant, tx.merchant?.let(::normalizeMerchant), tx.cardLast4,
                    tx.occurredAt?.let { "%04d-%02d-%02d %02d:%02d".format(it.year, it.month, it.day, it.hour, it.minute) },
                    c.category, c.reason.name,
                )
            }
            is EngineResult.Rejected -> Unit // informational; stays in `message` only
        }
        db.raseedQueries.markParsed(m.id)
    }
}
