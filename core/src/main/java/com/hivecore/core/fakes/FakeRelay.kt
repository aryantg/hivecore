package com.hivecore.core.fakes

import com.hivecore.core.Canon
import com.hivecore.core.Hashing
import com.hivecore.core.Verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/**
 * In-process `relayd` (spec 5.3, 16.6) for JVM tests: the same frames and rules as the Go relay, on plain JSON
 * objects so `:core` needs no mesh types. Sockets are [connect]ed listeners; delivery is synchronous on the
 * caller's thread. Time comes from [clock] so the 5-minute hold, invites and rate limits run on a fake clock
 * ([sweep] expires what is due, as the relay's janitor does every second).
 *
 * Rules: challenge → `auth` signed over `AUTH_DOMAIN || 0 || nonce` against the roster; revoked and unknown ids
 * refused; `msg` forwarded only between members of one roster, never inspected; held up to [holdMs] for an offline
 * member then dropped with `drop {to, reason}` to the sender; `presence {nodeId, online, since}` to every connected
 * member; `wake {nodeId}` → [onWake] / [wakes]; `ping` → `pong {ts, serverTs}`; 50 msg/s per node; `invite` opens a
 * guest slot a joiner can use with the raw token to reach the inviter only (remote pairing, spec 6.2).
 */
class FakeRelay(private val clock: () -> Long = { System.currentTimeMillis() }, private val holdMs: Long = HOLD_MS) {
    companion object {
        const val AUTH_DOMAIN = "relayd-auth-v1"
        const val HOLD_MS = 5 * 60_000L
        const val INVITE_TTL_MS = 10 * 60_000L
        const val RATE_MSGS_PER_S = 50
        const val MAX_CIPHER_BYTES = 64 * 1024 + 64
        val json: Json = Json { ignoreUnknownKeys = true }

        fun authBytes(nonce: ByteArray): ByteArray = AUTH_DOMAIN.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + nonce
    }

    interface Listener { fun onText(text: String); fun onClosed(reason: String) }
    interface Connection { fun send(text: String); fun close() }

    data class Member(val nodeId: String, val pubkey: String, val name: String = nodeId)
    data class Wake(val groupId: String, val nodeId: String, val from: String, val token: String?)
    private data class Invite(val inviter: String, val expiresAt: Long)
    private class Group(val groupId: String, var version: Long, var admin: String, val members: LinkedHashMap<String, String>,
                        val revoked: HashSet<String>) {
        val since = HashMap<String, Long>()
        val invites = HashMap<String, Invite>()
        fun isMember(id: String) = members.containsKey(id) && id !in revoked
    }
    private class Held(val from: String, val text: String, val id: Long?, val expiresAt: Long)

    private val random = SecureRandom()
    private val groups = HashMap<String, Group>()
    private val conns = HashMap<String, Conn>()
    private val held = HashMap<String, ArrayList<Held>>()
    private val all = LinkedHashSet<Conn>()

    /** Push tokens registered at auth, nodeId → token. */
    val tokens = HashMap<String, String>()
    /** Every wake request the relay accepted (a connected target is still recorded, as "already online"). */
    val wakes: MutableList<Wake> = java.util.Collections.synchronizedList(ArrayList())
    var onWake: ((Wake) -> Unit)? = null
    /** TURN credentials to hand out in `auth_ok`, or null. */
    var turn: JsonObject? = null
    /** While true, [connect] throws and every open socket is closed: the relay is down. */
    var down: Boolean = false
        private set
    /** Every frame the relay received, for assertions on what a client sent. */
    val received: MutableList<JsonObject> = java.util.Collections.synchronizedList(ArrayList())

    // ------------------------------------------------------------------ test controls

    /** Installs a roster directly (no signature), the shortcut for tests that are not about roster upload. */
    @Synchronized
    fun roster(groupId: String, version: Long, admin: String, members: List<Member>, revoked: Set<String> = emptySet()) {
        val g = Group(groupId, version, admin, LinkedHashMap(members.associate { it.nodeId to it.pubkey }), HashSet(revoked))
        install(g)
    }

