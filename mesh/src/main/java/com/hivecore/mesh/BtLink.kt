package com.hivecore.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.hivecore.core.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * One open RFCOMM stream to a bonded phone. [BtRfcommTransport] writes framed envelopes to [output] and reads them
 * from [input]; it never touches the socket. Keeping the socket behind this interface is what lets the framing and
 * reconnect logic run on the JVM over piped streams (see FakeBtEndpoint in the test source set).
 */
interface BtLink {
    val input: InputStream
    val output: OutputStream
    /** Bluetooth MAC of the other side; the `endpointId` of the [com.hivecore.core.PeerEndpoint]. */
    val remoteAddress: String
    /** Settings-level device name of the other side (falls back to the address). */
    val remoteName: String
    fun close()
}

/** Where links come from: the server socket (accept) and the bonded-device list (connect). */
interface BtEndpoint {
    /** Blocks until a remote phone connects to our service record. Throws [IOException] when Bluetooth is off or closed. */
    suspend fun accept(): BtLink
    /** One connection attempt to [address]; null when it refuses (a bonded laptop or earbud does, which is normal). */
    suspend fun connect(address: String): BtLink?
    /** Devices bonded in Settings as (address, name). */
    fun bonded(): List<Pair<String, String>>
    /** Whether the radio is usable right now (false when airplane mode switched Bluetooth off). */
    fun available(): Boolean = true
    /** Closes the server socket; a pending [accept] fails with [IOException]. The next [accept] listens again. */
    fun close()
}

class AndroidBtLink(private val socket: BluetoothSocket) : BtLink {
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override val remoteAddress: String = socket.remoteDevice?.address ?: "?"
    override val remoteName: String = try { socket.remoteDevice?.name } catch (_: SecurityException) { null } ?: remoteAddress
    override fun close() {
        try { socket.close() } catch (e: IOException) { SLog.d("BtLink", "close $remoteAddress: $e") }
    }
}

/**
 * Framework Bluetooth adapter. Needs `BLUETOOTH_CONNECT` granted and the phones bonded in Settings beforehand
 * (plan 2.3). The server socket is opened lazily on the first [accept] and re-opened after an error, so a
 * Bluetooth off/on cycle only costs one failed accept.
 */
class AndroidBtEndpoint(private val adapter: BluetoothAdapter) : BtEndpoint {
    companion object {
        const val SERVICE_NAME = "HiveCoreNode"
        val SERVICE_UUID: UUID = UUID.fromString("7a0b5a1e-4c3d-4e6f-9b21-5f0c2a1d9e01")
    }

    private val lock = Any()
    private var server: BluetoothServerSocket? = null

    private fun serverSocket(): BluetoothServerSocket = synchronized(lock) {
        server ?: adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID).also {
            server = it
            SLog.d("BtEndpoint", "listening as $SERVICE_NAME")
        }
    }

    override suspend fun accept(): BtLink = withContext(Dispatchers.IO) {
        val ss = try { serverSocket() } catch (e: SecurityException) { throw IOException("BLUETOOTH_CONNECT not granted", e) }
        try {
            AndroidBtLink(ss.accept())
        } catch (e: IOException) {
            // The server socket is dead (Bluetooth went off, or close()); drop it so the next accept re-listens.
            synchronized(lock) { if (server === ss) server = null }
            try { ss.close() } catch (_: IOException) {}
            throw e
        }
    }

    override suspend fun connect(address: String): BtLink? = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        try {
            val device = adapter.getRemoteDevice(address)
            socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            adapter.cancelDiscovery()
            socket.connect()
            AndroidBtLink(socket)
        } catch (e: IOException) {
            // Normal for every bonded device that is not a HiveCore phone; keep it at debug level and short.
            SLog.d("BtEndpoint", "connect $address refused: ${e.message}")
            try { socket?.close() } catch (_: IOException) {}
            null
        } catch (e: SecurityException) {
            SLog.w("BtEndpoint", "connect $address: BLUETOOTH_CONNECT not granted")
            try { socket?.close() } catch (_: IOException) {}
            null
        } catch (e: IllegalArgumentException) {
            SLog.w("BtEndpoint", "connect: bad address $address")
            null
        }
    }

    override fun bonded(): List<Pair<String, String>> = try {
        adapter.bondedDevices.orEmpty().map { it.address to (it.name ?: it.address) }
    } catch (e: SecurityException) {
        SLog.w("BtEndpoint", "bondedDevices: BLUETOOTH_CONNECT not granted")
        emptyList()
    }

    override fun available(): Boolean = try { adapter.isEnabled } catch (_: SecurityException) { false }

    override fun close() {
        val ss = synchronized(lock) { server.also { server = null } }
        try { ss?.close() } catch (e: IOException) { SLog.d("BtEndpoint", "close: $e") }
    }
}
