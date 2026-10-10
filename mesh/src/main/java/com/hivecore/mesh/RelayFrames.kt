package com.hivecore.mesh

import com.hivecore.core.Canon
import com.hivecore.core.Hashing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Base64

/**
 * Relay-level frames (spec 16.6), outside the sealed envelope; the Kotlin twin of `relayd/relay/protocol.go`. One
 * flat frame type, `type` selects the fields that matter:
 *
 * ```
 *   challenge  relay→client  {nonce, serverTs}
 *   auth       client→relay  {nodeId, groupId, rosterVersion, sig, pushToken?, invite?, pubkey?, inviter?}
 *   auth_ok    relay→client  {serverTs, members[{nodeId, online}], turn?, version}
 *   auth_fail  relay→client  {reason}
 *   roster     client→relay  {roster, adminSig}             roster_ok {groupId, version} / roster_fail {reason}
 *   msg        both          {to, from, cipher, ttlS?, id?}  sent {id, held}
 *   presence   relay→client  {nodeId, online, since}
 *   wake       client→relay  {nodeId}
 *   drop       relay→client  {to, reason, id?}
 *   ping/pong  both          {ts, serverTs?}
 *   invite     client→relay  {tokenHash, ttlS?}             invite_ok {tokenHash, ttlS} / invite_fail {reason}
 * ```
 *
 * The relay never sees plaintext: `cipher` is the sealed envelope, base64. `sig` in `auth` is the node key over
 * [RelayProtocol.authBytes]; a roster's `adminSig` is over [RelayFrames.rosterSigningBytes].
 */
@Serializable
data class RelayFrame(
    val type: String,
    val nonce: String? = null,
    val serverTs: Long? = null,
    val ts: Long? = null,
    val nodeId: String? = null,
    val groupId: String? = null,
    val rosterVersion: Long? = null,
    val sig: String? = null,
    val pushToken: String? = null,
    val invite: String? = null,
    val pubkey: String? = null,
    val inviter: String? = null,
    val tokenHash: String? = null,
    val members: List<RelayMember>? = null,
    val turn: TurnCredentials? = null,
    val version: Long? = null,
    val reason: String? = null,
    val roster: JsonElement? = null,
    val adminSig: String? = null,
    val to: String? = null,
    val from: String? = null,
    val cipher: String? = null,
    val ttlS: Int? = null,
    val id: Long? = null,
    val held: Boolean? = null,
    val online: Boolean? = null,
    val since: Long? = null,
)

@Serializable data class RelayMember(val nodeId: String, val online: Boolean)

/** coturn `use-auth-secret` credentials issued per session by the relay (spec 20.1). */
@Serializable data class TurnCredentials(val urls: List<String>, val username: String, val credential: String, val ttlS: Long)

/** Frame types, reasons and constants shared with the Go relay (`protocol.go`) and [com.hivecore.core.fakes.FakeRelay]. */
object RelayProtocol {
    const val CHALLENGE = "challenge"; const val AUTH = "auth"; const val AUTH_OK = "auth_ok"; const val AUTH_FAIL = "auth_fail"
    const val ROSTER = "roster"; const val ROSTER_OK = "roster_ok"; const val ROSTER_FAIL = "roster_fail"
    const val MSG = "msg"; const val SENT = "sent"; const val PRESENCE = "presence"; const val WAKE = "wake"; const val DROP = "drop"
    const val PING = "ping"; const val PONG = "pong"
    const val INVITE = "invite"; const val INVITE_OK = "invite_ok"; const val INVITE_FAIL = "invite_fail"

    const val REASON_EXPIRED = "expired"; const val REASON_NOT_MEMBER = "not_member"; const val REASON_REVOKED = "revoked"
    const val REASON_RATE_LIMITED = "rate_limited"; const val REASON_TOO_LARGE = "too_large"; const val REASON_QUEUE_FULL = "queue_full"
    const val REASON_BAD_FROM = "bad_from"; const val REASON_UNKNOWN_GROUP = "unknown_group"; const val REASON_BAD_SIGNATURE = "bad_signature"
    const val REASON_NOT_AUTH = "not_authenticated"; const val REASON_BAD_FRAME = "bad_frame"; const val REASON_STALE_VERSION = "stale_version"
    const val REASON_BAD_ROSTER = "bad_roster"; const val REASON_REPLACED = "replaced"; const val REASON_REMOVED = "removed_by_roster"
    const val REASON_BAD_INVITE = "bad_invite"; const val REASON_NO_PUSH_TOKEN = "no_push_token"; const val REASON_GUEST_ONLY = "guest_only"

