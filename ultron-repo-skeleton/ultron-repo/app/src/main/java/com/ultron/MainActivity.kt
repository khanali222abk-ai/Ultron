package com.ultron

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.ultron.ui.orb.AssistantOrb
import com.ultron.voice.AssistantBus
import com.ultron.voice.UltronForegroundService

class MainActivity : ComponentActivity() {

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startUltronService() else showMicDeniedMessage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by AssistantBus.orbState.collectAsState()
            val amplitude by AssistantBus.amplitude.collectAsState()
            AssistantOrb(state = state, amplitude = amplitude)
        }

        ensureMicPermissionThenStart()
    }

    private fun ensureMicPermissionThenStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startUltronService() else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startUltronService() {
        val intent = Intent(this, UltronForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showMicDeniedMessage() {
        android.widget.Toast.makeText(
            this,
            "Ultron needs the mic to hear the wake word — enable it in Settings to use voice.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}
