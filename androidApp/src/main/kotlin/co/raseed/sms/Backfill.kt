package co.raseed.sms

import android.content.Context
import android.provider.Telephony
import co.raseed.Db
import co.raseed.isAllowedSender
import co.raseed.parsePending
import co.raseed.storeMessage

/**
 * One-time READ_SMS import (design §4 backfill). Only allowlisted senders are read from the
 * provider; everything else is skipped at the cursor, never copied.
 * ponytail: whole inbox, synchronous; "how far back" picker + progress UI when the inbox is big enough to need it.
 * @return number of messages stored.
 */
fun backfill(context: Context): Int {
    val db = Db.get(context)
    var stored = 0
    val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, cols, null, null, "${Telephony.Sms.DATE} ASC")?.use { c ->
        while (c.moveToNext()) {
            val sender = c.getString(0)
            if (!isAllowedSender(sender)) continue
            if (storeMessage(db, sender, c.getString(1) ?: continue, c.getLong(2))) stored++
        }
    }
    parsePending(db)
    return stored
}
