package com.llsl.viper4android.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.llsl.viper4android.R
import com.llsl.viper4android.SERVICE_CHANNEL_ID
import com.llsl.viper4android.audio.AudioDevice
import com.llsl.viper4android.audio.AudioOutputDetector
import com.llsl.viper4android.audio.AudioSessionMonitor
import com.llsl.viper4android.data.model.DeviceSettings
import com.llsl.viper4android.data.repository.ViperRepository
import com.llsl.viper4android.effect.EffectState
import com.llsl.viper4android.effect.deserializeEffectPrefs
import com.llsl.viper4android.effect.loadEffectStateFromPrefs
import com.llsl.viper4android.effect.serializeEffectPrefs
import com.llsl.viper4android.library.FileLibrary
import com.llsl.viper4android.library.LibraryKind
import com.llsl.viper4android.rootless.CaptureConsentActivity
import com.llsl.viper4android.rootless.RootlessCapture
import com.llsl.viper4android.rootless.RootlessTransport
import com.llsl.viper4android.rootless.SessionDump
import com.llsl.viper4android.rootless.ViperEngine
import com.llsl.viper4android.utils.FileLogger
import com.llsl.viper4android.utils.WavDecoder
import com.llsl.viper4android.viper.BulkKind
import com.llsl.viper4android.viper.DriverStatus
import com.llsl.viper4android.viper.EffectRegistry
import com.llsl.viper4android.viper.ParamValue
import com.llsl.viper4android.viper.ViperParams
import com.llsl.viper4android.viper.ViperTransport
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.zip.CRC32
import javax.inject.Inject

@AndroidEntryPoint
class ViperService : LifecycleService() {
    @Inject
    lateinit var repository: ViperRepository

    companion object {
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.llsl.viper4android.service.START"
        const val ACTION_STOP = "com.llsl.viper4android.service.STOP"
        const val ACTION_TOGGLE_MASTER = "com.llsl.viper4android.service.TOGGLE_MASTER"
        const val EXTRA_MASTER_ENABLED = "com.llsl.viper4android.service.EXTRA_MASTER_ENABLED"
        private const val ACTION_CAPTURE_CONSENT = "com.llsl.viper4android.service.CAPTURE_CONSENT"
        private const val EXTRA_PROJECTION_DATA = "com.llsl.viper4android.service.EXTRA_PROJECTION_DATA"
        private const val CONSENT_RETRY_MS = 30_000L

        // Whether audio is being captured and processed right now.
        @Volatile
        var isCapturing: Boolean = false
            private set

        // Called by CaptureConsentActivity with the screen capture token, or
        // null if the user refused.
        fun onCaptureConsent(
            context: Context,
            projectionData: Intent?,
        ) {
            val intent =
                Intent(context, ViperService::class.java).apply {
                    action = ACTION_CAPTURE_CONSENT
                    putExtra(EXTRA_PROJECTION_DATA, projectionData)
                }
            context.startForegroundService(intent)
        }

        fun startService(context: Context) {
            val intent =
                Intent(context, ViperService::class.java).apply {
                    action = ACTION_START
                }
            context.startForegroundService(intent)
        }

        fun toggleMaster(
            context: Context,
            enabled: Boolean,
        ) {
            val intent =
                Intent(context, ViperService::class.java).apply {
                    action = ACTION_TOGGLE_MASTER
                    putExtra(EXTRA_MASTER_ENABLED, enabled)
                }
            context.startForegroundService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        val service: ViperService get() = this@ViperService
    }

    private val binder = LocalBinder()
    private val effectRegistry = EffectRegistry()
    private lateinit var transport: ViperTransport
    private var useAidlTypeUuid: Boolean = true
    private var globalMode: Boolean = false
    private var audioOutputDetector: AudioOutputDetector? = null
    private var sessionMonitor: AudioSessionMonitor? = null
    private var stateProvider: (() -> EffectState)? = null
    private var lastUiState: EffectState? = null

    // Rootless processing: the engine runs here, fed by RootlessCapture.
    private val engine by lazy { ViperEngine() }
    private var projection: MediaProjection? = null
    private var capture: RootlessCapture? = null
    private val projectionCallback =
        object : MediaProjection.Callback() {
            // The user stopped sharing from the status bar.
            override fun onStop() {
                FileLogger.i("Service", "Media projection stopped")
                stopCapture()
                setMasterOff()
            }
        }

    @Volatile
    private var excludedApps: Set<String> = emptySet()

    private data class DecodedKernel(
        val samples: FloatArray,
        val rawBytes: ByteArray,
        val totalFloats: Int,
        val channelCount: Int,
        val crc: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DecodedKernel

            if (totalFloats != other.totalFloats) return false
            if (channelCount != other.channelCount) return false
            if (crc != other.crc) return false
            if (!samples.contentEquals(other.samples)) return false
            if (!rawBytes.contentEquals(other.rawBytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = totalFloats
            result = 31 * result + channelCount
            result = 31 * result + crc
            result = 31 * result + samples.contentHashCode()
            result = 31 * result + rawBytes.contentHashCode()
            return result
        }
    }

    private val decodedKernelCache =
        object : LinkedHashMap<String, DecodedKernel>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecodedKernel>?) = size > 3
        }

