package co.raseed.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.raseed.Db
import co.raseed.KeepRaw
import co.raseed.RulePacks
import co.raseed.parsePending

class ParseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        parsePending(Db.get(applicationContext), RulePacks.current(applicationContext), KeepRaw.get(applicationContext))
        return Result.success()
    }
}
