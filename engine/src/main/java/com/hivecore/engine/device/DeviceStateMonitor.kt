package com.hivecore.engine.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.hivecore.core.DeviceState
import com.hivecore.core.SLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [DeviceState] from the framework (plan 13):
 *  - charging + battery temperature from the sticky `ACTION_BATTERY_CHANGED` (`EXTRA_PLUGGED`, `EXTRA_TEMPERATURE` in tenths of a degree);
 *  - thermal status from `PowerManager.addThermalStatusListener` (0 NONE … 6 SHUTDOWN);
 *  - `noInternet` from the default network callback: true unless the network has `NET_CAPABILITY_VALIDATED`, and true when there is no network;
 *  - airplane mode from `Settings.Global.AIRPLANE_MODE_ON` plus `ACTION_AIRPLANE_MODE_CHANGED`.
 * Call [start] once (Application or MainActivity) and [stop] to unregister. Every flow holds its last value, so the
 * Setup screen and the `STATE` hook read them without waiting.
 */
class DeviceStateMonitor(context: Context) : DeviceState {
    private val app = context.applicationContext

    private val chargingState = MutableStateFlow(false)
    private val tempState = MutableStateFlow<Float?>(null)
    private val thermalState = MutableStateFlow(0)
    private val noInternetState = MutableStateFlow(true)
    private val airplaneState = MutableStateFlow(false)

    override val charging: StateFlow<Boolean> = chargingState
    override val batteryTempC: StateFlow<Float?> = tempState
    override val thermal: StateFlow<Int> = thermalState
    override val noInternet: StateFlow<Boolean> = noInternetState
    override val airplane: StateFlow<Boolean> = airplaneState

    @Volatile private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onBattery(i)
    }
    private val airplaneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val on = i.getBooleanExtra("state", readAirplane())
            airplaneState.value = on
            SLog.d(TAG, "airplane=$on")
        }
    }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalState.value = status
        SLog.d(TAG, "thermal=$status")
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (noInternetState.value != !validated) SLog.d(TAG, "network validated=$validated")
            noInternetState.value = !validated
        }
        override fun onLost(network: Network) { noInternetState.value = true; SLog.d(TAG, "network lost") }
        override fun onUnavailable() { noInternetState.value = true }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        try {
            val sticky = ContextCompat.registerReceiver(app, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            if (sticky != null) onBattery(sticky)
        } catch (e: Exception) { SLog.e(TAG, "battery receiver", e) }
        try {
            ContextCompat.registerReceiver(app, airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            airplaneState.value = readAirplane()
        } catch (e: Exception) { SLog.e(TAG, "airplane receiver", e) }
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalState.value = pm.currentThermalStatus
            pm.addThermalStatusListener(thermalListener)
        } catch (e: Exception) { SLog.e(TAG, "thermal listener", e) }
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            noInternetState.value = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) { SLog.e(TAG, "network callback", e) }
        SLog.d(TAG, "started: charging=${chargingState.value} temp=${tempState.value} thermal=${thermalState.value} noInternet=${noInternetState.value} airplane=${airplaneState.value}")
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(batteryReceiver) }
        runCatching { app.unregisterReceiver(airplaneReceiver) }
        runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener) }
        runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback) }
        SLog.d(TAG, "stopped")
    }

    private fun onBattery(i: Intent) {
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temp = if (tenths == Int.MIN_VALUE) null else tenths / 10f
        if (chargingState.value != plugged) SLog.d(TAG, "charging=$plugged temp=$temp")
        chargingState.value = plugged
        tempState.value = temp
    }

    private fun readAirplane(): Boolean =
        try { Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } catch (_: Exception) { false }

    private companion object { const val TAG = "DeviceState" }
}
