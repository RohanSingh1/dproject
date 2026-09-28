package com.dproject.remote.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.dproject.remote.databinding.ActivityMainBinding
import com.dproject.remote.service.RemoteInputService
import com.dproject.remote.service.StreamingService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var pendingSessionId: String? = null

    companion object {
        private const val REQ_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle deep link (https://your-domain.com/?s=SESSIONID)
        intent?.data?.let { uri ->
            uri.getQueryParameter("s")?.let { sid ->
                binding.etSessionId.setText(sid)
            }
        }

        binding.btnConnect.setOnClickListener {
            val sid = binding.etSessionId.text?.toString()?.trim()?.uppercase()
            if (sid.isNullOrEmpty()) {
                binding.tvStatus.text = "Enter a session ID first."
                return@setOnClickListener
            }
            pendingSessionId = sid
            requestScreenCapture()
        }

        binding.btnPermissions.setOnClickListener {
            openAccessibilitySettings()
        }

        updatePermissionButton()
    }

    private fun requestScreenCapture() {
        binding.tvStatus.text = "Requesting screen capture permission…"
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQ_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val sid = pendingSessionId ?: return
                binding.tvStatus.text = "Starting stream for session $sid…"
                StreamingService.start(this, sid, resultCode, data)
            } else {
                binding.tvStatus.text = "Screen capture permission denied."
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun updatePermissionButton() {
        val enabled = RemoteInputService.isEnabled(this)
        binding.btnPermissions.text = if (enabled)
            "Accessibility enabled"
        else
            "Enable Accessibility (for remote input)"
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButton()
    }
}
