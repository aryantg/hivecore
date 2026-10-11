package com.hivecore.app.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hivecore.app.HiveCoreGraph
import com.hivecore.app.MainActivity
import com.hivecore.core.SLog
import com.hivecore.ui.ports.PairPingView
import com.hivecore.ui.ports.SwarmPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Ping nearby" on the receiving phone when the app is not in front: one notification per pair request with Approve
 * and Deny as actions (they land in [PairPingReceiver]); tapping the body opens the app, where the same card sits
 * at the top of the Swarm screen. Answered or expired requests take their notification with them.
 */
class PairPingNotifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)
    private val shown = HashSet<String>()

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pair requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A phone on the same Wi-Fi asks to pair with this one"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    /** Mirrors [SwarmPort.pairPings]: a notification appears per new request and goes when the request does. */
    fun bind(port: SwarmPort, scope: CoroutineScope) {
        scope.launch {
            port.pairPings.collect { pings ->
                val ids = pings.map { it.id }.toSet()
                synchronized(shown) {
                    (shown - ids).forEach { id -> cancel(id); shown.remove(id) }
                    pings.filter { it.id !in shown }.forEach { p -> notify(p); shown += p.id }
                }
            }
        }
    }

    private fun notify(p: PairPingView) {
        val open = PendingIntent.getActivity(ctx, idFor(p.id), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun answer(approve: Boolean) = PendingIntent.getBroadcast(ctx, idFor(p.id) * 2 + (if (approve) 1 else 0), PairPingReceiver.intent(ctx, p.id, approve),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(com.hivecore.R.drawable.ic_stat_hive)
            .setContentTitle("${p.fromName} wants to pair with this phone")
            .setContentText("Same Wi-Fi. Approving shows six digits to compare on both phones.")
            .setCategory(Notification.CATEGORY_SOCIAL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Approve", answer(true)).build())
            .addAction(Notification.Action.Builder(null, "Deny", answer(false)).build())
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        try { nm.notify(idFor(p.id), n) } catch (e: Exception) { SLog.w(TAG, "notify failed: $e") }
    }

    private fun cancel(id: String) { try { nm.cancel(idFor(id)) } catch (e: Exception) { SLog.w(TAG, "cancel failed: $e") } }

    private fun idFor(id: String): Int = BASE + (id.hashCode() and 0x3FFFFF)

    companion object {
        const val CHANNEL = "hivecore.pairing"
        private const val BASE = 0x5A000000
        /** matches `LanPairPing.TTL_MS` */
        private const val TIMEOUT_MS = 2 * 60_000L
        private const val TAG = "PairPingNotifier"
    }
}

/** Approve / Deny from the notification: answers through the Swarm view model so the screen shows the same outcome. */
class PairPingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        SLog.d("PairPingReceiver", "${if (approve) "approve" else "deny"} from the notification")
        val graph = HiveCoreGraph.get(context)
        val ping = graph.swarm.state.value.pings.firstOrNull { it.id == id } ?: graph.swarmPort.pairPings.value.firstOrNull { it.id == id } ?: return
        graph.swarm.answerPing(ping, approve)
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_APPROVE = "approve"
        fun intent(ctx: Context, id: String, approve: Boolean): Intent =
            Intent(ctx, PairPingReceiver::class.java).setAction("com.hivecore.PAIR_PING_${if (approve) "APPROVE" else "DENY"}").putExtra(EXTRA_ID, id).putExtra(EXTRA_APPROVE, approve)
    }
}
