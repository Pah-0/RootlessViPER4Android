package com.llsl.viper4android.rootless

import android.os.Build
import com.llsl.viper4android.viper.DriverStatus
import com.llsl.viper4android.viper.ParamValue
import com.llsl.viper4android.viper.ViperParamPayload

// JNI bindings for app/src/main/cpp/viper_jni.cpp.
object ViperNative {
    init {
        System.loadLibrary("viper_rootless")
    }

    external fun create(samplingRate: Int): Long

    external fun destroy(handle: Long)

    external fun setSamplingRate(
        handle: Long,
        samplingRate: Int,
    )

    external fun reset(handle: Long)

    external fun setParam(
        handle: Long,
        param: Int,
        payload: ByteArray,
    ): Boolean

    external fun process(
        handle: Long,
        samples: FloatArray,
        frameCount: Int,
    )

    external fun samplingRate(handle: Long): Int

    external fun processedFrames(handle: Long): Long

    external fun convolverKernelId(handle: Long): Int

    external fun versionName(): String

    external fun versionCode(): Int
}

// The ViPER engine running in this process: the rootless replacement for the
// driver's AudioEffect. Parameters may be set from any thread while the
// capture thread calls process().
class ViperEngine(
    samplingRate: Int = 48000,
) {
    private val handle = ViperNative.create(samplingRate)

    var samplingRate: Int
        get() = ViperNative.samplingRate(handle)
        set(value) {
            if (value != samplingRate) ViperNative.setSamplingRate(handle, value)
        }

    fun set(
        param: Int,
        value: ParamValue,
    ): Boolean = ViperNative.setParam(handle, param, ViperParamPayload.encode(value))

    fun process(
        samples: FloatArray,
        frameCount: Int,
    ) = ViperNative.process(handle, samples, frameCount)

    fun reset() = ViperNative.reset(handle)

    // Only once nothing can call process() any more.
    fun close() = ViperNative.destroy(handle)

    fun status(enabled: Boolean): DriverStatus =
        DriverStatus(
            enabled = enabled,
            sampleRate = ViperNative.samplingRate(handle),
            processedFrames = ViperNative.processedFrames(handle),
            kernelId = ViperNative.convolverKernelId(handle),
            versionCode = ViperNative.versionCode(),
            versionName = ViperNative.versionName(),
            arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
        )
}
