package com.example.ipcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        findViewById<Button>(R.id.startButton).setOnClickListener { ensurePermissionsThenStart() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopStreaming() }
        updateUrl()

        // Auto-start streaming when the app opens, if the camera permission is already granted.
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startStreaming()
        }
    }

    private fun ensurePermissionsThenStart() {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isEmpty()) startStreaming()
        else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val camIndex = permissions.indexOf(Manifest.permission.CAMERA)
        when {
            camIndex < 0 -> startStreaming() // camera was already granted
            grantResults.getOrNull(camIndex) == PackageManager.PERMISSION_GRANTED -> startStreaming()
            else -> statusText.text = "Camera permission denied"
        }
    }

    private fun startStreaming() {
        val intent = Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        statusText.text = "Streaming — you can turn the screen off"
        updateUrl()
    }

    private fun stopStreaming() {
        startService(Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_STOP })
        statusText.text = "Stopped"
    }

    private fun updateUrl() {
        val ip = localIpAddress()
        val nameUrl = "http://${CameraService.MDNS_HOST}.local:${CameraService.PORT}/"
        val ipUrl = if (ip != null) "http://$ip:${CameraService.PORT}/" else "Wi-Fi: not connected"
        urlText.text = "$nameUrl\n$ipUrl"
    }

    private fun localIpAddress(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue
                        return host
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
