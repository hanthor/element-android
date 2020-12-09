/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.app.features.popup

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import com.tapadoo.alerter.Alerter
import com.tapadoo.alerter.OnHideAlertListener
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.isAnimationDisabled
import im.vector.app.features.pin.PinActivity
import im.vector.app.features.themes.ThemeUtils
import timber.log.Timber
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responsible of displaying important popup alerts on top of the screen.
 * Alerts are stacked and will be displayed sequentially but sorted by priority.
 * So if a new alert is posted with a higher priority than the current one it will show it instead and the current one
 * will be back in the queue in first position.
 */
@Singleton
class PopupAlertManager @Inject constructor() {

    companion object {
        const val INCOMING_CALL_PRIORITY = Int.MAX_VALUE
    }

    private var weakCurrentActivity: WeakReference<Activity>? = null
    private var currentAlerter: VectorAlert? = null

    private val alertQueue = mutableListOf<VectorAlert>()

    fun postVectorAlert(alert: VectorAlert) {
        synchronized(alertQueue) {
            alertQueue.add(alert)
        }
        weakCurrentActivity?.get()?.runOnUiThread {
            displayNextIfPossible()
        }
    }

    fun cancelAlert(uid: String) {
        synchronized(alertQueue) {
            alertQueue.listIterator().apply {
                while (this.hasNext()) {
                    val next = this.next()
                    if (next.uid == uid) {
                        this.remove()
                    }
                }
            }
        }

        // it could also be the current one
        if (currentAlerter?.uid == uid) {
            weakCurrentActivity?.get()?.runOnUiThread {
                Alerter.hide()
                currentIsDismissed()
            }
        }
    }

    /**
     * Cancel all alerts, after a sign out for instance
     */
    fun cancelAll() {
        synchronized(alertQueue) {
            alertQueue.clear()
        }

        // Cancel any displayed alert
        weakCurrentActivity?.get()?.runOnUiThread {
            Alerter.hide()
            currentIsDismissed()
        }
    }

    fun onNewActivityDisplayed(activity: Activity) {
        // we want to remove existing popup on previous activity and display it on new one
        if (currentAlerter != null) {
            weakCurrentActivity?.get()?.let {
                Alerter.clearCurrent(it)
                setLightStatusBar()
            }
        }
        weakCurrentActivity = WeakReference(activity)
        if (!shouldBeDisplayedIn(currentAlerter, activity)) {
            return
        }
        if (currentAlerter != null) {
            if (currentAlerter!!.expirationTimestamp != null && System.currentTimeMillis() > currentAlerter!!.expirationTimestamp!!) {
                // this alert has expired, remove it
                // perform dismiss
                try {
                    currentAlerter?.dismissedAction?.run()
                } catch (e: Exception) {
                    Timber.e("## failed to perform action")
                }
                currentAlerter = null
                Handler(Looper.getMainLooper()).postDelayed({
                    displayNextIfPossible()
                }, 2000)
            } else {
                showAlert(currentAlerter!!, activity, animate = false)
            }
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                displayNextIfPossible()
            }, 2000)
        }
    }

    private fun displayNextIfPossible() {
        val currentActivity = weakCurrentActivity?.get() ?: return
        val next: VectorAlert?
        synchronized(alertQueue) {
            next = alertQueue.maxByOrNull { it.priority }
            // If next alert with highest priority is higher than the current one, we should display it
            // and add the current one to queue again.
            if (next != null && next.priority > currentAlerter?.priority ?: Int.MIN_VALUE) {
                alertQueue.remove(next)
                currentAlerter?.also {
                    alertQueue.add(0, it)
                }
            } else {
                // otherwise, we don't do anything
                return
            }
        }
        currentAlerter = next
        next?.let {
            if (!shouldBeDisplayedIn(next, currentActivity)) return
            val currentTime = System.currentTimeMillis()
            if (next.expirationTimestamp != null && currentTime > next.expirationTimestamp!!) {
                // skip
                try {
                    next.dismissedAction?.run()
                } catch (e: java.lang.Exception) {
                    Timber.e("## failed to perform action")
                }
                displayNextIfPossible()
            } else {
                showAlert(it, currentActivity)
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun clearLightStatusBar() {
        weakCurrentActivity?.get()
                ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.M }
                // Do not change anything on Dark themes
                ?.takeIf { ThemeUtils.isLightTheme(it) }
                ?.let { it.window?.decorView }
                ?.let { view ->
                    var flags = view.systemUiVisibility
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    view.systemUiVisibility = flags
                }
    }

    @SuppressLint("InlinedApi")
    private fun setLightStatusBar() {
        weakCurrentActivity?.get()
                ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.M }
                // Do not change anything on Dark themes
                ?.takeIf { ThemeUtils.isLightTheme(it) }
                ?.let { it.window?.decorView }
                ?.let { view ->
                    var flags = view.systemUiVisibility
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    view.systemUiVisibility = flags
                }
    }

    private fun showAlert(alert: VectorAlert, activity: Activity, animate: Boolean = true) {
        clearLightStatusBar()

        val noAnimation = !animate || isAnimationDisabled(activity)

        alert.weakCurrentActivity = WeakReference(activity)
        val alerter = Alerter.create(activity, alert.layoutRes)

        alerter.setTitle(alert.title)
                .setText(alert.description)
                .also { al ->
                    al.getLayoutContainer()?.also {
                        alert.viewBinder?.bind(it)
                    }
                }
                .apply {
                    if (noAnimation) {
                        setEnterAnimation(R.anim.anim_alerter_no_anim)
                    }

                    alert.iconId?.let {
                        setIcon(it)
                    }
                    alert.actions.forEach { action ->
                        addButton(action.title, R.style.AlerterButton, View.OnClickListener {
                            if (action.autoClose) {
                                currentIsDismissed()
                                Alerter.hide()
                            }
                            try {
                                action.action.run()
                            } catch (e: java.lang.Exception) {
                                Timber.e("## failed to perform action")
                            }
                        })
                    }
                    setOnClickListener(View.OnClickListener { _ ->
                        alert.contentAction?.let {
                            currentIsDismissed()
                            Alerter.hide()
                            try {
                                it.run()
                            } catch (e: java.lang.Exception) {
                                Timber.e("## failed to perform action")
                            }
                        }
                    })
                }
                .setOnHideListener(OnHideAlertListener {
                    // called when dismissed on swipe
                    try {
                        alert.dismissedAction?.run()
                    } catch (e: java.lang.Exception) {
                        Timber.e("## failed to perform action")
                    }
                    currentIsDismissed()
                })
                .enableSwipeToDismiss()
                .enableInfiniteDuration(true)
                .apply {
                    if (alert.colorInt != null) {
                        setBackgroundColorInt(alert.colorInt!!)
                    } else if (alert.colorAttribute != null) {
                        setBackgroundColorInt(ThemeUtils.getColor(activity, alert.colorAttribute!!))
                    } else {
                        setBackgroundColorRes(alert.colorRes ?: R.color.notification_accent_color)
                    }
                }
                .enableIconPulse(!noAnimation)
                .show()
    }

    private fun currentIsDismissed() {
        // current alert has been hidden
        setLightStatusBar()

        currentAlerter = null
        Handler(Looper.getMainLooper()).postDelayed({
            displayNextIfPossible()
        }, 500)
    }

    private fun shouldBeDisplayedIn(alert: VectorAlert?, activity: Activity): Boolean {
        return alert != null
                && activity !is PinActivity
                && activity is VectorBaseActivity
                && alert.shouldBeDisplayedIn.invoke(activity)
    }
}
