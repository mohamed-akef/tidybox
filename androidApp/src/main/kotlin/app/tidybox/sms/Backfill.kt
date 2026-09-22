package app.tidybox.sms

import android.content.Context
import android.provider.Telephony
import app.tidybox.Db
import app.tidybox.KeepRaw
import app.tidybox.RulePacks
import app.tidybox.Senders
import app.tidybox.parsePending
import app.tidybox.reparseUnread
import app.tidybox.storeMessage

/**
 * One-time READ_SMS import (design §4 backfill). Only allowlisted senders are read from the
 * provider; everything else is skipped at the cursor, never copied.
 * @param sinceMillis only messages received at or after this instant; 0 = everything.
 * @param onProgress called with the running count of stored messages.
 * @return messages stored to transactions found. Equal-ish means the templates fit this bank;
 *   N to 0 means the sender is allowlisted but its message shape is unknown to the engine.
 */
fun backfill(context: Context, sinceMillis: Long, onProgress: (Int) -> Unit = {}): Pair<Int, Int> {
    val db = Db.get(context)
    val allowed = Senders.get(context)
    var stored = 0
    val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
    context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI, cols, "${Telephony.Sms.DATE} >= ?", arrayOf(sinceMillis.toString()), "${Telephony.Sms.DATE} ASC",
    )?.use { c ->
        while (c.moveToNext()) {
            val sender = c.getString(0) ?: continue
            if (!storeMessage(db, allowed, sender, c.getString(1) ?: continue, c.getLong(2))) continue
            stored++
            if (stored % 25 == 0) onProgress(stored)
        }
    }
    val pack = RulePacks.current(context)
    val keepRaw = KeepRaw.get(context)
    val found = reparseUnread(db, pack, keepRaw) + parsePending(db, pack, keepRaw)
    onProgress(stored)
    return stored to found
}

private val PHONE_NUMBER = Regex("^\\+?[\\d ()-]{7,}$")

/**
 * Alphanumeric sender IDs present in the phone's inbox, minus those already allowlisted.
 * Reads the ADDRESS column only — no bodies — and stores nothing; the user picks from the
 * list in Settings. This is the deliberate, user-initiated relaxation of design §2.2 that lets
 * the app work in any country without a hardcoded bank list (decisions D8).
 * Numeric addresses are people; they never appear.
 */
fun seenSenders(context: Context): List<String> {
    val allowed = Senders.get(context).map(Senders::key).toSet()
    val seen = LinkedHashSet<String>()
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
        while (c.moveToNext()) {
            val a = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (!PHONE_NUMBER.matches(a) && Senders.key(a) !in allowed) seen += a
        }
    }
    return seen.toList()
}