    /** The relay goes away: every socket closes, nothing can connect until [comeBack]. Rosters and held messages survive. */
    @Synchronized
    fun goDown() { down = true; killAll("relay down") }

    @Synchronized
    fun comeBack() { down = false }

    /** Closes every socket (a relay restart) without blocking reconnects. */
    @Synchronized
    fun killAll(reason: String = "restart") { all.toList().forEach { it.closeBy(reason) } }

    @Synchronized fun online(nodeId: String): Boolean = conns.containsKey(nodeId)
    @Synchronized fun heldCount(nodeId: String): Int = held[nodeId]?.size ?: 0
    @Synchronized fun socketCount(): Int = all.size
    @Synchronized fun rosterVersion(groupId: String): Long? = groups[groupId]?.version

    /** Drops expired held messages (telling the sender) and expired invites (closing their guests). */
    @Synchronized
    fun sweep() {
        val now = clock()
        for ((to, q) in held.entries.toList()) {
            val it = q.iterator()
            while (it.hasNext()) {
                val h = it.next()
                if (now < h.expiresAt) continue
                it.remove()
                conns[h.from]?.emit(frame("drop") { put("to", to); put("reason", "expired"); h.id?.let { id -> put("id", id) } })
            }
            if (q.isEmpty()) held.remove(to)
        }
        for (g in groups.values) {
            g.invites.entries.removeIf { now >= it.value.expiresAt }
            conns.values.filter { it.guest && it.groupId == g.groupId && g.invites.values.none { i -> i.inviter == it.inviter } }
                .forEach { it.closeBy("expired") }
        }
    }

    // ------------------------------------------------------------------ sockets

    @Synchronized
    fun connect(listener: Listener): Connection {
        check(!down) { "relay is down" }
        val c = Conn(listener)
        all += c
        val nonce = ByteArray(32).also { random.nextBytes(it) }
        c.nonce = nonce
        c.emit(frame("challenge") { put("nonce", Base64.getEncoder().encodeToString(nonce)); put("serverTs", clock()) })
        return c
    }

    private inner class Conn(val listener: Listener) : Connection {
        var nonce = ByteArray(0)
        var nodeId = ""; var groupId = ""; var guest = false; var inviter = ""; var authed = false
        var closed = false
        var windowStart = 0L; var inWindow = 0

        fun emit(f: JsonObject) { if (!closed) listener.onText(f.toString()) }

        override fun send(text: String) {
            val obj = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) {
                emit(frame("drop") { put("reason", "bad_frame") }); return
            }
            received += obj
            synchronized(this@FakeRelay) { if (!closed) handle(this, obj) }
        }

        override fun close() { synchronized(this@FakeRelay) { closeBy("client closed") } }

