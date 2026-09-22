package app.tidybox.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.tidybox.Db
import app.tidybox.KeepRaw
import app.tidybox.RulePacks
import app.tidybox.parsePending

class ParseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        parsePending(Db.get(applicationContext), RulePacks.current(applicationContext), KeepRaw.get(applicationContext))
        return Result.success()
    }
}
