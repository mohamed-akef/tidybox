package app.tidybox

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tidybox.db.TidyBoxDb
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import app.tidybox.engine.CategoryReason
import app.tidybox.engine.EngineResult
import app.tidybox.engine.RulePack
import app.tidybox.engine.Transaction
import app.tidybox.engine.TxType
import app.tidybox.engine.categorize
import app.tidybox.engine.extract
import app.tidybox.engine.normalizeMerchant
import java.security.MessageDigest

/**
 * Sender allowlist — the privacy enforcement point (design §2.2). Anything not here is dropped
 * in the receiver before it is stored, parsed or hashed. Seeded with Saudi bank IDs; the user
 * edits it in Settings and the edited set is what the receiver consults.
 */
val DEFAULT_SENDERS = setOf(
    "AlRajhiBank", "Alinma", "AlinmaBank", "SNB", "AlAhli", "SABB", "Riyad Bank", "RiyadBank", "ANB",
    "BSF", "AlBilad", "Bank AlBilad", "AlJazira", "BankAlJazira", "stc pay", "stcpay", "STC Bank", "D360",
    // Egypt — observed on a device. Seeds are only ever IDs seen on a real phone; the
    // "Scan phone" picker in Settings covers every other bank and country.
    "CIB", "KFH Egypt", "VF-Cash",
)

object Senders {
    private const val PREFS = "tidybox-senders"
    private const val KEY = "allow"
    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null) ?: DEFAULT_SENDERS
    fun set(context: Context, senders: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, senders).commit()
    }
    /** The one matching rule. Everything that can persist a message routes through here. */
    /** Case, spaces and dashes are presentation ("Riyad Bank" vs "RiyadBank"). Still an exact
     *  ID match: "CIB" must NOT admit "CIB OTP", or OTP bodies would be stored under Keep raw. */
    fun key(id: String): String = id.lowercase().filterNot { it == ' ' || it == '-' || it == '_' }
    fun allows(allowed: Set<String>, sender: String?): Boolean =
        sender != null && allowed.any { key(it) == key(sender) }
}

/** The active merchant pack: a user-imported JSON if one parsed, else the bundled one (design §6). */
object RulePacks {
    private const val PREFS = "tidybox-rulepack"
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

/** O4. Default keep: enables re-parse after a rule-pack update, "why this category", and backup. */
/** Whether the one-time automatic history import has run. */
object Imported {
    private const val PREFS = "tidybox-privacy"
    private const val KEY = "auto_imported"
    fun get(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).commit() }
}

object KeepRaw {
    private const val PREFS = "tidybox-privacy"
    private const val KEY = "keep_raw"
    fun get(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    /** Turning it off erases what is already stored; turning it back on cannot bring it back. */
    fun set(context: Context, keep: Boolean, db: TidyBoxDb) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, keep).commit()
        if (!keep) db.tidyBoxQueries.blankBodies()
    }
}

object Db {
    @Volatile private var instance: TidyBoxDb? = null
    fun get(context: Context): TidyBoxDb = instance ?: synchronized(this) {
        instance ?: run {
            System.loadLibrary("sqlcipher")
            val app = context.applicationContext
            // Design §3: SQLCipher, key held in the Android Keystore. The passphrase is cleared by the factory after open.
            val factory = SupportOpenHelperFactory(dbPassphrase(app))
            TidyBoxDb(AndroidSqliteDriver(TidyBoxDb.Schema, app, "tidybox.db", factory = factory)).also { instance = it }
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
fun storeMessage(db: TidyBoxDb, allowed: Set<String>, sender: String?, body: String, receivedAt: Long): Boolean {
    if (!Senders.allows(allowed, sender)) return false
    db.tidyBoxQueries.insertMessage(sha256(sender!!, body, receivedAt.toString()), sender, body, receivedAt)
    return true
}

/** The user's correction: persists as a rule and rewrites every row of that merchant. */
fun correct(db: TidyBoxDb, merchantKey: String, category: String) {
    db.tidyBoxQueries.upsertRule(merchantKey, category)
    db.tidyBoxQueries.applyRule(category, merchantKey)
}

/** Re-categorize every transaction with the given pack. User overrides are kept as they are. */
fun recategorizeAll(db: TidyBoxDb, pack: RulePack) {
    val overrides = db.tidyBoxQueries.rules().executeAsList().associate { it.merchant_key to it.category }
    db.transaction {
        for (t in db.tidyBoxQueries.allTxForRecategorize().executeAsList()) {
            if (t.category_reason == CategoryReason.OVERRIDE.name) continue
            val c = categorize(Transaction(TxType.valueOf(t.type), 0.0, "SAR", t.merchant, null, null, null), overrides, pack)
            db.tidyBoxQueries.setCategory(c.category, c.reason.name, t.id)
        }
    }
}

/**
 * Parse everything stored but not yet parsed. Pure engine in, rows out. Safe to run any time.
 *
 * Neither parameter is defaulted, on purpose. Both defaults were right at two call sites and
 * silently wrong at the third: `pack` made a restore categorize with the bundled dictionary, and
 * `keepRaw = true` made a restore re-populate raw message bodies the user had chosen to erase —
 * reversing a privacy setting with no error and no UI. The compiler is the only thing that
 * reliably notices the next call site.
 */
fun parsePending(db: TidyBoxDb, pack: RulePack, keepRaw: Boolean): Int {
    var found = 0
    val overrides = db.tidyBoxQueries.rules().executeAsList().associate { it.merchant_key to it.category }
    for (m in db.tidyBoxQueries.unparsed().executeAsList()) {
        when (val r = extract(m.body)) {
            is EngineResult.Parsed -> {
                val tx = r.tx
                val c = categorize(tx, overrides, pack)
                db.tidyBoxQueries.insertTx(
                    m.id, tx.type.name, tx.amount, tx.currency, tx.merchant, tx.merchant?.let(::normalizeMerchant), tx.cardLast4,
                    tx.occurredAt?.let { "%04d-%02d-%02d %02d:%02d".format(it.year, it.month, it.day, it.hour, it.minute) },
                    c.category, c.reason.name,
                )
                found++
            }
            is EngineResult.Rejected -> Unit // informational; stays in `message` only
        }
        db.tidyBoxQueries.markParsed(m.id)
    }
    // Runs on every parse, not just when the switch flips: a message that arrived while the app
    // was closed must not keep its body either. `body != ''` in the query makes the repeat a no-op.
    if (!keepRaw) db.tidyBoxQueries.blankBodies()
    return found
}

/**
 * Messages the engine could not read, digits masked, for pasting into a bug report. The user
 * decides to share; the app only makes the sample easy to produce. Card and account numbers
 * become `0`s; amounts are kept because they are what a template has to find.
 */
fun unreadableSample(db: TidyBoxDb, limit: Long = 20): String =
    db.tidyBoxQueries.unreadable(limit).executeAsList().joinToString("\n\n") { m ->
        "[" + m.sender + "]\n" + m.body.replace(Regex("\\d{4,}")) { "0".repeat(it.value.length) }
    }