        fun closeBy(reason: String) {
            if (closed) return
            closed = true
            all.remove(this)
            if (authed && conns[nodeId] === this) {
                conns.remove(nodeId)
                if (!guest) groups[groupId]?.let { g -> g.since[nodeId] = clock(); presence(g, nodeId, false) }
            }
            listener.onClosed(reason)
        }
    }

    // ------------------------------------------------------------------ frames

    private fun frame(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject { put("type", type); build() }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull

    private fun handle(c: Conn, f: JsonObject) {
        when (val type = f.str("type")) {
            "ping" -> c.emit(frame("pong") { f.long("ts")?.let { put("ts", it) }; put("serverTs", clock()) })
            "pong" -> {}
            "auth" -> auth(c, f)
            "roster" -> applyRoster(c, f)
            else -> {
                if (!c.authed) { c.emit(frame("drop") { put("reason", "not_authenticated") }); return }
                when (type) {
                    "msg" -> forward(c, f)
                    "wake" -> wake(c, f)
                    "invite" -> openInvite(c, f)
                    else -> c.emit(frame("drop") { put("reason", "bad_frame") })
                }
            }
        }
    }

    private fun auth(c: Conn, f: JsonObject) {
        fun fail(reason: String) { c.emit(frame("auth_fail") { put("reason", reason) }); c.closeBy(reason) }
        if (c.authed) return fail("bad_frame")
        val g = groups[f.str("groupId") ?: ""] ?: return fail("unknown_group")
        val nodeId = f.str("nodeId") ?: return fail("bad_frame")
        val sig = Base64.getDecoder().decode(f.str("sig") ?: "")
        val invite = f.str("invite")
        if (invite != null) {
            val pubkey = f.str("pubkey") ?: return fail("bad_invite")
            if (nodeIdOf(pubkey) != nodeId) return fail("bad_invite")
            val inv = g.invites[Hashing.sha256Hex(invite)]
            if (inv == null || clock() >= inv.expiresAt || (f.str("inviter")?.let { it != inv.inviter } == true)) return fail("bad_invite")
            if (!Verify.verify(pubkey, authBytes(c.nonce), sig)) return fail("bad_signature")
            if (g.isMember(nodeId)) return fail("bad_invite")
            c.guest = true; c.inviter = inv.inviter
        } else {
            if (nodeId in g.revoked) return fail("revoked")
            val pubkey = g.members[nodeId] ?: return fail("not_member")
            if (!Verify.verify(pubkey, authBytes(c.nonce), sig)) return fail("bad_signature")
        }
        c.nodeId = nodeId; c.groupId = g.groupId; c.authed = true
        val old = conns[nodeId]
        if (old != null) { conns.remove(nodeId); old.closeBy("replaced") } else if (!c.guest) g.since[nodeId] = clock()
        conns[nodeId] = c
        f.str("pushToken")?.let { if (!c.guest) tokens[nodeId] = it }
        c.emit(frame("auth_ok") {
            put("serverTs", clock()); put("version", g.version)
            turn?.let { put("turn", it) }
            val members = if (c.guest) listOf(c.inviter) else g.members.keys.filter { it !in g.revoked }
            put("members", JsonArray(members.map { id -> buildJsonObject { put("nodeId", id); put("online", conns.containsKey(id)) } }))
        })
        if (!c.guest) {
            if (old == null) presence(g, nodeId, true)
            held.remove(nodeId)?.forEach { c.emit(json.parseToJsonElement(it.text).jsonObject) }
        }
    }

    private fun presence(g: Group, nodeId: String, online: Boolean) {
        val f = frame("presence") { put("nodeId", nodeId); put("online", online); put("since", g.since[nodeId] ?: clock()) }
        conns.values.filter { it.groupId == g.groupId && !it.guest && it.nodeId != nodeId }.forEach { it.emit(f) }
    }

    private fun forward(c: Conn, f: JsonObject) {
        val to = f.str("to") ?: ""; val id = f.long("id")
        fun drop(reason: String) = c.emit(frame("drop") { put("to", to); put("reason", reason); id?.let { put("id", it) } })
        if (f.str("from") != c.nodeId) return drop("bad_from")
        val cipher = f.str("cipher") ?: ""
        if (to.isEmpty() || cipher.length / 4 * 3 > MAX_CIPHER_BYTES) return drop("too_large")
        val second = clock() / 1000
        if (second != c.windowStart) { c.windowStart = second; c.inWindow = 0 }
        if (++c.inWindow > RATE_MSGS_PER_S) return drop("rate_limited")
        val g = groups[c.groupId] ?: return drop("unknown_group")
        val target = conns[to]
        val targetGuest = target != null && target.guest && target.groupId == c.groupId && target.inviter == c.nodeId
        if (c.guest && to != c.inviter) return drop("guest_only")
        if (!c.guest && !targetGuest && !g.isMember(to)) return drop("not_member")
        val out = frame("msg") { put("to", to); put("from", c.nodeId); put("cipher", cipher); id?.let { put("id", it) } }
        if (target != null && target.groupId == c.groupId) {
            target.emit(out)
            if (id != null) c.emit(frame("sent") { put("id", id); put("held", false) })
            return
        }
        if (c.guest || targetGuest) return drop("not_member")
        val ttl = f["ttlS"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { minOf(it * 1000L, holdMs) } ?: holdMs
        held.getOrPut(to) { ArrayList() } += Held(c.nodeId, out.toString(), id, clock() + ttl)
        if (id != null) c.emit(frame("sent") { put("id", id); put("held", true) })
    }

    private fun wake(c: Conn, f: JsonObject) {
        val target = f.str("nodeId") ?: ""
        fun drop(reason: String) = c.emit(frame("drop") { put("to", target); put("reason", reason) })
        if (c.guest) return drop("guest_only")
        val g = groups[c.groupId] ?: return drop("unknown_group")
        if (!g.isMember(target)) return drop("not_member")
        if (conns.containsKey(target)) return
        val w = Wake(g.groupId, target, c.nodeId, tokens[target])
        wakes += w
        onWake?.invoke(w)
        if (w.token == null) drop("no_push_token")
    }

    private fun openInvite(c: Conn, f: JsonObject) {
        val hash = f.str("tokenHash") ?: ""
        if (c.guest || hash.length != 64) { c.emit(frame("invite_fail") { put("reason", "bad_invite") }); return }
        val ttl = f["ttlS"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { minOf(it * 1000L, INVITE_TTL_MS) } ?: INVITE_TTL_MS
        groups[c.groupId]?.invites?.put(hash, Invite(c.nodeId, clock() + ttl))
        c.emit(frame("invite_ok") { put("tokenHash", hash); put("ttlS", (ttl / 1000).toInt()) })
    }

    // ------------------------------------------------------------------ roster upload

    private fun nodeIdOf(pubkeyB64: String): String? = try { Hashing.sha256Hex(Base64.getDecoder().decode(pubkeyB64)) } catch (_: Exception) { null }

    private fun applyRoster(c: Conn, f: JsonObject) {
        fun fail(reason: String) = c.emit(frame("roster_fail") { put("reason", reason) })
        val body = f["roster"] as? JsonObject ?: return fail("bad_roster")
        val adminSig = f.str("adminSig") ?: body.str("adminSig") ?: return fail("bad_roster")
        val groupId = body.str("groupId") ?: return fail("bad_roster")
        val version = body.long("version")?.takeIf { it > 0 } ?: return fail("bad_roster")
        val admin = body.str("admin") ?: return fail("bad_roster")
        val members = LinkedHashMap<String, String>()
        for (m in (body["members"] as? JsonArray ?: return fail("bad_roster"))) {
            val o = m as? JsonObject ?: return fail("bad_roster")
            val id = o.str("nodeId") ?: return fail("bad_roster"); val pk = o.str("pubkey") ?: return fail("bad_roster")
            if (nodeIdOf(pk) != id || members.containsKey(id)) return fail("bad_roster")
            members[id] = pk
        }
        if (members.isEmpty() || !members.containsKey(admin)) return fail("bad_roster")
        val revoked = (body["revoked"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toHashSet() ?: HashSet()
        val heldGroup = groups[groupId]
        val signer = heldGroup?.admin ?: admin
        val key = members[signer] ?: heldGroup?.members?.get(signer) ?: return fail("bad_signature")
        val canonical = Canon.canonical(JsonObject(body.filterKeys { it != "adminSig" })).toByteArray(Charsets.UTF_8)
        if (!Verify.verify(key, canonical, Base64.getDecoder().decode(adminSig))) return fail("bad_signature")
        if (heldGroup != null && version < heldGroup.version) return fail("stale_version")
        c.emit(frame("roster_ok") { put("groupId", groupId); put("version", version) })
        if (heldGroup != null && version == heldGroup.version) return
        install(Group(groupId, version, admin, members, revoked))
    }

    private fun install(g: Group) {
        val prev = groups[g.groupId]
        if (prev != null) { g.since.putAll(prev.since); g.invites.putAll(prev.invites) }
        groups[g.groupId] = g
        conns.values.filter { it.groupId == g.groupId && !it.guest && !g.isMember(it.nodeId) }.toList().forEach { mc ->
            held.remove(mc.nodeId)
            mc.closeBy(if (mc.nodeId in g.revoked) "revoked" else "removed_by_roster")
        }
        held.keys.removeIf { id -> groups.values.none { it.isMember(id) } }
    }
}
