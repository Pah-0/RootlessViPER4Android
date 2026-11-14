package com.llsl.viper4android.rootless

import com.llsl.viper4android.effect.EffectState
import com.llsl.viper4android.utils.FileLogger
import com.llsl.viper4android.viper.BulkKind
import com.llsl.viper4android.viper.DriverStatus
import com.llsl.viper4android.viper.ParamValue
import com.llsl.viper4android.viper.ViperParams
import com.llsl.viper4android.viper.ViperParamsSerializer
import com.llsl.viper4android.viper.ViperTransport

// Sends parameters to the in-process engine instead of a driver, with the same
// payloads the driver transports use.
class RootlessTransport(
    private val engine: ViperEngine,
    private val isProcessing: () -> Boolean,
) : ViperTransport {
    override var bulkSideChannels: ((ddcDevice: String, convolverKernel: String, force: Boolean) -> Unit)? = null

    private val bulkKeys = mutableMapOf<BulkKind, String>()

    override fun set(
        param: Int,
        value: ParamValue,
    ) {
        if (!engine.set(param, value)) {
            FileLogger.w("RootlessTransport", "Engine rejected param $param: $value")
        }
    }

    override fun pushFullState(
        state: EffectState,
        forceBulk: Boolean,
    ) {
        val snapshot = ParamValue.Bytes(ViperParamsSerializer.toByteArray(state))
        if (!engine.set(ViperParams.PARAM_SET_FULL_PARAMS, snapshot)) {
            FileLogger.w("RootlessTransport", "Full snapshot dispatch failed")
        }
        val hasDdc = state.ddc.enable && state.ddc.device.isNotEmpty()
        val hasKernel = state.convolver.enable && state.convolver.kernelFile.isNotEmpty()
        if (hasDdc || hasKernel) {
            bulkSideChannels?.invoke(
                if (hasDdc) state.ddc.device else "",
                if (hasKernel) state.convolver.kernelFile else "",
                forceBulk,
            )
        }
    }

    override fun probeStatus(): DriverStatus = engine.status(isProcessing())

    override fun shouldStream(
        kind: BulkKind,
        key: String,
        force: Boolean,
    ): Boolean = force || bulkKeys[kind] != key

    override fun markStreamed(
        kind: BulkKind,
        key: String,
    ) {
        bulkKeys[kind] = key
    }
}
