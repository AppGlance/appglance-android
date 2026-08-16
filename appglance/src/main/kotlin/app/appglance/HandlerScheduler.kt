package app.appglance

import android.os.Handler

/** The production [Scheduler]: the SDK's own `HandlerThread`. */
internal class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMillis: Long) {
        handler.postDelayed(task, delayMillis)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}