    /** Domain prefix of the challenge signature: the signed bytes can never be a JSON envelope body. */
    const val AUTH_DOMAIN = "relayd-auth-v1"
    const val NONCE_BYTES = 32
    /** Envelope cap plus framing (protocol.go `MaxCipherBytes`). */
    const val MAX_CIPHER_BYTES = 64 * 1024 + 64
    const val HOLD_MS = 5 * 60_000L
    const val PING_EVERY_MS = 25_000L
    const val RECONNECT_MIN_MS = 1_000L
    const val RECONNECT_MAX_MS = 30_000L
    /** "WAN attempts wait up to 60 s, then fault" (spec 14, relay down). */
    const val OUTAGE_WAIT_MS = 60_000L
    /** Grace after a wake before a sleeping WAN node counts as absent (spec 5.5). */
    const val WAKE_GRACE_MS = 20_000L
    const val INVITE_TTL_MS = 10 * 60_000L
    const val LINK_ECHO_BYTES = 2048
    const val LINK_MEASURE_INTERVAL_MS = 5 * 60_000L
    const val RATE_MSGS_PER_S = 50
    const val RATE_BYTES_PER_S = 2 * 1024 * 1024

    /** `AUTH_DOMAIN || 0x00 || nonce` — what the node key signs to answer a challenge. */
    fun authBytes(nonce: ByteArray): ByteArray = AUTH_DOMAIN.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + nonce

    fun authBytes(nonceB64: String): ByteArray = authBytes(Base64.getDecoder().decode(nonceB64))

    /** `sha256(token)` in hex: what the inviter registers with the relay so a joiner can present the raw token. */
    fun tokenHash(token: String): String = Hashing.sha256Hex(token)
}

/** Codec for [RelayFrame]; lenient on unknown fields so either side can grow. */
object RelayFrames {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(f: RelayFrame): String = json.encodeToString(RelayFrame.serializer(), f)

    fun decode(text: String): RelayFrame? = try { json.decodeFromString(RelayFrame.serializer(), text) } catch (_: Exception) { null }

    fun challenge(nonceB64: String, serverTs: Long) = RelayFrame(RelayProtocol.CHALLENGE, nonce = nonceB64, serverTs = serverTs)
    fun msg(to: String, from: String, cipherB64: String, id: Long? = null, ttlS: Int? = null) =
        RelayFrame(RelayProtocol.MSG, to = to, from = from, cipher = cipherB64, id = id, ttlS = ttlS)
    fun ping(ts: Long) = RelayFrame(RelayProtocol.PING, ts = ts)
    fun wake(nodeId: String) = RelayFrame(RelayProtocol.WAKE, nodeId = nodeId)
    fun invite(token: String, ttlS: Int? = null) = RelayFrame(RelayProtocol.INVITE, tokenHash = RelayProtocol.tokenHash(token), ttlS = ttlS)

    /** The roster body as a JSON object, `adminSig` included (the relay strips it before verifying). */
    fun rosterElement(body: RosterBody): JsonObject = MeshJson.json.encodeToJsonElement(body) as JsonObject

    /** The `roster` frame carrying an admin-signed [RosterBody]. */
    fun roster(body: RosterBody): RelayFrame = RelayFrame(RelayProtocol.ROSTER, roster = rosterElement(body), adminSig = body.adminSig)

    /**
     * What the admin signs: the canonical JSON of the roster body without `adminSig` (spec 6.4, 6.6). Byte-identical
     * to `relay.CanonicalWithout(body, "adminSig")` on the relay.
     */
    fun rosterSigningBytes(body: RosterBody): ByteArray {
        val obj = rosterElement(body)
        return Canon.canonical(JsonObject(obj.filterKeys { it != "adminSig" })).toByteArray(Charsets.UTF_8)
    }
}
