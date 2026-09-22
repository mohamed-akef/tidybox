package co.raseed.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import co.raseed.Db
import co.raseed.Senders
import co.raseed.storeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Design §4. The process may die the moment onReceive returns, so this does one cheap thing —
 * allowlist check + one insert under goAsync() — and hands parsing to WorkManager, which survives.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = parts.firstOrNull()?.displayOriginatingAddress
        val allowed = Senders.get(context)
        if (!Senders.allows(allowed, sender)) return // dropped: not stored, not parsed, not hashed
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val receivedAt = parts.first().timestampMillis

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (storeMessage(Db.get(context), allowed, sender, body, receivedAt)) {
                    WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ParseWorker>().build())
                }
            } finally {
                pending.finish()
            }
        }
    }
}
