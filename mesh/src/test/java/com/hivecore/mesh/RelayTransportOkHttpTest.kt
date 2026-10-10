package com.hivecore.mesh

import com.hivecore.core.Inbound
import com.hivecore.core.fakes.FakeSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The OkHttp path over MockWebServer's WebSocket support: a scripted relay that issues a challenge, checks the
 * signature over the auth bytes and forwards one msg back, so the wire format is exercised end to end.
 */
class RelayTransportOkHttpTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private val serverClosed = CountDownLatch(1)

    @After fun tearDown() {
        scope.cancel()
        serverClosed.await(5, TimeUnit.SECONDS)
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test fun challengeAuthAndMessageOverARealWebSocket() = runBlocking {
        val signer = FakeSigner()
        val nodeId = Wire.nodeIdOf(signer.publicKeyB64)!!
        val peer = "p".repeat(64)
        val nonce = ByteArray(32) { (it * 7).toByte() }
        val nonceB64 = Base64.getEncoder().encodeToString(nonce)
        val seen = CopyOnWriteArrayList<RelayFrame>()

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(RelayFrames.encode(RelayFrames.challenge(nonceB64, 1_800_000_000_000L)))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { serverClosed.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val f = RelayFrames.decode(text) ?: return
                seen += f
                when (f.type) {
                    RelayProtocol.AUTH -> {
                        val ok = f.nodeId == nodeId && f.groupId == "g1" && f.rosterVersion == 4L &&
                            com.hivecore.core.Verify.verify(signer.publicKeyB64, RelayProtocol.authBytes(nonce), Base64.getDecoder().decode(f.sig!!))
                        webSocket.send(RelayFrames.encode(if (ok)
                            RelayFrame(RelayProtocol.AUTH_OK, serverTs = 1_800_000_000_100L, version = 4,
                                members = listOf(RelayMember(nodeId, true), RelayMember(peer, true)),
                                turn = TurnCredentials(listOf("turn:turn.test:3478"), "123:$nodeId", "cred", 3600))
                        else RelayFrame(RelayProtocol.AUTH_FAIL, reason = RelayProtocol.REASON_BAD_SIGNATURE)))
                    }
                    RelayProtocol.MSG -> {
                        // Forward it back as if the peer answered with the same bytes.
                        webSocket.send(RelayFrames.encode(RelayFrames.msg(nodeId, peer, f.cipher!!)))
                        webSocket.send(RelayFrames.encode(RelayFrame(RelayProtocol.SENT, id = f.id)))
                    }
                }
            }
        }))
        server.start()

        val t = RelayTransport(server.url("/ws").toString().replaceFirst("http", "ws"), signer, "g1", { 4L }, scope,
            OkHttpRelaySockets(client), pushToken = { "fcm-token" })
        val got = CopyOnWriteArrayList<Inbound>()
        scope.launch { t.inbound.collect { got += it } }
        t.start("a")
        withTimeout(10_000) { t.authenticated.first { it } }
        assertEquals(listOf(RelayTransport.endpointOf(peer)), t.connected.value)
        assertEquals("turn:turn.test:3478", t.turn.value!!.urls.single())
        assertTrue(t.clock.synced)
        assertEquals("fcm-token", seen.first { it.type == RelayProtocol.AUTH }.pushToken)

        assertTrue(t.send(RelayTransport.endpointOf(peer), "over the wire".toByteArray()).isSuccess)
        withTimeout(10_000) { while (got.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("over the wire", String(got[0].bytes))
        assertEquals(RelayTransport.endpointOf(peer), got[0].endpoint)
        t.stop()
        assertTrue(serverClosed.await(5, TimeUnit.SECONDS))
    }
}
