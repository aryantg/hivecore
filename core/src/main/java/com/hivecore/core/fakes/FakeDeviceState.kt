package com.hivecore.core.fakes

import com.hivecore.core.DeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Mutable flows; defaults = on battery, cool, no internet, airplane off. */
class FakeDeviceState : DeviceState {
    val chargingState = MutableStateFlow(false)
    val tempState = MutableStateFlow<Float?>(31.0f)
    val thermalState = MutableStateFlow(0)
    val noInternetState = MutableStateFlow(true)
    val airplaneState = MutableStateFlow(false)
    override val charging: StateFlow<Boolean> = chargingState
    override val batteryTempC: StateFlow<Float?> = tempState
    override val thermal: StateFlow<Int> = thermalState
    override val noInternet: StateFlow<Boolean> = noInternetState
    override val airplane: StateFlow<Boolean> = airplaneState
}
