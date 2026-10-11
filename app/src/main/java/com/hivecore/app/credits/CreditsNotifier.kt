package com.hivecore.app.credits

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.hivecore.app.MainActivity
import com.hivecore.core.SLog
import com.hivecore.ui.Format
import com.hivecore.ui.vm.ComputeCredits

/**
 * "You earned 42 points": one notification per settled job this phone computed for another phone (the UI-only
 * compute points, `CreditsViewModel`). Low importance, no sound: the owner reads it when they pick the phone up.
 */
class CreditsNotifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Compute points", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Points earned for the time this phone computed for other phones"
        })
    }

    fun notify(award: ComputeCredits.Award, balance: Int) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(com.hivecore.R.drawable.ic_stat_hive)
            .setContentTitle("You earned ${Format.plural(award.points, "point")}")
            .setContentText("${Format.duration(award.computeMs)} of compute for ${award.commanderName} · balance ${Format.plural(balance, "point")}")
            .setStyle(Notification.BigTextStyle().bigText(
                "${Format.duration(award.computeMs)} of compute over ${Format.plural(award.chunks, "chunk")} for ${award.commanderName}.\n" +
                    "Balance: ${Format.plural(balance, "point")}. Each request from Home costs ${ComputeCredits.PROMPT_COST}."))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(idFor(award.planId), n) } catch (e: Exception) { SLog.w(TAG, "notify failed: $e") }
    }

    private fun idFor(planId: String): Int = BASE + (planId.hashCode() and 0x3FFFFF)

    companion object {
        const val CHANNEL = "hivecore.credits"
        private const val BASE = 0x5B000000
        private const val TAG = "CreditsNotifier"
    }
}
