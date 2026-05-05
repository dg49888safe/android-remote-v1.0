package com.remote.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.remote.agent.service.AgentService

class MainActivity : AppCompatActivity() {

    private var pendingServer = ""
    private var pendingName = ""

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val serviceIntent = Intent(this, AgentService::class.java).apply {
            putExtra("server", pendingServer)
            putExtra("deviceName", pendingName)
            if (result.resultCode == RESULT_OK && result.data != null) {
                putExtra("projectionResultCode", result.resultCode)
                putExtra("projectionData", result.data)
            }
        }
        startForegroundService(serviceIntent)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        if (result.resultCode == RESULT_OK) {
            tvStatus.text = "状态：运行中 ✅（屏幕录制已授权）"
        } else {
            tvStatus.text = "状态：运行中 ⚠️（屏幕录制未授权，屏控不可用）"
            Toast.makeText(this, "未授权屏幕录制，屏控功能不可用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("agent", MODE_PRIVATE)
        val etServer = findViewById<EditText>(R.id.etServer)
        val etDeviceName = findViewById<EditText>(R.id.etDeviceName)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        etServer.setText(prefs.getString("server", "ws://ubuntu222506test.webredirect.org/ws"))
        etDeviceName.setText(prefs.getString("deviceName", android.os.Build.MODEL))

        btnStart.setOnClickListener {
            pendingServer = etServer.text.toString().trim()
            pendingName = etDeviceName.text.toString().trim()
            prefs.edit().putString("server", pendingServer).putString("deviceName", pendingName).apply()

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            tvStatus.text = "状态：已停止"
        }

        tvStatus.text = if (AgentService.isRunning) "状态：运行中 ✅" else "状态：未启动"

        requestPermissions()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }
}
