package app.appglance

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Reports raw foreground/background transitions for the whole app; the client decides what they
 * mean. `ProcessLifecycleOwner` already debounces the gap between one activity stopping and the
 * next starting (a rotation, a screen change), and the client is idempotent about repeats anyway,
 * so nothing is remembered here - except by [ActivityWatch], which stands in when the host app's
 * manifest leaves `ProcessLifecycleOwner` unable to report at all.
 */
internal object LifecycleBridge : DefaultLifecycleObserver {

    /** The application [ActivityWatch] is registered on, while it stands in. Main thread only. */
    private var watching: Application? = null

    /** Safe to call from any thread and any number of times. */
    fun install(context: Context) {
        val app = context.applicationContext as? Application
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attach(app)
        } else {
            Handler(Looper.getMainLooper()).post { attach(app) }
        }
    }

    /**
     * Stops reporting. `trackAppLifecycle = false` has to mean the same thing on a second
     * `configure` as on the first: every other option is re-applied when a later `configure`
     * replaces the client, and an app that turns the flag off to drive [AppGlance.setActive] on
     * its own terms would otherwise keep getting the platform's transitions interleaved with its
     * own, with nothing in the logs to connect the two.
     */
    fun uninstall() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detach()
        } else {
            Handler(Looper.getMainLooper()).post { detach() }
        }
    }

    // Main thread only. `addObserver` is itself idempotent for the same observer.
    private fun attach(app: Application?) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(this)
        watchActivitiesIfLifecycleOwnerIsInert(lifecycle, app)
        // `configure` while the app is already in front: `addObserver` replays the lifecycle only
        // to a NEW observer, and this one has been attached since the first configure - so a
        // client created by a later configure (the documented way to apply a consent change)
        // would sit inactive until the app was backgrounded and reopened. Hand it the start it
        // missed; `setActive` is idempotent, so the paths that did get the replay are unaffected.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || ActivityWatch.started > 0) {
            AppGlance.setActive(true)
        }
    }

    private fun detach() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        watching?.unregisterActivityLifecycleCallbacks(ActivityWatch)
        watching = null
    }

    /**
     * `ProcessLifecycleOwner` reports nothing until androidx.startup's `InitializationProvider`
     * has run `ProcessLifecycleInitializer`, which happens in a content provider, before
     * `Application.onCreate`. So a registry still at INITIALIZED by the time this runs means that
     * provider is not in the merged manifest - the host app removed it, which is a documented
     * cold-start trim and one a performance tool will suggest as soon as it notices the provider
     * this SDK's dependency adds. The registry then stays inert for the life of the process:
     * `onStart` and `onStop` never fire, so there is no `session.start`, no presence ping and no
     * flush on background, while `install` and every `track` call still ship - a dashboard that is
     * quietly wrong rather than visibly empty, which nobody opens a support ticket about.
     *
     * Watching the activities directly costs one registration and covers exactly that case. If the
     * app initialises `ProcessLifecycleOwner` by hand later, both sources report and `setActive`
     * is idempotent, so the overlap is free.
     */
    private fun watchActivitiesIfLifecycleOwnerIsInert(lifecycle: Lifecycle, app: Application?) {
        if (lifecycle.currentState != Lifecycle.State.INITIALIZED || app == null || watching != null) return
        watching = app
        app.registerActivityLifecycleCallbacks(ActivityWatch)
        Log.line(
            "androidx.startup's InitializationProvider isn't in this app's manifest, so " +
                "ProcessLifecycleOwner never reports the app coming to the front. Watching activities directly " +
                "instead, so sessions and \"active right now\" still work.",
        )
    }

    override fun onStart(owner: LifecycleOwner) {
        AppGlance.setActive(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        AppGlance.setActive(false)
    }

    /**
     * The stand-in source of foreground state, used only when `ProcessLifecycleOwner` is inert:
     * one counter of started activities, and the app is in front while it is above zero. Android
     * starts the next activity before stopping the previous one, so moving between screens never
     * reaches zero. A configuration change does - the activity is destroyed and recreated - and
     * `isChangingConfigurations` is what tells that apart from the user actually leaving; the
     * recreated activity's `onStart` then finds the client already active and costs nothing.
     */
    private object ActivityWatch : Application.ActivityLifecycleCallbacks {
        var started: Int = 0
            private set

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) AppGlance.setActive(true)
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0 && !activity.isChangingConfigurations) AppGlance.setActive(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
