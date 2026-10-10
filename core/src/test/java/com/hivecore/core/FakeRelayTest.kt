package com.hivecore.core

import com.hivecore.core.fakes.FakeRelay
import com.hivecore.core.fakes.FakeSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The relay rules on raw frames, so other tracks can rely on the fake without the mesh module. */
class FakeRelayTest {
    private var now = 1_791_633_600_000L
    private val relay = FakeRelay(clock = { now })

    inner class Node(val name: String) {
        val signer = FakeSigner()
        val id = Hashing.sha256Hex(Base64.getDecoder().decode(signer.publicKeyB64))
        val member = FakeRelay.Member(id, signer.publicKeyB64, name)
        val frames = ArrayList<JsonObject>()
        var closedReason: String? = null
        lateinit var conn: FakeRelay.Connection
        fun connect() {
            conn = relay.connect(object : FakeRelay.Listener {
                override fun onText(text: String) { frames += Json.parseToJsonElement(text).jsonObject }
                override fun onClosed(reason: String) { closedReason = reason }
            })
        }
        fun auth(group: String = "g1", token: String? = null, signWith: FakeSigner = signer) {
            val nonce = Base64.getDecoder().decode(frames.last { it.type == "challenge" }.str("nonce")!!)
            val sig = Base64.getEncoder().encodeToString(signWith.sign(FakeRelay.authBytes(nonce)))
            send(buildJsonObject { put("type", "auth"); put("nodeId", id); put("groupId", group); put("rosterVersion", 1); put("sig", sig); token?.let { put("pushToken", it) } })
        }
        fun send(f: JsonObject) = conn.send(f.toString())
        fun msg(to: String, cipher: String = "AAEC", id: Long? = null) =
            send(buildJsonObject { put("type", "msg"); put("to", to); put("from", this@Node.id); put("cipher", cipher); id?.let { put("id", it) } })
        fun last(type: String) = frames.last { it.type == type }
        fun count(type: String) = frames.count { it.type == type }
    }

    private val JsonObject.type get() = str("type")
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    @Test fun authAgainstTheRosterAndRefusals() {
        val a = Node("a"); val b = Node("b"); val x = Node("x")
        relay.roster("g1", 1, a.id, listOf(a.member, b.member), revoked = setOf(b.id))
        a.connect(); a.auth()
        val ok = a.last("auth_ok")
        assertEquals(now, ok.str("serverTs")!!.toLong())
        assertEquals("[{\"nodeId\":\"${a.id}\",\"online\":true}]", ok["members"].toString())
        assertTrue(relay.online(a.id))

        b.connect(); b.auth()
        assertEquals("revoked", b.last("auth_fail").str("reason")); assertEquals("revoked", b.closedReason)
        x.connect(); x.auth()
        assertEquals("not_member", x.last("auth_fail").str("reason"))
        val y = Node("y"); y.connect(); y.auth(group = "nope")
        assertEquals("unknown_group", y.last("auth_fail").str("reason"))
        val z = Node("a2"); z.connect(); z.auth(signWith = b.signer)
        assertEquals("not_member", z.last("auth_fail").str("reason"))
        val w = Node("w"); relay.roster("g1", 2, a.id, listOf(a.member, w.member))
        w.connect(); w.auth(signWith = x.signer)
        assertEquals("bad_signature", w.last("auth_fail").str("reason"))
    }

    @Test fun forwardingIsScopedHeldAndDropped() {
        val a = Node("a"); val b = Node("b"); val c = Node("c")
        relay.roster("g1", 1, a.id, listOf(a.member, b.member))
        relay.roster("g2", 1, c.id, listOf(c.member))
        a.connect(); a.auth(); b.connect(); b.auth(); c.connect(); c.auth()
        assertEquals("true", a.last("presence")["online"].toString())

        a.msg(b.id, "Zm9v", id = 1)
        assertEquals("Zm9v", b.last("msg").str("cipher")); assertEquals(a.id, b.last("msg").str("from"))
        assertEquals("false", a.last("sent")["held"].toString())
        a.msg(c.id)
        assertEquals("not_member", a.last("drop").str("reason"))
        assertEquals(0, c.count("msg"))

        b.conn.close()
        assertEquals("false", a.last("presence")["online"].toString())
        a.msg(b.id, "aGVsZA==", id = 2)
        assertEquals("true", a.last("sent")["held"].toString())
        assertEquals(1, relay.heldCount(b.id))
        now += 4 * 60_000; relay.sweep()
        assertEquals(1, relay.heldCount(b.id))
        b.connect(); b.auth()
        assertEquals("aGVsZA==", b.last("msg").str("cipher"))
        assertEquals(0, relay.heldCount(b.id))

        b.conn.close()
        a.msg(b.id, "bGF0ZQ==", id = 3)
        now += 5 * 60_000 + 1; relay.sweep()
        val drop = a.last("drop")
        assertEquals("expired", drop.str("reason")); assertEquals(b.id, drop.str("to")); assertEquals("3", drop["id"].toString())
        assertEquals(0, relay.heldCount(b.id))
    }

    @Test fun wakeRateLimitAndRelayDown() {
        val a = Node("a"); val b = Node("b")
        relay.roster("g1", 1, a.id, listOf(a.member, b.member))
        a.connect(); a.auth(); b.connect(); b.auth(token = "tok-b"); b.conn.close()
        val woken = ArrayList<FakeRelay.Wake>()
        relay.onWake = { woken += it }
        a.send(buildJsonObject { put("type", "wake"); put("nodeId", b.id) })
        assertEquals(listOf(FakeRelay.Wake("g1", b.id, a.id, "tok-b")), woken)

        repeat(55) { a.msg(b.id) }
        assertEquals(5, a.frames.count { it.type == "drop" && it.str("reason") == "rate_limited" })
        now += 1_000
        a.msg(b.id)
        assertEquals(5, a.frames.count { it.type == "drop" && it.str("reason") == "rate_limited" })

        relay.goDown()
        assertEquals("relay down", a.closedReason)
        assertFalse(relay.online(a.id))
        val threw = runCatching { a.connect() }.isFailure
        assertTrue(threw)
        relay.comeBack(); a.connect(); a.auth()
        assertTrue(relay.online(a.id))
        assertNull(a.closedReason.takeIf { relay.online(a.id) && a.frames.last().type != "auth_ok" })
    }

    @Test fun pingCarriesRelayTime() {
        val a = Node("a")
        relay.roster("g1", 1, a.id, listOf(a.member))
        a.connect(); a.auth()
        now += 777
        a.send(buildJsonObject { put("type", "ping"); put("ts", 5) })
        val pong = a.last("pong")
        assertEquals("5", pong["ts"].toString()); assertEquals(now.toString(), pong["serverTs"].toString())
    }
}
