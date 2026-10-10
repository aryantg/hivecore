package com.hivecore.ui

import com.hivecore.core.LinkKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure text formatting shared by the screens and the view models (JVM-tested, no Android imports). */
object Format {
    /** 1265 → "1,265". */
    fun count(n: Long): String {
        val neg = n < 0
        val digits = kotlin.math.abs(n).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return (if (neg) "-" else "") + sb
    }

    fun count(n: Int): String = count(n.toLong())

    /** "420 ms", "1.2 s", "1 min 40 s". */
    fun duration(ms: Long): String = when {
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
        else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
    }

    /** "512 B", "3.3 MB", "1.2 GB". */
    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", n / (1024.0 * 1024.0 * 1024.0))
    }

    /** Rupees: "₹0", "₹1.20", "₹500". */
    fun inr(amount: Double): String = if (amount == Math.rint(amount)) "₹${amount.toLong()}" else String.format(Locale.US, "₹%.2f", amount)

    /** PowerManager thermal status 0..6 → the word on the tile. */
    fun thermalWord(level: Int): String = when (level) {
        0 -> "Cool"; 1 -> "Warm"; 2 -> "Hot"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; else -> "Shutdown"
    }

    /** "33 °C" or null. */
    fun temperature(c: Float?): String? = c?.let { String.format(Locale.US, "%.0f °C", it) }

    /** Spec section 17 Swarm tile: "online via LAN / Nearby / Bluetooth / direct / relay". */
    fun linkWord(kind: LinkKind?): String = when (kind) {
        LinkKind.LAN, null -> "LAN"
        LinkKind.P2P -> "Nearby"
        LinkKind.BT -> "Bluetooth"
        LinkKind.WAN_DIRECT -> "direct"
        LinkKind.WAN_RELAY -> "relay"
    }

    /** "12 s ago", "3 min ago", "2 h ago". */
    fun ago(ms: Long): String = when {
        ms < 0 -> "just now"
        ms < 60_000 -> "${ms / 1000} s ago"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        else -> "${ms / 3_600_000} h ago"
    }

    /** Short hash for a label: the first 12 hex characters. */
    fun shortHash(h: String?): String = h?.take(12) ?: "—"

    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateTimeFmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
    fun clock(ts: Long): String = synchronized(clockFmt) { clockFmt.format(Date(ts)) }
    fun dateTime(ts: Long): String = synchronized(dateTimeFmt) { dateTimeFmt.format(Date(ts)) }

    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}
