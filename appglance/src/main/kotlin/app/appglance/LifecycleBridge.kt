package app.appglance

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Reports raw foreground/background transitions for the whole app; the client decides what they
 * mean. `ProcessLifecycleOwner` already debounces the gap between one activity stopping and the
 * next starting (a rotation, a screen change), and the client is idempotent about repeats anyway,
 * so nothing here needs to remember state.
 */
internal object LifecycleBridge : DefaultLifecycleObserver {

    /** Safe to call from any thread and any number of times. */
    fun install() {
        if (Looper.myLooper() == Looper.getMainLooper()) attach() else Handler(Looper.getMainLooper()).post { attach() }
    }

    // Main thread only. `addObserver` is itself idempotent for the same observer.
    private fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        AppGlance.setActive(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        AppGlance.setActive(false)
    }
}
