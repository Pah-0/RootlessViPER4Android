package com.llsl.viper4android.audio

data class AudioDevice(
    val id: String,
    val name: String,
    val type: Int,
    val isHeadphone: Boolean,
    // The name Android reported, when it isn't the one shown: for the
    // headphone jack that's the phone's own model.
    val reportedName: String? = null,
) {
    companion object {
        const val ID_SPEAKER = "speaker"
        const val ID_WIRED = "wired_headphone"
        const val ID_ANDROID_AUTO = "android_auto"

        val DEFAULT_SPEAKER = AudioDevice(ID_SPEAKER, "Speaker", 0, false)
    }
}