    private var bootMasterEnabled: Boolean = false
    private val masterEnabled: Boolean
        get() = stateProvider?.invoke()?.masterEnable ?: lastUiState?.masterEnable ?: bootMasterEnabled

    fun setStateProvider(provider: () -> EffectState) {
        stateProvider = provider
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundAs(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        FileLogger.i("Service", "Service created")
        lifecycleScope.launch {
            ensureConfigLoaded()
            launch {
                repository.getStringSetPreference(ViperRepository.PREF_EXCLUDED_APPS).collect {
                    excludedApps = it
                    capture?.setExcludedPackages(it)
                }
            }
            startAudioOutputMonitor()
        }
    }

    private var configLoaded = false

    private suspend fun ensureConfigLoaded() {
        if (configLoaded) return
        transport = RootlessTransport(engine) { capture?.isRunning == true }.also { installBulkSideChannels(it) }
        globalMode = repository.getBooleanPreference(ViperRepository.PREF_GLOBAL_MODE).first()
        bootMasterEnabled = repository.getBooleanPreference(ViperRepository.PREF_MASTER_ENABLE).first()
        configLoaded = true
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun initGlobalEffect() {
        effectRegistry.ensureGlobal()
    }

    private fun installBulkSideChannels(t: ViperTransport) {
        t.bulkSideChannels = { ddc, kernel, force ->
            if (ddc.isNotEmpty()) applyDdcDevice(ddc, force)
            if (kernel.isNotEmpty()) applyConvolverKernel(kernel, force)
        }
    }

    private fun applyState(
        state: EffectState,
        masterOn: Boolean,
    ) {
        val atStartup = startupCheckPending
        startupCheckPending = false
        if (!masterOn) {
            stopCapture()
            return
        }
        // Without DUMP the original audio isn't muted, so don't come back on
        // by ourselves; turning it on by hand still works.
        if (atStartup && !SessionDump.hasDumpPermission(this)) {
            FileLogger.w("Service", "DUMP not granted: staying off")
            Toast.makeText(this, R.string.rootless_dump_missing, Toast.LENGTH_LONG).show()
            setMasterOff()
            return
        }
        // Set the rate capture will run at before loading kernels and DDC
        // coefficients, as a rate change resets the engine.
        val rate = RootlessCapture.sampleRate(this)
        val rateChanged = engine.samplingRate != rate
        engine.samplingRate = rate
        transport.pushFullState(state, forceBulk = rateChanged)
        startCapture()
    }

    private fun startCapture() {
        if (capture != null) return
        val mp = projection
        if (mp == null) {
            requestCaptureConsent()
            return
        }
        capture =
            RootlessCapture(this, engine, mp) { reason ->
                mainHandler.post {
                    Toast.makeText(this, getString(R.string.rootless_capture_failed, reason), Toast.LENGTH_LONG).show()
                    stopCapture()
                    setMasterOff()
                }
            }.also {
                it.setExcludedPackages(excludedApps)
                it.start()
            }
        isCapturing = true
    }

    private fun stopCapture() {
        val hadProjection = projection != null
        releaseCapture()
        // Drop the media projection type so the system stops showing that
        // the screen is being shared.
        if (hadProjection) startForegroundAs(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun releaseCapture() {
        capture?.stop()
        capture = null
        isCapturing = false
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
    }

    private fun onProjectionGranted(data: Intent) {
        startForegroundAs(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        projection?.let { releaseCapture() }
        projection =
            try {
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(Activity.RESULT_OK, data)
                    ?.also { it.registerCallback(projectionCallback, mainHandler) }
            } catch (e: Exception) {
                FileLogger.e("Service", "Failed to get media projection", e)
                null
            }
        if (projection == null) {
            stopCapture()
            setMasterOff()
            return
        }
        lifecycleScope.launch {
            ensureConfigLoaded()
            // Consent comes from turning processing on.
            repository.setBooleanPreference(ViperRepository.PREF_MASTER_ENABLE, true)
            bootMasterEnabled = true
            applyState(currentDeviceState(), true)
        }
    }

    // Asks for capture consent. Android blocks activity starts from the
    // background (after boot, from the tile), so a notification offers it too.
    private fun requestCaptureConsent() {
        // State pushes while the dialog is up would reopen it.
        val now = SystemClock.elapsedRealtime()
        if (now - consentRequestedAt < CONSENT_RETRY_MS) return
        consentRequestedAt = now
        val consent = CaptureConsentActivity.intent(this)
        val notification =
            NotificationCompat
                .Builder(this, SERVICE_CHANNEL_ID)
                .setContentTitle(getString(R.string.rootless_consent_title))
                .setContentText(getString(R.string.rootless_consent_text))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(this, 1, consent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                ).build()
        getSystemService(NotificationManager::class.java)?.notify(CaptureConsentActivity.NOTIFICATION_ID, notification)
        try {
            startActivity(consent)
        } catch (e: Exception) {
            FileLogger.w("Service", "Can't open capture consent: ${e.message}")
        }
    }

    private fun setMasterOff() {
        bootMasterEnabled = false
        lifecycleScope.launch {
            repository.setBooleanPreference(ViperRepository.PREF_MASTER_ENABLE, false)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Whether the first state applied since the service started, which turns
    // ViPER back on if it was left on, is still to come.
    private var startupCheckPending = true
    private var consentRequestedAt = -CONSENT_RETRY_MS

    private fun startForegroundAs(type: Int) = ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

    private var currentServiceDeviceId: String = ""

    private fun startAudioOutputMonitor() {
        val detector = AudioOutputDetector(this)
        audioOutputDetector = detector
        lifecycleScope.launch {
            detector.activeDevice.collect { device ->
                if (device.id != currentServiceDeviceId) {
                    FileLogger.i(
                        "Service",
                        "Device changed: $currentServiceDeviceId -> ${device.id} (${device.name})",
                    )
                    currentServiceDeviceId = device.id
                    lastUiState = null
                    reapplyForDevice(device)
                }
            }
        }
    }

    private suspend fun reapplyForDevice(device: AudioDevice) {
        val saved = repository.getDeviceSettings(device.id)
        val state: EffectState =
            if (saved != null) {
                FileLogger.i("Service", "Loading device settings from DB for ${device.id}")
                val baseState = loadEffectStateFromPrefs(repository)
                val json = JSONObject(saved.settingsJson)
                deserializeEffectPrefs(json, baseState).also {
                    repository.updateDeviceLastConnected(device.id)
                }
            } else {
                FileLogger.i("Service", "No DB entry for ${device.id}, using DataStore defaults")
                val s = loadEffectStateFromPrefs(repository)
                val json = serializeEffectPrefs(s)
                repository.saveDeviceSettings(
                    DeviceSettings(
                        deviceId = device.id,
                        deviceName = device.name,
                        isHeadphone = device.isHeadphone,
                        settingsJson = json.toString(),
                    ),
                )
                s
            }
        lastUiState = state
        applyState(state, masterEnabled)
    }

    private suspend fun currentDeviceState(): EffectState {
        stateProvider?.invoke()?.let { state ->
            if (state.activeDeviceId == currentServiceDeviceId) return state
        }
        lastUiState?.let { return it }
        val device = audioOutputDetector?.activeDevice?.value ?: AudioDevice.DEFAULT_SPEAKER
        val baseState = loadEffectStateFromPrefs(repository)
        val saved = repository.getDeviceSettings(device.id) ?: return baseState
        return deserializeEffectPrefs(JSONObject(saved.settingsJson), baseState)
    }

    private suspend fun dispatchFullStateToEffect() {
        if (!masterEnabled) return
        val state = currentDeviceState()
        lastUiState = state
        transport.pushFullState(state, forceBulk = true)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                FileLogger.i("Service", "Service started")
            }

            ACTION_STOP -> {
                releaseCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TOGGLE_MASTER -> {
                val next = intent.getBooleanExtra(EXTRA_MASTER_ENABLED, false)
                lifecycleScope.launch {
                    ensureConfigLoaded()
                    bootMasterEnabled = next
                    val state = currentDeviceState()
                    stateProvider = null
                    lastUiState = state
                    applyState(state, next)
                }
            }

            ACTION_CAPTURE_CONSENT -> {
                consentRequestedAt = -CONSENT_RETRY_MS
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)
                if (data != null) {
                    onProjectionGranted(data)
                } else {
                    FileLogger.i("Service", "Capture consent refused")
                    setMasterOff()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseCapture()
        bulkExecutor.shutdownNow()
        audioOutputDetector?.stop()
        audioOutputDetector = null
        FileLogger.i("Service", "Service destroyed")
        super.onDestroy()
    }

    private fun startSessionMonitor() {
        stopSessionMonitor()
        val monitor =
            AudioSessionMonitor(
                context = this,
                onSessionOpen = { sessionId, pkg -> openSession(sessionId, pkg) },
                onSessionClose = { sessionId -> closeSession(sessionId) },
            )
        monitor.start()
        sessionMonitor = monitor
    }

    private fun stopSessionMonitor() {
        sessionMonitor?.stop()
        sessionMonitor = null
    }

    private fun openSession(
        sessionId: Int,
        packageName: String,
    ) {
        if (!masterEnabled) {
            FileLogger.d(
                "Service",
                "Master off: skipping per-app session $sessionId ($packageName)",
            )
            return
        }
        if (globalMode) {
            FileLogger.d(
                "Service",
                "Global mode: skipping per-app session $sessionId ($packageName)",
            )
            return
        }
        if (packageName in excludedApps) {
            FileLogger.i(
                "Service",
                "Excluded app: skipping session $sessionId ($packageName)",
            )
            return
        }
        if (effectRegistry.isSessionOpen(sessionId)) {
            FileLogger.w("Service", "Session $sessionId already open")
            return
        }

        effectRegistry.openSession(sessionId, packageName) ?: return

        lifecycleScope.launch {
            dispatchFullStateToEffect()
            FileLogger.i("Service", "Applied full state to session $sessionId")
        }
    }

    private fun closeSession(sessionId: Int) {
        effectRegistry.closeSession(sessionId)
    }

    private fun releaseAllSessions() {
        effectRegistry.releaseAll()
    }

    fun setParam(
        param: Int,
        value: ParamValue,
    ) = transport.set(param, value)

    fun dispatchFullState(state: EffectState) {
        lastUiState = state
        applyState(state, masterEnabled)
    }

    fun parseVdc(
        text: String,
        name: String,
    ): Pair<List<FloatArray>, List<FloatArray>>? {
        try {
            var coeffs44100: FloatArray? = null
            var coeffs48000: FloatArray? = null
            for (line in text.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("SR_44100:") -> {
                        coeffs44100 =
                            trimmed
                                .removePrefix("SR_44100:")
                                .split(",")
                                .map { it.trim().toFloat() }
                                .toFloatArray()
                    }

                    trimmed.startsWith("SR_48000:") -> {
                        coeffs48000 =
                            trimmed
                                .removePrefix("SR_48000:")
                                .split(",")
                                .map { it.trim().toFloat() }
                                .toFloatArray()
                    }
                }
            }

            val a = coeffs44100
            val b = coeffs48000
            if (a == null || b == null || a.isEmpty() || a.size != b.size || a.size % 5 != 0) {
                FileLogger.w(
                    "Service",
                    "DDC coefficient parse failure: $name 44100=${a?.size} 48000=${b?.size}",
                )
                return null
            }
            val sec44 = a.toList().chunked(5).map { it.toFloatArray() }
            val sec48 = b.toList().chunked(5).map { it.toFloatArray() }
            return sec44 to sec48
        } catch (e: Exception) {
            FileLogger.e("Service", "Failed to parse DDC: $name", e)
            return null
        }
    }

    // Kernel and VDC loads read and decode files, which can be large or in a
    // slow linked folder: they run one at a time off the main thread.
    private val bulkExecutor = Executors.newSingleThreadExecutor()

    fun applyConvolverKernel(
        fileName: String,
        force: Boolean = false,
    ) {
        bulkExecutor.execute { loadConvolverKernel(fileName, force) }
    }

    fun applyDdcDevice(
        name: String,
        force: Boolean = false,
    ) {
        bulkExecutor.execute { loadDdcDevice(name, force) }
    }

    private fun loadConvolverKernel(
        fileName: String,
        force: Boolean,
    ) {
        if (!transport.shouldStream(BulkKind.CONVOLVER, fileName, force)) return
        val sendInts: (Int, Int, Int, Int) -> Unit =
            { p, a, b, c -> transport.set(p, ParamValue.Ints(intArrayOf(a, b, c))) }
        val sendFloats: (Int, FloatArray) -> Unit =
            { p, v -> transport.set(p, ParamValue.Floats(v)) }
        if (fileName.isEmpty()) {
            sendInts(ViperParams.PARAM_CONVOLVER_PREPARE_BUFFER, 0, 0, 1)
            return
        }
        try {
            val decoded = decodedKernelCache[fileName]
            val samples: FloatArray
            val rawBytes: ByteArray
            val totalFloats: Int
            val channelCount: Int
            val crc: Int
            if (decoded != null) {
                samples = decoded.samples
                totalFloats = decoded.totalFloats
                channelCount = decoded.channelCount
                crc = decoded.crc
            } else {
                val bytes = FileLibrary.get(this).read(LibraryKind.KERNEL, fileName)
                if (bytes == null) {
                    FileLogger.w("Service", "Kernel file missing: $fileName")
                    return
                }
                val d = WavDecoder.decode(bytes)
                samples = d.samples
                totalFloats = d.samples.size
                channelCount = d.channels
                FileLogger.i("Service", "Kernel decoded: $fileName samples=$totalFloats ch=$channelCount")
                rawBytes =
                    ByteBuffer
                        .allocate(totalFloats * 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .also { for (f in samples) it.putFloat(f) }
                        .array()
                crc = CRC32().apply { update(rawBytes) }.value.toInt()
                decodedKernelCache[fileName] =
                    DecodedKernel(samples, rawBytes, totalFloats, channelCount, crc)
            }

            sendInts(ViperParams.PARAM_CONVOLVER_PREPARE_BUFFER, totalFloats, channelCount, 0)

            val maxFloatsPerChunk = 2046
            var offset = 0
            var chunkIndex = 0
            while (offset < totalFloats) {
                val floatsInChunk = minOf(totalFloats - offset, maxFloatsPerChunk)
                sendFloats(
                    ViperParams.PARAM_CONVOLVER_SET_BUFFER,
                    samples.copyOfRange(offset, offset + floatsInChunk),
                )
                offset += floatsInChunk
                chunkIndex++
            }

            val kernelId = fileName.hashCode()
            sendInts(ViperParams.PARAM_CONVOLVER_COMMIT_BUFFER, totalFloats, crc, kernelId)
            FileLogger.i("Service", "Kernel streamed: $fileName chunks=$chunkIndex crc=0x${crc.toUInt().toString(16)}")
            transport.markStreamed(BulkKind.CONVOLVER, fileName)
        } catch (e: Exception) {
            FileLogger.e("Service", "Failed to stream kernel: $fileName", e)
        }
    }

    private fun loadDdcDevice(
        name: String,
        force: Boolean,
    ) {
        if (!transport.shouldStream(BulkKind.DDC, name, force)) return
        val sendFloats: (Int, FloatArray) -> Unit =
            { p, v -> transport.set(p, ParamValue.Floats(v)) }
        if (name.isEmpty()) {
            sendFloats(ViperParams.PARAM_DDC_COEFFICIENTS, FloatArray(0))
            return
        }
        val bytes = FileLibrary.get(this).read(LibraryKind.VDC, name)
        if (bytes == null) {
            FileLogger.w("Service", "DDC file missing: $name")
            return
        }
        val parsed = parseVdc(bytes.decodeToString(), FileLibrary.get(this).label(LibraryKind.VDC, name)) ?: return
        val sec44100 = parsed.first
        val sec48000 = parsed.second
        val floatsPerRate = sec44100.size * 5
        val coeffs = FloatArray(floatsPerRate * 2)
        var i = 0
        for (s in sec44100) for (v in s) coeffs[i++] = v
        for (s in sec48000) for (v in s) coeffs[i++] = v
        sendFloats(ViperParams.PARAM_DDC_COEFFICIENTS, coeffs)
        transport.markStreamed(BulkKind.DDC, name)
    }

    fun probeDriverStatus(): DriverStatus? = transport.probeStatus()

    fun setGlobalMode(enabled: Boolean) {
        globalMode = enabled
        if (!masterEnabled) {
            applyState(EffectState(), false)
            return
        }
        if (enabled) {
            stopSessionMonitor()
            releaseAllSessions()
        } else {
            effectRegistry.releaseGlobal()
        }
        lifecycleScope.launch {
            applyState(currentDeviceState(), true)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent =
            launchIntent?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        return NotificationCompat
            .Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
