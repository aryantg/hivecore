package com.hivecore.mesh

import com.hivecore.core.LinkKind

/**
 * Spec 4.6 / 5.5 / 20.2: the profile beacon's heartbeat and stale thresholds per link class. A phone beats every
 * [LAN_INTERVAL_MS] on LAN-class links and every [WAN_INTERVAL_MS] through the relay or a WebRTC channel; a peer is
 * stale after three missed beats. `ProfileBeacon` (in `:engine`) reads these; the ladder's P2P and Bluetooth links count
 * as LAN cadence (same room), the WAN kinds as WAN cadence.
 */
object BeaconCadence {
    const val LAN_INTERVAL_MS = 15_000L
    const val LAN_STALE_MS = 45_000L
    const val WAN_INTERVAL_MS = 30_000L
    const val WAN_STALE_MS = 90_000L

    fun intervalFor(kind: LinkKind): Long = if (isWan(kind)) WAN_INTERVAL_MS else LAN_INTERVAL_MS
    fun staleFor(kind: LinkKind): Long = if (isWan(kind)) WAN_STALE_MS else LAN_STALE_MS

    /** The cadence a phone uses when it has peers over several link kinds: the slowest one, so no WAN peer sees it stale. */
    fun intervalFor(kinds: Collection<LinkKind>): Long = kinds.maxOfOrNull { intervalFor(it) } ?: LAN_INTERVAL_MS
    fun staleFor(kinds: Collection<LinkKind>): Long = kinds.maxOfOrNull { staleFor(it) } ?: LAN_STALE_MS

    private fun isWan(kind: LinkKind) = kind == LinkKind.WAN_DIRECT || kind == LinkKind.WAN_RELAY
}
