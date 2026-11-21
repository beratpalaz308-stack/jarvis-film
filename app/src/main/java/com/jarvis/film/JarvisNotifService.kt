package com.jarvis.film

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class Msg(
    val pkg: String,
    val from: String,
    val text: String,
    val pi: PendingIntent?,
    val ri: RemoteInput?,
    val time: Long
)

class JarvisNotifService : NotificationListenerService() {

    companion object {
        @Volatile var instance: JarvisNotifService? = null
        private val inbox = ArrayList<Msg>()
        private val pkgs = setOf(
            "com.instagram.android", "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
            "com.facebook.orca", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.android.mms"
        )

        fun snapshot(): List<Msg> = synchronized(inbox) { ArrayList(inbox) }

        fun reply(ctx: Context, m: Msg, text: String): Boolean {
            val pi = m.pi ?: return false
            val ri = m.ri ?: return false
            return try {
                val intent = Intent()
                val b = Bundle()
                b.putCharSequence(ri.resultKey, text)
                RemoteInput.addResultsToIntent(arrayOf(ri), intent, b)
                pi.send(ctx, 0, intent)
                synchronized(inbox) { inbox.remove(m) }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
        try {
            val act = activeNotifications
            if (act != null) for (s in act) ingest(s)
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) ingest(sbn)
    }

    private fun ingest(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in pkgs) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras ?: return
        val from = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        var pi: PendingIntent? = null
        var ri: RemoteInput? = null
        val acts = n.actions
        if (acts != null) {
            for (a in acts) {
                val r = a.remoteInputs
                if (r != null && r.isNotEmpty()) {
                    pi = a.actionIntent
                    ri = r[0]
                    break
                }
            }
        }
        synchronized(inbox) {
            val last = inbox.lastOrNull()
            if (last != null && last.from == from && last.text == text) return
            inbox.add(Msg(pkg, from, text, pi, ri, System.currentTimeMillis()))
            while (inbox.size > 50) inbox.removeAt(0)
        }
    }
}
