package co.raseed.sms

import android.content.Context
import android.provider.Telephony
import co.raseed.Db
import co.raseed.KeepRaw
import co.raseed.RulePacks
import co.raseed.Senders
import co.raseed.parsePending
import co.raseed.storeMessage

/**
 * One-time READ_SMS import (design §4 backfill). Only allowlisted senders are read from the
 * provider; everything else is skipped at the cursor, never copied.
 * @param sinceMillis only messages received at or after this instant; 0 = everything.
 * @param onProgress called with the running count of stored messages.
 * @return number of messages stored.
 */
fun backfill(context: Context, sinceMillis: Long, onProgress: (Int) -> Unit = {}): Int {
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
    parsePending(db, RulePacks.current(context), KeepRaw.get(context))
    onProgress(stored)
    return stored
}
