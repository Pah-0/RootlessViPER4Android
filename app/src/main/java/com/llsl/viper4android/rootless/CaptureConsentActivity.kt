package com.llsl.viper4android.rootless

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.llsl.viper4android.service.ViperService

// Invisible activity that asks for the microphone permission (needed for any
// AudioRecord), grants the adb-only permissions through Shizuku if it can, and
// then asks for screen capture consent, whose token lets ViperService capture
// other apps' playback.
class CaptureConsentActivity : ComponentActivity() {
    companion object {
        const val NOTIFICATION_ID = 2

        fun intent(context: Context): Intent =
            Intent(context, CaptureConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) grantAdbPermissions() else finishWith(null)
        }

    private val projectionRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finishWith(result.data?.takeIf { result.resultCode == RESULT_OK })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (savedInstanceState != null) return
        val missing =
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) grantAdbPermissions() else permissionRequest.launch(missing.toTypedArray())
    }

    // With the PROJECT_MEDIA app-op granted, the consent below is approved
    // without a prompt.
    private fun grantAdbPermissions() {
        AdbGrants.grantWithShizuku(this) {
            if (!isFinishing) requestProjection()
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Capturing a single app would capture only that app's audio.
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        projectionRequest.launch(intent)
    }

    private fun finishWith(projectionData: Intent?) {
        ViperService.onCaptureConsent(this, projectionData)
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
