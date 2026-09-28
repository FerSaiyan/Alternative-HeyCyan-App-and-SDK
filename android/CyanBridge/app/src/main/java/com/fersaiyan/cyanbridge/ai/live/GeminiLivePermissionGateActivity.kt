package com.fersaiyan.cyanbridge.ai.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Request permissions before opening a background microphone session. If notifications
 * are declined, use the visible activity-scoped preview with its own Stop button
 * instead of starting an invisible microphone foreground service.
 */
class GeminiLivePermissionGateActivity : AppCompatActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { launchPermittedRoute() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) launchPermittedRoute()
        else if (savedInstanceState == null) permissions.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun launchPermittedRoute() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission is required for Gemini Live", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val initialImage = intent.getStringExtra(EXTRA_INITIAL_IMAGE)
        val initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)
        if (notificationsAllowed) {
            GeminiLiveForegroundService.start(
                context = this,
                language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty(),
                imagePrompt = intent.getStringExtra(EXTRA_IMAGE_PROMPT).orEmpty(),
                initialImagePath = initialImage,
                initialPrompt = initialPrompt,
                useRelay = intent.getBooleanExtra(EXTRA_USE_RELAY, false),
            )
        } else {
            Toast.makeText(
                this,
                "Notifications are off: Live will run only in the open preview. Use its Stop button.",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(Intent(this, GeminiLiveActivity::class.java).apply {
                putExtra(GeminiLiveActivity.EXTRA_AUTO_START, true)
                initialImage?.let { putExtra(GeminiLiveActivity.EXTRA_INITIAL_IMAGE_PATH, it) }
                initialPrompt?.let { putExtra(GeminiLiveActivity.EXTRA_INITIAL_PROMPT, it) }
            })
        }
        finish()
    }

    companion object {
        private const val EXTRA_LANGUAGE = "live_language"
        private const val EXTRA_IMAGE_PROMPT = "live_image_prompt"
        private const val EXTRA_INITIAL_IMAGE = "live_initial_image"
        private const val EXTRA_INITIAL_PROMPT = "live_initial_prompt"
        private const val EXTRA_USE_RELAY = "live_use_relay"

        fun launch(
            activity: Activity,
            language: String,
            imagePrompt: String,
            initialImagePath: String? = null,
            initialPrompt: String? = null,
            useRelay: Boolean = false,
        ) {
            activity.startActivity(Intent(activity, GeminiLivePermissionGateActivity::class.java).apply {
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_IMAGE_PROMPT, imagePrompt)
                initialImagePath?.let { putExtra(EXTRA_INITIAL_IMAGE, it) }
                initialPrompt?.let { putExtra(EXTRA_INITIAL_PROMPT, it) }
                putExtra(EXTRA_USE_RELAY, useRelay)
            })
        }
    }
}
