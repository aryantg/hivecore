package com.hivecore.app.approval

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import com.hivecore.core.PendingApproval
import com.hivecore.core.SLog
import com.hivecore.trust.ApprovalNotifying
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec 17 "Approve a plan on an owner phone: notification → one fingerprint for the whole plan → done". A
 * high-importance notification whose full-screen intent opens [ApprovalActivity] over the lock screen (the
 * incoming-call mechanism), plus a vibration pattern. On API 34+ the full-screen intent needs the
 * `USE_FULL_SCREEN_INTENT` special access; when the system has revoked it the notification is still posted as a
 * heads-up and [needsFullScreenPermission] turns true so Settings can offer [fullScreenSettingsIntent]. The
 * pending card the main activity shows ([ApprovalBanner]) is the fallback when neither surfaces.
 */
class ApprovalNotifier(context: Context) : ApprovalNotifying {
    private val ctx = context.applicationContext
    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)
    private val _needsFullScreenPermission = MutableStateFlow(false)

    /** True when the system currently blocks full-screen intents for this app (API 34+). Re-checked on every notify. */
    val needsFullScreenPermission: StateFlow<Boolean> = _needsFullScreenPermission.asStateFlow()

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Plan approvals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A paired phone asks for consent to use confidential data on this phone"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        refresh()
    }

    /** Re-evaluates [needsFullScreenPermission]. Returns the allowed state. */
    fun refresh(): Boolean {
        val can = canUseFullScreenIntent()
        _needsFullScreenPermission.value = !can
        return can
    }

    fun notificationsEnabled(): Boolean = nm.areNotificationsEnabled()

    /** Settings page where the user grants full-screen intents (API 34+); the app's notification settings otherwise. */
    fun fullScreenSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 34)
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
        else
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    override fun notify(pending: PendingApproval) {
        val canFullScreen = refresh()
        val open = ApprovalActivity.intent(ctx, pending.jobId)
        val pi = PendingIntent.getActivity(ctx, idFor(pending.jobId), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(com.hivecore.R.drawable.ic_stat_hive)
            .setContentTitle("${pending.requester} asks for figures from this phone")
            .setContentText(pending.description.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(pending.description))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter((pending.deadlineAt - System.currentTimeMillis()).coerceAtLeast(1_000L))
        if (canFullScreen) builder.setFullScreenIntent(pi, true)
        try {
            nm.notify(idFor(pending.jobId), builder.build())
            SLog.d(TAG, "posted approval notification job=${pending.jobId.take(8)} (enabled=${nm.areNotificationsEnabled()}, fullScreen=$canFullScreen)")
        } catch (e: Exception) {
            SLog.e(TAG, "notify failed", e)
        }
        vibrate()
    }

    override fun cancel(jobId: String) {
        try { nm.cancel(idFor(jobId)) } catch (e: Exception) { SLog.w(TAG, "cancel failed: $e") }
    }

    private fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < 34 || try { nm.canUseFullScreenIntent() } catch (e: Exception) { SLog.w(TAG, "canUseFullScreenIntent failed: $e"); false }

    private fun vibrate() {
        try {
            val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(PATTERN, -1))
        } catch (e: Exception) {
            SLog.w(TAG, "vibrate failed: $e")
        }
    }

    private fun idFor(jobId: String): Int = jobId.hashCode()

    companion object {
        const val CHANNEL = "hivecore.approvals"
        private const val TAG = "ApprovalNotifier"
        private val PATTERN = longArrayOf(0, 300, 150, 300, 150, 500)
    }
}
