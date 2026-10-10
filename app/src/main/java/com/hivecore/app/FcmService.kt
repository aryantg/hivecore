package com.hivecore.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hivecore.core.SLog
import com.hivecore.core.SharedPrefsKv
import com.hivecore.engine.device.WorkerService

/**
 * Spec 5.5 wake: the relay sends an FCM data message `{type: wake, groupId, from}` to a member that is asleep; the
 * worker foreground service is (re)started so the relay socket comes back. The token is kept in prefs under
 * [PREF_TOKEN] and the relay transport registers it at every auth. Nothing else rides on push (the service is the
 * real mechanism; wake is a bonus).
 */
class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SharedPrefsKv(applicationContext).putString(PREF_TOKEN, token)
        SLog.d(TAG, "push token refreshed")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        if (d["type"] != "wake") return
        SLog.d(TAG, "wake from ${d["from"]?.take(8)} for ${d["groupId"]}")
        if (Features.remote) runCatching { WorkerService.start(applicationContext) }.onFailure { SLog.w(TAG, "wake: ${it.message}") }
    }

    companion object {
        const val PREF_TOKEN = "fcm_token"
        private const val TAG = "Fcm"
    }
}
